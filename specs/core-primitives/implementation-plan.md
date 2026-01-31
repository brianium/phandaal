# Core Primitives - Implementation Plan

## Overview

Implement the foundational file operation effects for phandaal. These form the base layer that audit-log and reload-tracking build upon.

## Prerequisites

- [ ] Create phandaal project structure (deps.edn, src directories)
- [ ] Add sandestin as dependency
- [ ] Add malli for schema validation

## Phase 1: Project Setup and Result Shape

- [ ] Create `deps.edn` with sandestin, malli dependencies
- [ ] Create namespace structure:
  - `ascolais.phandaal` - Main namespace, registry factory
  - `ascolais.phandaal.io` - File I/O utilities (atomic write, LOC counting)
  - `ascolais.phandaal.ns` - Namespace inference from paths
  - `ascolais.phandaal.format` - Formatter helpers and protocol
- [ ] Define result shape specs in malli
- [ ] Implement `result-map` helper that constructs consistent results
- [ ] Implement `check-threshold` helper that detects threshold violations

## Phase 2: Core File Effects

### 2.1: Read-meta effect
- [ ] Implement `::phandaal/read-meta` effect
- [ ] File existence check
- [ ] Line count without full read (count newlines)
- [ ] Modification time
- [ ] Namespace inference for .clj/.cljc/.cljs files

### 2.2: Write effect
- [ ] Implement `::phandaal/write` effect
- [ ] Atomic write (temp file + rename)
- [ ] Create parent directories if `:create-dirs? true`
- [ ] LOC before/after tracking
- [ ] Threshold detection (populate `:threshold` in result)
- [ ] Namespace inference and reload tracking

### 2.3: Append effect
- [ ] Implement `::phandaal/append` effect
- [ ] Append to existing file
- [ ] Create file if doesn't exist
- [ ] LOC tracking
- [ ] Threshold checking

### 2.4: Insert effect
- [ ] Implement `::phandaal/insert` effect
- [ ] Support `:line` insertion (1-indexed)
- [ ] Support `:after` pattern matching
- [ ] Support `:before` pattern matching
- [ ] Handle regex patterns in `:after`/`:before`
- [ ] Error handling for pattern not found

### 2.5: Replace effect
- [ ] Implement `::phandaal/replace` effect
- [ ] String find/replace
- [ ] Regex find/replace
- [ ] Single vs all occurrences (`:all?` flag)
- [ ] Return count of replacements made

## Phase 3: Namespace Tracking

- [ ] Implement namespace inference from file paths
- [ ] Support configurable `:source-paths` in registry options
- [ ] Track pending reloads in dispatch-data under `::phandaal/pending-reloads`
- [ ] Implement `::phandaal/pending-reloads` placeholder for access
- [ ] Implement `::phandaal/clear-pending-reloads` effect (for after reload)

## Phase 4: Formatting

### 4.1: Formatter Helper

- [ ] Create `ascolais.phandaal.format` namespace
- [ ] Implement `sh` helper function:
  ```clojure
  (defn sh [command-template]
    "Create formatter from shell command. Use {path} as placeholder."
    (fn [path] ...))
  ```
  - Accept command template with `{path}` placeholder
  - Execute via `clojure.java.shell/sh`
  - Throw on non-zero exit

### 4.2: Integrate Formatting into Effects

- [ ] Add `:formatters` option to registry (map of extension string → fn)
- [ ] Create `run-formatter!` utility:
  - Look up formatter by file extension
  - Call `(formatter path)`
  - Re-read file after formatter runs
  - Handle errors gracefully (log, continue with unformatted)
- [ ] Update write effect flow:
  1. Atomic write content
  2. Run formatter if configured for extension
  3. Re-read file for final content
  4. Count LOC on formatted result
  5. Return with `:formatted?` key
- [ ] Update append effect similarly
- [ ] Update insert effect similarly
- [ ] Update replace effect similarly

### 4.3: Error Handling

- [ ] Formatter failures should not fail the write
- [ ] Log formatter errors via `tap>`
- [ ] Set `:formatted? false` and `:format-error` in result
- [ ] LOC reflects unformatted content on format failure

## Phase 5: Registry Factory

- [ ] Implement `(phandaal/registry opts)` factory function
- [ ] Accept options:
  - `:project-root` (required)
  - `:source-paths` (optional, defaults to `["src"]`)
  - `:default-threshold` (optional)
  - `:formatters` (optional, map of extension string → formatter)
  - `:reload-executor` (optional, for reload-tracking spec)
- [ ] Register all effects with schemas and descriptions
- [ ] Register placeholders

## Phase 6: Testing

- [ ] Unit tests for `phandaal.io` utilities
  - Atomic write success and failure cases
  - LOC counting accuracy
- [ ] Unit tests for `phandaal.ns` namespace inference
  - Various source path configurations
  - Edge cases (files outside source paths, non-Clojure files)
- [ ] Unit tests for `phandaal.format` utilities
  - `sh` helper success case
  - `sh` helper failure (non-zero exit)
  - `sh` helper with special characters in path
- [ ] Integration tests for each effect
  - Write/append/insert/replace happy paths
  - Threshold exceeded scenarios
  - Error handling (permission denied, disk full simulation)
- [ ] Integration tests with formatting
  - Effect with formatter configured
  - Effect without formatter (no-op)
  - Formatter failure doesn't fail write
  - LOC reflects formatted output
- [ ] Test result shape consistency across all effects

## Phase 7: Documentation

- [ ] Docstrings for all public functions
- [ ] README.md with:
  - Quick start example
  - Effect reference table
  - Result shape documentation
  - Configuration options
- [ ] CLAUDE.md with usage instructions for Claude

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
