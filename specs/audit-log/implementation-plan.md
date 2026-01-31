# Audit Log - Implementation Plan

## Overview

Implement the audit logging system with pluggable storage backends. This builds on core-primitives and provides the persistent record of all phandaal operations.

## Prerequisites

- [ ] Core primitives spec implemented (or at least result shape defined)
- [ ] Sandestin interceptor infrastructure understood

## Phase 1: Storage Protocol and Entry Shape

- [ ] Define `AuditStorage` protocol in `ascolais.phandaal.storage`
- [ ] Define entry schema in malli:
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
- [ ] Implement `build-entry` helper that extracts/denormalizes fields
- [ ] Implement content truncation utility

## Phase 2: Flat-File Driver

- [ ] Create `ascolais.phandaal.storage.file` namespace
- [ ] Implement `create` factory function
- [ ] Implement `append!`:
  - Open file in append mode
  - Write EDN + newline
  - Flush/fsync
  - Close
- [ ] Implement `query`:
  - Read all lines
  - Parse each as EDN
  - Apply filters (since, until, session-id, effect-key, file-path, status)
  - Sort by ts descending
  - Apply limit
- [ ] Implement `clear!`:
  - For `:all true`: truncate file
  - For `:before inst`: read, filter, rewrite
- [ ] Handle file creation if doesn't exist
- [ ] Handle concurrent access (file locking or accept eventual consistency)

## Phase 3: SQLite Driver

- [ ] Add next.jdbc and SQLite dependencies (optional, loaded dynamically?)
- [ ] Create `ascolais.phandaal.storage.sqlite` namespace
- [ ] Implement `create` factory:
  - Create/open database
  - Run schema migrations
  - Return storage instance
- [ ] Define schema:
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
- [ ] Create indexes for common query patterns
- [ ] Implement `append!` with prepared statement
- [ ] Implement `query` with dynamic SQL building
- [ ] Implement `clear!` with DELETE statements
- [ ] Connection pooling (single connection sufficient for most use cases)

## Phase 4: Interceptor Factory

- [ ] Create `ascolais.phandaal.interceptors` namespace
- [ ] Implement `audit-log` interceptor factory:
  ```clojure
  (defn audit-log [storage & opts])
  ```
- [ ] Implement effect detection (is this a phandaal effect?)
- [ ] Implement `after-effect` handler:
  - Check if effect is phandaal operation
  - Check effect-filter predicate
  - Build entry from context
  - Append to storage
- [ ] Support options:
  - `:effect-filter` - predicate for selective logging
  - `:content-limit` - truncation threshold
  - `:async?` - write in background (future enhancement)

## Phase 5: Query API

- [ ] Create convenience functions in main namespace:
  ```clojure
  (phandaal/recent-activity storage)        ;; Last 24 hours
  (phandaal/session-activity storage id)    ;; Specific session
  (phandaal/file-history storage path)      ;; Changes to file
  (phandaal/warnings storage)               ;; Entries with hints
  ```
- [ ] Implement pretty-print for REPL inspection
- [ ] Support EDN output for programmatic use

## Phase 6: Testing

### Unit Tests
- [ ] Entry building and truncation
- [ ] File driver append/query/clear
- [ ] SQLite driver append/query/clear
- [ ] Query filtering logic

### Integration Tests
- [ ] Interceptor integration with sandestin dispatch
- [ ] Full flow: dispatch effect → interceptor logs → query shows entry
- [ ] Multiple storage backends with same test suite
- [ ] Concurrent access behavior

### Edge Cases
- [ ] Empty log queries
- [ ] Very large entries (content truncation)
- [ ] Malformed existing log files (graceful handling)
- [ ] Missing log file (auto-create)

## Phase 7: Documentation

- [ ] Docstrings for protocol and all public functions
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
