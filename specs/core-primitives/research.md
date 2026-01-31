# Core Primitives - Research

## Problem Statement

When Claude (or any automated system) modifies source files, valuable context is lost:
- How many lines were added/removed?
- Is this file getting too large?
- Which namespaces need reloading?
- What should happen next?

Direct file edits provide none of this. We need a primitive layer that wraps file operations with metadata tracking, enabling intelligent workflows where the system can guide next steps based on what just happened.

## Requirements

### Functional Requirements

1. **Write effect**: Replace entire file contents, return metadata
2. **Append effect**: Add content to end of file with threshold awareness
3. **Insert effect**: Add content at specific line or marker
4. **Replace effect**: Find/replace patterns within file
5. **Read-meta effect**: Get file metadata without full content read
6. All effects must return consistent result shape
7. Track pending namespace reloads in dispatch-data
8. **Threshold detection**: Report when files exceed configured limits
9. **Formatting**: Optional post-write formatting with pluggable formatters

### Non-Functional Requirements

- **Performance**: File operations should be atomic where possible
- **Safety**: Never lose data on failed writes (write to temp, then rename)
- **Composability**: Effects should work well with sandestin actions and interceptors
- **Discoverability**: Full schema documentation for all effects

## Result Shape

All file-modifying effects return this consistent shape:

```clojure
{:path "/absolute/path/to/file.clj"
 :status :ok              ;; :ok | :created | :error
 :loc {:before 1423       ;; nil if file was created
       :after 1456
       :delta 33}
 :threshold {:limit 1500  ;; nil if no threshold specified
             :exceeded? false
             :remaining 44}
 :hints []                ;; Always empty from phandaal; domain libraries populate
 :reload {:namespaces [sandbox.ui]  ;; nil for non-Clojure files
          :type :clj-reload}
 :formatted? true}        ;; Was formatter called? false if failed, nil if none configured
```

