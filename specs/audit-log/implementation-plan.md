# Audit Log - Implementation Plan

## Overview

Implement the audit logging system with pluggable storage backends. This builds on core-primitives and provides the persistent record of all phandaal operations.

## Prerequisites

- [x] Core primitives spec implemented (or at least result shape defined)
- [x] Sandestin interceptor infrastructure understood

## Phase 1: Storage Protocol and Entry Shape

- [x] Define `AuditStorage` protocol in `ascolais.phandaal.storage`
- [x] Define entry schema in malli:
  ```clojure
  [:map
   [:id :uuid]
   [:ts inst?]
   [:session-id {:optional true} :string]
   [:effect-key :keyword]
   [:effect-args :map]
   [:result :map]
   [:file-path {:optional true} :string]
   [:hints [:vector :map]]
   [:status :keyword]]
  ```
- [x] Implement `build-entry` helper that extracts/denormalizes fields
- [x] Implement content truncation utility

## Phase 2: Flat-File Driver

- [x] Create `ascolais.phandaal.storage.file` namespace
- [x] Implement `create` factory function
- [x] Implement `append!`:
  - Open file in append mode
  - Write EDN + newline
  - Flush/fsync
  - Close
- [x] Implement `query`:
  - Read all lines
  - Parse each as EDN
  - Apply filters (since, until, session-id, effect-key, file-path, status)
  - Sort by ts descending
  - Apply limit
- [x] Implement `clear!`:
  - For `:all true`: truncate file
  - For `:before inst`: read, filter, rewrite
- [x] Handle file creation if doesn't exist
- [x] Handle concurrent access (file locking or accept eventual consistency)

## Phase 3: SQLite Driver

- [x] Add next.jdbc and SQLite dependencies (optional, loaded dynamically?)
- [x] Create `ascolais.phandaal.storage.sqlite` namespace
- [x] Implement `create` factory:
  - Create/open database
  - Run schema migrations
  - Return storage instance
- [x] Define schema:
  ```sql
  CREATE TABLE IF NOT EXISTS audit_log (
    id TEXT PRIMARY KEY,
    ts TEXT NOT NULL,
    session_id TEXT,
    effect_key TEXT NOT NULL,
    effect_args TEXT,
    result TEXT,
    file_path TEXT,
    hints TEXT,
    status TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
  );
  ```
- [x] Create indexes for common query patterns
- [x] Implement `append!` with prepared statement
- [x] Implement `query` with dynamic SQL building
- [x] Implement `clear!` with DELETE statements
- [x] Connection pooling (single connection sufficient for most use cases)

## Phase 4: Interceptor Factory

- [x] Create `ascolais.phandaal.interceptors` namespace
- [x] Implement `audit-log` interceptor factory:
  ```clojure
  (defn audit-log [storage & opts])
  ```
- [x] Implement effect detection (is this a phandaal effect?)
- [x] Implement `after-effect` handler:
  - Check if effect is phandaal operation
  - Check effect-filter predicate
  - Build entry from context
  - Append to storage
- [x] Support options:
  - `:effect-filter` - predicate for selective logging
  - `:content-limit` - truncation threshold
  - [ ] `:async?` - write in background (future enhancement)

## Phase 5: Query API

- [x] Create convenience functions in main namespace:
  ```clojure
  (phandaal/recent-activity storage)        ;; Last 24 hours
  (phandaal/session-activity storage id)    ;; Specific session
  (phandaal/file-history storage path)      ;; Changes to file
  (phandaal/warnings storage)               ;; Entries with hints
  ```
- [ ] Implement pretty-print for REPL inspection
- [x] Support EDN output for programmatic use

## Phase 6: Testing

### Unit Tests
- [x] Entry building and truncation
- [x] File driver append/query/clear
- [ ] SQLite driver append/query/clear
- [x] Query filtering logic

### Integration Tests
- [ ] Interceptor integration with sandestin dispatch
- [ ] Full flow: dispatch effect → interceptor logs → query shows entry
- [ ] Multiple storage backends with same test suite
- [ ] Concurrent access behavior

### Edge Cases
- [x] Empty log queries
- [x] Very large entries (content truncation)
- [x] Malformed existing log files (graceful handling)
- [x] Missing log file (auto-create)

## Phase 7: Documentation

- [x] Docstrings for protocol and all public functions
- [ ] README section on audit logging:
  - Setup with flat-file (default)
  - Upgrade to SQLite
  - Custom storage backend guide
- [ ] Example queries for common scenarios
- [ ] CLAUDE.md section on using audit log for context

## File Structure

```
phandaal/
├── src/clj/ascolais/phandaal/
│   ├── storage.clj            # Protocol definition
│   ├── storage/
│   │   ├── file.clj           # Flat-file driver
│   │   └── sqlite.clj         # SQLite driver
│   └── interceptors.clj       # Audit log interceptor
```

## Dependencies

```clojure
;; Required
org.clojure/clojure
ascolais/sandestin
metosin/malli

;; Optional (for SQLite driver)
com.github.seancorfield/next.jdbc
org.xerial/sqlite-jdbc
```

## Rollout Plan

1. Implement flat-file driver first (zero dependencies)
2. Test interceptor integration
3. Add SQLite driver
4. Document upgrade path
5. Release with core-primitives

## Integration with Core Primitives

The interceptor automatically logs all phandaal effects:

```clojure
;; User dispatches
(dispatch [[::phandaal/append {:path "..." :content "..."}]])

;; Interceptor sees after-effect context:
;; {:effect [::phandaal/append {...}]
;;  :result {:path "..." :loc {...} :status :ok}}

;; Builds and appends entry to storage
```

No changes needed to core primitives - the interceptor observes and records.
