# Core Primitives - Implementation Plan

## Overview

Implement the foundational file operation effects for phandaal. These form the base layer that audit-log and reload-tracking build upon.

## Prerequisites

- [x] Create phandaal project structure (deps.edn, src directories)
- [x] Add sandestin as peer dependency (in :dev alias)
- [x] Malli available via sandestin (v0.16.4)

## Phase 1: Project Setup and Result Shape

- [x] deps.edn configured with sandestin as peer dep
- [x] Create namespace structure:
  - `ascolais.phandaal` - Main namespace, registry factory
  - `ascolais.phandaal.io` - File I/O utilities (atomic write, LOC counting)
  - `ascolais.phandaal.ns` - Namespace inference from paths
  - `ascolais.phandaal.result` - Result shape helpers
  - `ascolais.phandaal.format` - Formatter helpers
  - `ascolais.phandaal.effects` - Effect handler factories
- [x] Define result shape specs in malli (inline in registry)
- [x] Implement `result-map` helper that constructs consistent results
- [x] Implement `check-threshold` helper that detects threshold violations

## Phase 2: Core File Effects

### 2.1: Read-meta effect
- [x] Implement `::phandaal/read-meta` effect
- [x] File existence check
- [x] Line count without full read (count newlines)
- [x] Modification time
- [x] Namespace inference for .clj/.cljc/.cljs files

### 2.2: Write effect
- [x] Implement `::phandaal/write` effect
- [x] Atomic write (temp file + rename)
- [x] Create parent directories if `:create-dirs? true`
- [x] LOC before/after tracking
- [x] Threshold detection (populate `:threshold` in result)
- [x] Namespace inference and reload tracking

### 2.3: Append effect
- [x] Implement `::phandaal/append` effect
- [x] Append to existing file
- [x] Create file if doesn't exist
- [x] LOC tracking
- [x] Threshold checking

### 2.4: Insert effect
- [x] Implement `::phandaal/insert` effect
- [x] Support `:line` insertion (1-indexed)
- [x] Support `:after` pattern matching
- [x] Support `:before` pattern matching
- [x] Handle regex patterns in `:after`/`:before`
- [x] Error handling for pattern not found

### 2.5: Replace effect
- [x] Implement `::phandaal/replace` effect
- [x] String find/replace
- [x] Regex find/replace
- [x] Single vs all occurrences (`:all?` flag)
- [ ] Return count of replacements made

## Phase 3: Namespace Tracking

- [x] Implement namespace inference from file paths
- [x] Support configurable `:source-paths` in registry options
- [x] Track pending reloads in dispatch-data under `::phandaal/pending-reloads`
- [x] Implement `::phandaal/pending-reloads` placeholder for access
- [ ] Implement `::phandaal/clear-pending-reloads` effect (for after reload)

## Phase 4: Formatting

### 4.1: Formatter Helper

- [x] Create `ascolais.phandaal.format` namespace
- [x] Implement `sh` helper function:
  ```clojure
  (defn sh [command-template]
    "Create formatter from shell command. Use {path} as placeholder."
    (fn [path] ...))
  ```
  - Accept command template with `{path}` placeholder
  - Execute via `clojure.java.shell/sh`
  - Throw on non-zero exit

### 4.2: Integrate Formatting into Effects

- [x] Add `:formatters` option to registry (map of extension string → fn)
- [x] Create `run-formatter!` utility:
  - Look up formatter by file extension
  - Call `(formatter path)`
  - Re-read file after formatter runs
  - Handle errors gracefully (log, continue with unformatted)
- [x] Update write effect flow:
  1. Atomic write content
  2. Run formatter if configured for extension
  3. Re-read file for final content
  4. Count LOC on formatted result
  5. Return with `:formatted?` key
- [x] Update append effect similarly
- [x] Update insert effect similarly
- [x] Update replace effect similarly

### 4.3: Error Handling

- [x] Formatter failures should not fail the write
- [x] Log formatter errors via `tap>`
- [x] Set `:formatted? false` and `:format-error` in result
- [x] LOC reflects unformatted content on format failure

## Phase 5: Registry Factory

- [x] Implement `(phandaal/registry opts)` factory function
- [x] Accept options:
  - `:project-root` (required)
  - `:source-paths` (optional, defaults to `["src"]`)
  - `:default-threshold` (optional)
  - `:formatters` (optional, map of extension string → formatter)
  - `:reload-executor` (optional, for reload-tracking spec)
- [x] Register all effects with schemas and descriptions
- [x] Register placeholders

## Phase 6: Testing

- [x] Unit tests for `phandaal.io` utilities
  - Atomic write success and failure cases
  - LOC counting accuracy
- [x] Unit tests for `phandaal.ns` namespace inference
  - Various source path configurations
  - Edge cases (files outside source paths, non-Clojure files)
- [ ] Unit tests for `phandaal.format` utilities
  - `sh` helper success case
  - `sh` helper failure (non-zero exit)
  - `sh` helper with special characters in path
- [x] Integration tests for each effect
  - Write/append/insert/replace happy paths
  - Threshold exceeded scenarios
  - Error handling (permission denied, disk full simulation)
- [ ] Integration tests with formatting
  - Effect with formatter configured
  - Effect without formatter (no-op)
  - Formatter failure doesn't fail write
  - LOC reflects formatted output
- [x] Test result shape consistency across all effects

## Phase 7: Documentation

- [x] Docstrings for all public functions
- [ ] README.md with:
  - Quick start example
  - Effect reference table
  - Result shape documentation
  - Configuration options
- [x] CLAUDE.md with usage instructions for Claude (in project root)

## Rollout Plan

1. Implement and test core-primitives in isolation
2. Create example usage in a test project
3. Integrate with sandestin test suite patterns
4. Release as independent library

## Dependencies on Other Specs

- **audit-log**: Will use these primitives, adds interceptor for logging
- **reload-tracking**: Will extend registry with reload executor option

## File Structure

```
phandaal/
├── deps.edn
├── src/clj/ascolais/
│   ├── phandaal.clj              # Registry factory, main API
│   └── phandaal/
│       ├── effects.clj        # Effect handlers
│       ├── io.clj             # File I/O utilities
│       ├── ns.clj             # Namespace inference
│       ├── format.clj         # Formatter helpers (cljfmt, shell)
│       └── result.clj         # Result shape helpers
├── test/src/clj/ascolais/
│   ├── phandaal_test.clj
│   └── phandaal/
│       ├── io_test.clj
│       ├── ns_test.clj
│       └── format_test.clj
├── README.md
└── CLAUDE.md
```