**Notes:**
- The `:loc` values reflect the file state *after* formatting. This ensures threshold checks are accurate against the actual formatted result.
- The `:hints` array is always empty from phandaal. Domain libraries wrap phandaal effects and populate hints with actionable, context-aware guidance. See [Threshold Detection and Hints](#threshold-detection-and-hints) below.

### Status Values

| Status | Meaning |
|--------|---------|
| `:ok` | File existed and was modified |
| `:created` | File did not exist and was created |
| `:error` | Operation failed (details in `:error` key) |

### Threshold Detection and Hints

Phandaal **detects** threshold violations but does **not** provide guidance on what to do about them. The `:hints` array in results is always empty from phandaal.

**Why?** Threshold guidance is deeply domain-specific:
- CSS files might need barrel imports by category
- Clojure namespaces might split by feature or layer
- Route files might group by API domain

Phandaal can't know these conventions. Domain libraries (tsain, route managers, etc.) wrap phandaal effects and add meaningful, actionable hints:

```clojure
;; Domain library wraps phandaal and adds context-aware hints
(fn [{:keys [dispatch]} system args]
  (let [result (first (:results (dispatch [[::phandaal/append {...}]])))]
    (if (get-in result [:threshold :exceeded?])
      (update result :hints conj
        {:type :css-barrel-import
         :severity :warning
         :message "styles.css exceeds 1500 lines"
         :instructions ["Create components/cards.css"
                       "Move card-related rules"
                       "Add @import to main stylesheet"]
         :action [::tsain/split-css {:category :cards}]})
      result)))
```

This separation keeps phandaal focused on detection while domain libraries provide actionable guidance that Claude can actually use.

## Options Considered

### Option A: Thin Wrappers

**Description:** Minimal wrappers around `spit`/`slurp` that just add LOC counting.

**Pros:**
- Simple implementation
- Low overhead

**Cons:**
- No atomic writes
- No threshold logic
- Limited metadata

### Option B: Rich Primitives (Recommended)

**Description:** Full-featured effects with atomic writes, threshold detection, namespace inference, and consistent result shapes.

**Pros:**
- Complete metadata for all operations
- Safe atomic writes
- Threshold awareness built-in
- Namespace tracking automatic

**Cons:**
- More complex implementation
- Slightly more overhead per operation

### Option C: Wrapper + Interceptor

**Description:** Thin wrappers with interceptors adding metadata.

**Pros:**
- Separation of concerns
- Interceptors can be optional

**Cons:**
- Harder to reason about (metadata added "magically")
- Result shape depends on interceptor configuration

## Recommendation

**Option B: Rich Primitives**. The overhead is negligible for file operations, and having complete metadata directly from the effect makes the system predictable and self-documenting. Domain libraries can trust the result shape without worrying about interceptor configuration.

## Effect Specifications

### `::phandaal/write`

Replace entire file contents.

```clojure
;; Schema
[:tuple [:= ::phandaal/write]
 [:map
  [:path :string]
  [:content :string]
  [:create-dirs? {:optional true} :boolean]
  [:threshold {:optional true} :int]]]

;; Example
[::phandaal/write {:path "/project/src/app/core.clj"
                :content "(ns app.core)\n\n(defn foo [])"
                :threshold 500}]
```

### `::phandaal/append`

Add content to end of file.

```clojure
;; Schema
[:tuple [:= ::phandaal/append]
 [:map
  [:path :string]
  [:content :string]
  [:threshold {:optional true} :int]]]

;; Example
[::phandaal/append {:path "/project/src/app/routes.clj"
                 :content "\n(defn new-handler [])\n"
                 :threshold 500}]
```

### `::phandaal/insert`

Insert content at specific location.

```clojure
;; Schema
[:tuple [:= ::phandaal/insert]
 [:map
  [:path :string]
  [:content :string]
  [:at [:or
        [:map [:line :int]]           ;; Line number (1-indexed)
        [:map [:after :string]]       ;; After matching line
        [:map [:before :string]]]]    ;; Before matching line
  [:threshold {:optional true} :int]]]

;; Examples
[::phandaal/insert {:path "/project/src/app/core.clj"
                 :content "(def new-var 42)\n"
                 :at {:line 10}}]

[::phandaal/insert {:path "/project/src/app/core.clj"
                 :content "(require '[new.dep :as dep])\n"
                 :at {:after "(ns app.core"}}]
```

### `::phandaal/replace`

Find and replace within file.

```clojure
;; Schema
[:tuple [:= ::phandaal/replace]
 [:map
  [:path :string]
  [:find [:or :string :regex]]
  [:replacement :string]
  [:all? {:optional true} :boolean]]]  ;; Replace all occurrences?

;; Example
[::phandaal/replace {:path "/project/src/app/core.clj"
                  :find "old-function-name"
                  :replacement "new-function-name"
                  :all? true}]
```

### `::phandaal/read-meta`

Get file metadata without reading full content.

```clojure
;; Schema
[:tuple [:= ::phandaal/read-meta] :string]

;; Example
[::phandaal/read-meta "/project/src/app/core.clj"]

;; Returns
{:path "/project/src/app/core.clj"
 :exists? true
 :loc 245
 :modified #inst "2024-01-15T10:00:00"
 :namespace 'app.core}  ;; Inferred for .clj files
```

## Namespace Inference

For Clojure files, phandaal infers the namespace from the file path using configurable source paths:

```clojure
(phandaal/registry {:project-root "/project"
                 :source-paths ["src/clj" "src/cljc" "dev/src/clj"]})

;; /project/src/clj/app/core.clj → app.core
;; /project/dev/src/clj/dev.clj → dev
```

Files outside source paths, or non-Clojure files, return `nil` for namespace.

## Formatting

Phandaal supports optional post-write formatting via pluggable formatters. Formatting runs after the write operation but before LOC counting, ensuring metrics reflect the actual formatted result.

### Why Format in Phandaal?

- **Accurate metrics**: LOC and threshold checks reflect formatted output
- **Consistency**: Ensures all phandaal-written code matches project style
- **Convenience**: Claude can write "close enough" code; formatting fixes it

### When NOT to Format in Phandaal

- Project uses pre-commit hooks for formatting
- Editor format-on-save handles it
- Minimal setup preferred (trust Claude's formatting)

### Formatter Configuration

Formatters are functions `(fn [path] ...)` that format the file at `path` in place. Configure per extension:

```clojure
(phandaal/registry
  {:project-root "/project"
   :source-paths ["src/clj"]
   :formatters {".clj"  (fn [path] (shell/sh "cljfmt" "fix" path))
                ".css"  (fn [path] (shell/sh "prettier" "--write" path))
                ".json" (fn [path] (shell/sh "prettier" "--write" path))}})
```

If no `:formatters` configured, no formatting occurs (default).

### What Goes in a Formatter Function

Anything you want. Phandaal just calls `(formatter path)` after writing. Examples:

```clojure
;; Shell command
(fn [path] (shell/sh "cljfmt" "fix" path))

;; Library call
(fn [path]
  (spit path (cljfmt.core/reformat-string (slurp path))))

;; External service
(fn [path]
  (http/post "https://formatter.example.com" {:body (slurp path)}))

;; Dispatch an effect (if you have dispatch in scope)
(fn [path]
  (dispatch [[::my-app/format-file path]]))
```

Phandaal doesn't care what happens inside—it just needs the file at `path` to be formatted when the function returns.

### Optional Shell Helper

Since shell commands are common, phandaal provides one small helper:

```clojure
(ns ascolais.phandaal.format
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn sh
  "Create formatter from shell command. Use {path} as placeholder."
  [command-template]
  (fn [path]
    (let [cmd (str/replace command-template "{path}" path)
          {:keys [exit err]} (shell/sh "sh" "-c" cmd)]
      (when-not (zero? exit)
        (throw (ex-info "Formatter failed" {:cmd cmd :exit exit :err err}))))))
```

Usage:
```clojure
{:formatters {".clj"  (phandaal.format/sh "cljfmt fix {path}")
              ".css"  (phandaal.format/sh "prettier --write {path}")}}
```

This is convenience, not required. Write your own functions if preferred.

### Formatting Flow

1. Effect writes content to file (atomic write)
2. Look up formatter for file extension
3. If formatter found, call `(formatter path)`
4. Re-read file for final content
5. Count lines on formatted content
6. Check thresholds against formatted LOC
7. Return result with `:formatted? true`

### Error Handling

If formatting fails:
- Log warning via `tap>`
- Return result with `:formatted? false` and `:format-error` details
- File contains unformatted content (write succeeded)
- LOC reflects unformatted state

This ensures writes never fail due to formatter issues—formatting is best-effort.

## Open Questions

- [x] Should `::phandaal/insert` support regex patterns for `:after`/`:before`? → Yes, useful for flexible matching
- [x] Should we support `:encoding` option? → No, default to UTF-8, keep it simple
- [ ] Should `::phandaal/write` have a `:backup?` option? → Maybe, could be useful for safety

## References

- [Sandestin effect system](https://github.com/ascolais/sandestin)
- [clj-reload](https://github.com/tonsky/clj-reload) - Namespace reloading
- [Tsain component management](../../../tsain) - Domain library pattern
