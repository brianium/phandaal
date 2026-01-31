# Phandaal

Sandestin effect library for source file modifications with metadata tracking.

Phandaal provides atomic file operations as [Sandestin](https://github.com/brianium/sandestin) effects, returning rich metadata (line counts, thresholds, namespace inference) that enables intelligent source modification workflows.

## Installation

Add to your `deps.edn`:

```clojure
{:deps {io.github.ascolais/phandaal {:git/tag "v0.1.0" :git/sha "..."}
        io.github.brianium/sandestin {:git/tag "v0.5.0" :git/sha "526d4c5"}}}
```

Phandaal requires Sandestin as a peer dependency.

## Quick Start

```clojure
(require '[ascolais.phandaal :as phandaal]
         '[ascolais.sandestin :as s])

;; Create a dispatch with phandaal effects
(def dispatch
  (s/create-dispatch
    [[phandaal/registry {:project-root "/path/to/project"
                         :source-paths ["src/clj"]}]]))

;; Write a file
(dispatch {} {} [[::phandaal/write {:path "/path/to/project/src/clj/app/core.clj"
                                    :content "(ns app.core)\n\n(defn hello [] \"world\")\n"}]])
;; => {:results [{:effect [...]
;;                :res {:path "/path/to/project/src/clj/app/core.clj"
;;                      :status :created
;;                      :loc {:before nil :after 3 :delta nil}
;;                      :hints []
;;                      :reload {:namespaces [app.core] :type :clj-reload}}}]
;;     :errors []}
```

## Effects

| Effect | Description |
|--------|-------------|
| `::phandaal/write` | Replace entire file contents |
| `::phandaal/append` | Append content to end of file |
| `::phandaal/insert` | Insert content at line number or pattern |
| `::phandaal/replace` | Find and replace within file |
| `::phandaal/read-meta` | Get file metadata without reading content |

### `::phandaal/write`

Replace entire file contents with atomic write (temp file + rename).

```clojure
[::phandaal/write {:path "/absolute/path/to/file.clj"
                   :content "(ns my.namespace)\n"
                   :create-dirs? true      ; optional, create parent dirs
                   :threshold 500}]        ; optional, line threshold
```

### `::phandaal/append`

Append content to end of file. Creates file if it doesn't exist.

```clojure
[::phandaal/append {:path "/path/to/file.clj"
                    :content "\n(defn new-fn [] ...)\n"
                    :threshold 500}]
```

### `::phandaal/insert`

Insert content at a specific location.

```clojure
;; Insert at line number (1-indexed)
[::phandaal/insert {:path "/path/to/file.clj"
                    :content "(require '[new.dep :as dep])"
                    :at {:line 2}}]

;; Insert after matching pattern
[::phandaal/insert {:path "/path/to/file.clj"
                    :content "(require '[new.dep :as dep])"
                    :at {:after "(ns my.namespace"}}]

;; Insert before matching pattern
[::phandaal/insert {:path "/path/to/file.clj"
                    :content ";; Documentation\n"
                    :at {:before "(defn main"}}]

;; Patterns can be regex
[::phandaal/insert {:path "/path/to/file.clj"
                    :content "new-line"
                    :at {:after #"defn \w+"}}]
```

### `::phandaal/replace`

Find and replace within file.

```clojure
;; Replace first occurrence
[::phandaal/replace {:path "/path/to/file.clj"
                     :find "old-name"
                     :replacement "new-name"}]

;; Replace all occurrences
[::phandaal/replace {:path "/path/to/file.clj"
                     :find "old-name"
                     :replacement "new-name"
                     :all? true}]

;; Regex patterns
[::phandaal/replace {:path "/path/to/file.clj"
                     :find #"v\d+\.\d+\.\d+"
                     :replacement "v2.0.0"
                     :all? true}]
```

### `::phandaal/read-meta`

Get file metadata without reading full content.

```clojure
[::phandaal/read-meta "/path/to/file.clj"]
;; => {:path "/path/to/file.clj"
;;     :exists? true
;;     :loc 245
;;     :modified #inst "2024-01-15T10:00:00"
;;     :namespace app.core}
```

## Result Shape

All file-modifying effects return a consistent result shape:

```clojure
{:path "/absolute/path/to/file.clj"
 :status :ok                          ; :ok | :created | :error
 :loc {:before 100                    ; nil if file was created
       :after 115
       :delta 15}
 :threshold {:limit 500               ; present only if threshold specified
             :exceeded? false
             :remaining 385}
 :hints []                            ; always empty; domain libraries populate
 :reload {:namespaces [app.core]      ; nil for non-Clojure files
          :type :clj-reload}
 :formatted? true}                    ; nil if no formatter, false on error
```

### Status Values

| Status | Meaning |
|--------|---------|
| `:ok` | File existed and was modified |
| `:created` | File did not exist and was created |
| `:error` | Operation failed (details in `:error` key) |

## Configuration

### Registry Options

```clojure
(phandaal/registry
  {:project-root "/path/to/project"      ; required - absolute path
   :source-paths ["src/clj" "src/cljc"]  ; optional, defaults to ["src"]
   :formatters {".clj" my-formatter}})   ; optional, map of extension to fn
```

### Formatters

Formatters are functions that take a file path and format the file in place:

```clojure
(require '[cljfmt.core :as cljfmt])

;; Using cljfmt as a library
(defn cljfmt-formatter [path]
  (spit path (cljfmt/reformat-string (slurp path))))

;; Using shell command via helper
(require '[ascolais.phandaal.format :as fmt])

(def cljfmt-sh (fmt/sh "cljfmt fix {path}"))

;; Configure in registry
(phandaal/registry
  {:project-root "/project"
   :formatters {".clj"  cljfmt-formatter
                ".cljc" cljfmt-formatter
                ".edn"  (fmt/sh "jet --pretty {path}")}})
```

Formatter behavior:
- Runs after write, before LOC counting
- Failures don't fail the write (graceful degradation)
- Result includes `:formatted? false` and `:format-error` on failure
- LOC in result reflects formatted content

## Namespace Inference

For Clojure files (`.clj`, `.cljc`, `.cljs`), phandaal infers the namespace from the file path:

```clojure
;; With source-paths ["src/clj"]
;; /project/src/clj/app/core.clj → app.core
;; /project/src/clj/my_app/utils.clj → my-app.utils
```

The inferred namespace appears in the result under `:reload`:

```clojure
{:reload {:namespaces [app.core]
          :type :clj-reload}}
```

## Threshold Detection

Phandaal detects when files exceed a line threshold but does not prescribe what to do about it. The `:hints` array is always empty—domain libraries add context-aware guidance.

```clojure
;; Check if threshold exceeded
(let [{:keys [results]} (dispatch {} {} [[::phandaal/write
                                          {:path path
                                           :content content
                                           :threshold 500}]])]
  (when (get-in results [0 :res :threshold :exceeded?])
    ;; Domain library adds actionable hints
    (println "File exceeds 500 lines, consider splitting")))
```

## Pending Reloads

Effects track which namespaces need reloading in dispatch-data. Access via placeholder:

```clojure
;; In an effect or action that needs the pending reloads
[::my-effect [::phandaal/pending-reloads]]
```

## Audit Logging

Phandaal provides a pluggable audit logging system to track all file modifications.

### Setup

```clojure
(require '[ascolais.phandaal :as phandaal]
         '[ascolais.phandaal.storage.file :as file-storage]
         '[ascolais.phandaal.interceptors :as phandaal-int]
         '[ascolais.sandestin :as s])

;; Create storage (flat-file EDN, zero dependencies)
(def audit-storage
  (file-storage/create {:path ".phandaal/audit.edn"}))

;; Add interceptor to dispatch
(def dispatch
  (s/create-dispatch
    {:registries [(phandaal/registry {:project-root "/project"})]
     ::s/interceptors [(phandaal-int/audit-log audit-storage
                         :content-limit 1000
                         :session-id "my-session")]}))
```

### Storage Backends

**Flat-file (default)** - Zero dependencies, human-readable, greppable:

```clojure
(require '[ascolais.phandaal.storage.file :as file-storage])
(def storage (file-storage/create {:path ".phandaal/audit.edn"}))
```

**SQLite** - Indexed queries for large logs (requires next.jdbc + sqlite-jdbc):

```clojure
(require '[ascolais.phandaal.storage.sqlite :as sqlite-storage])
(def storage (sqlite-storage/create {:path ".phandaal/audit.db"}))
```

Add to deps.edn for SQLite:
```clojure
{:deps {com.github.seancorfield/next.jdbc {:mvn/version "1.3.939"}
        org.xerial/sqlite-jdbc {:mvn/version "3.45.1.0"}}}
```

### Querying

```clojure
;; Recent activity (last 24 hours)
(phandaal/recent-activity storage)
(phandaal/recent-activity storage :hours 48 :limit 50)

;; Session activity
(phandaal/session-activity storage "my-session")

;; File history
(phandaal/file-history storage "/project/src/app/core.clj")

;; Entries with warnings/hints
(phandaal/warnings storage)
```

### Direct Protocol Access

```clojure
(require '[ascolais.phandaal.storage :as storage])

;; Query with filters
(storage/query storage
  {:since #inst "2024-01-15"
   :effect-key :ascolais.phandaal/write
   :file-path "/project/src/"
   :status :ok
   :limit 100})

;; Clear old entries
(storage/clear! storage {:before #inst "2024-01-01"})
(storage/clear! storage {:all true})
```

### Entry Shape

Each audit entry contains:

```clojure
{:id #uuid "..."
 :ts #inst "2024-01-15T10:23:00.000Z"
 :session-id "my-session"           ; optional
 :effect-key :ascolais.phandaal/write
 :effect-args {:path "..." :content "..."}
 :result {:status :ok :loc {...}}
 :file-path "/project/src/app/core.clj"
 :hints []
 :status :ok}
```

### Custom Storage Backend

Implement the `AuditStorage` protocol:

```clojure
(require '[ascolais.phandaal.storage :as storage])

(defrecord MyStorage [...]
  storage/AuditStorage
  (append! [this entry] ...)
  (query [this opts] ...)
  (clear! [this opts] ...))
```

## Error Handling

Effects throw exceptions for:
- Insert/replace on non-existent file
- Pattern not found in insert `:after`/`:before`

These appear in the dispatch result's `:errors` vector:

```clojure
(let [{:keys [errors]} (dispatch {} {} [[::phandaal/insert ...]])]
  (when (seq errors)
    (println "Error:" (:error (first errors)))))
```

## Development

### Prerequisites

- [Clojure CLI](https://clojure.org/guides/install_clojure) 1.11+

### Start the REPL

```bash
clj -M:dev
```

### Development Workflow

```clojure
(dev)      ; Switch to dev namespace
(start)    ; Start the system (opens Portal)
(reload)   ; Reload changed namespaces
(restart)  ; Full restart
```

### Testing

```bash
clj -X:test
```

## License

MIT
