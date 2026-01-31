# ascolais/phandaal

## Project Overview

Phandaal is a Sandestin effect library for source file modifications with metadata tracking. When working in this codebase, you're building the primitives that enable effect-driven source modifications.

This is a Clojure project using deps.edn for dependency management.

## Technology Stack

- **Clojure** with deps.edn
- **clj-reload** for namespace reloading during development
- **Portal** for data inspection (tap> integration)
- **Cognitect test-runner** for running tests

## Development Setup

### Starting the REPL

```bash
clj -M:dev
```

This starts a REPL with development dependencies loaded.

### Development Workflow

1. Start REPL with `clj -M:dev`
2. Load dev namespace: `(dev)`
3. Start the system: `(start)`
4. Make changes to source files
5. Reload: `(reload)`

The `dev` namespace provides:
- `(start)` - Start the development system
- `(stop)` - Stop the system
- `(reload)` - Reload changed namespaces via clj-reload
- `(restart)` - Stop, reload, and start

### Portal

Portal opens automatically when the dev namespace loads. Any `(tap> data)` calls will appear in the Portal UI.

## Project Structure

```
phandaal/
├── src/clj/ascolais/
│   ├── phandaal.clj           # Main API, registry factory
│   └── phandaal/
│       ├── effects.clj        # Effect handlers
│       ├── io.clj             # File I/O utilities
│       ├── ns.clj             # Namespace inference
│       ├── result.clj         # Result shape helpers
│       ├── reload.clj         # Reload executor presets
│       ├── storage.clj        # AuditStorage protocol
│       ├── storage/
│       │   ├── file.clj       # Flat-file driver
│       │   └── sqlite.clj     # SQLite driver
│       └── interceptors.clj   # Audit log interceptor
├── dev/src/clj/               # Development-only source (user.clj, dev.clj)
├── test/src/clj/              # Test files
├── specs/                     # Design specifications
└── resources/                 # Resource files
```

## Key Concepts

### Result Shape

All file effects return this consistent shape:

```clojure
{:path "/absolute/path"
 :status :ok              ;; :ok | :created | :error
 :loc {:before n :after m :delta d}
 :threshold {:limit n :exceeded? bool :remaining n}
 :hints [...]             ;; When thresholds exceeded
 :reload {:namespaces [sym...] :type :clj-reload}}
```

### Pending Reloads

File effects track affected namespaces in dispatch-data:

```clojure
{::phandaal/pending-reloads #{app.core app.routes}}
```

Access via placeholder: `[::phandaal/pending-reloads]`

### Threshold Hints

Callers provide `:threshold-hint` with their effect args. Phandaal detects when threshold is exceeded and includes the hint in results. This enables domain libraries to encode their own split/organization rules.

## Specifications

Read specs before implementing:

1. **[specs/core-primitives/](specs/core-primitives/)** - Priority 10, implement first
2. **[specs/audit-log/](specs/audit-log/)** - Priority 20, depends on core
3. **[specs/reload-tracking/](specs/reload-tracking/)** - Priority 30, extends core

Each spec has:
- `README.md` - Overview and decisions
- `research.md` - Problem analysis and options
- `implementation-plan.md` - Task breakdown

## REPL Evaluation

Use the clojure-eval skill to evaluate code via nREPL.

### Starting an nREPL Server

To start a REPL with nREPL support (required for clojure-eval):

```bash
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' -M:dev -m nrepl.cmdline --port 7888
```

This starts an nREPL server on port 7888 with all dev dependencies loaded.

### Connecting and Evaluating

```bash
clj-nrepl-eval --discover-ports          # Find running REPLs
clj-nrepl-eval -p 7888 "(+ 1 2 3)"       # Evaluate expression
```

**Important:** All REPL evaluation should take place in the `dev` namespace. After connecting, switch to the dev namespace:

```bash
clj-nrepl-eval -p 7888 "(dev)"
```

To reload code after making changes, use clj-reload:

```bash
clj-nrepl-eval -p 7888 "(reload)"
```

## Running Tests

```bash
clj -X:test
```

Or from the REPL (in the dev namespace):

```clojure
(reload)  ; Reload changed namespaces first
(require '[clojure.test :refer [run-tests]])
(run-tests 'ascolais.phandaal-test)
```

### Testing Patterns

Use Sandestin's test patterns:

```clojure
(deftest write-effect-test
  (let [dispatch (s/create-dispatch [(phandaal/registry {...})])]
    (testing "returns consistent result shape"
      (let [result (dispatch [[::phandaal/write {:path ... :content ...}]])]
        (is (= :ok (get-in result [:results 0 :status])))
        (is (contains? (get-in result [:results 0]) :loc))))))
```

## Adding Dependencies

When adding new dependencies in a REPL-connected environment:

1. **Add to the running REPL first** using `clojure.repl.deps/add-lib`:
   ```clojure
   (clojure.repl.deps/add-lib 'metosin/malli {:mvn/version "0.16.4"})
   ```
   Note: The library name must be quoted.

2. **Confirm the dependency works** by requiring and testing it in the REPL.

3. **Only then add to deps.edn** once confirmed working.

This ensures dependencies are immediately available without restarting the REPL.

## Implementation Notes

### Atomic Writes

File writes should be atomic (write to temp, rename):

```clojure
(defn atomic-spit [path content]
  (let [temp (File/createTempFile "phandaal" ".tmp" (.getParentFile (io/file path)))]
    (spit temp content)
    (.renameTo temp (io/file path))))
```

### Namespace Inference

Use configurable source-paths to infer namespace from file path:

```clojure
;; /project/src/clj/app/core.clj with source-paths ["src/clj"]
;; → app.core
```

### Storage Protocol

Keep it simple - three methods:

```clojure
(defprotocol AuditStorage
  (append! [this entry])
  (query [this opts])
  (clear! [this opts]))
```

## Code Style

- Follow standard Clojure conventions
- Use `cljfmt` formatting (applied automatically via hooks)
- Prefer pure functions where possible
- Use `tap>` for debugging output (appears in Portal)

### Namespaced Keywords

Clojure has two syntaxes for namespaced keywords:

**Single colon (`:`)** - Explicit namespace, works anywhere:
```clojure
:my.app.config/timeout    ; Fully qualified namespace
:ui/visible               ; Arbitrary namespace (doesn't need to exist)
:db/id                    ; Common convention for domain markers
```

**Double colon (`::`)** - Auto-resolved namespace:
```clojure
;; In namespace my.app.core:
::key                     ; Expands to :my.app.core/key

;; With required aliases:
(require '[my.app.db :as db])
::db/query                ; Expands to :my.app.db/query
```

**When to use which:**
- Use `:` with explicit namespace when the keyword meaning is independent of the current file
- Use `::` when the keyword is specific to the current namespace
- Use `::alias/key` to reference keywords from required namespaces without typing the full name
- Prefer `:` for spec keys, component IDs, and data that crosses namespace boundaries

## Git Commits

Use conventional commits format:

```
<type>: <description>

[optional body]
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

Examples:
- `feat: add user authentication`
- `fix: resolve nil pointer in data parser`
- `refactor: simplify database connection logic`
