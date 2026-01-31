# Audit Log - Research

## Problem Statement

When Claude makes source modifications through effects, there's no persistent record of what happened. Each session starts fresh with no knowledge of previous activity. Users can't easily review "what did Claude change?" and debugging issues requires reconstructing history from git commits (which may batch multiple changes).

We need an append-only audit log that:
- Records every phandaal operation with full context
- Persists across Claude sessions
- Is queryable for recent activity, specific files, or effect types
- Works with various storage backends depending on project needs

## Requirements

### Functional Requirements

1. **Append entries**: Record effect invocations with args and results
2. **Query entries**: Filter by time range, effect type, session, file path
3. **Clear entries**: Remove old entries (by time or all)
4. **Session grouping**: Optional session ID to group related operations
5. **Storage backends**: Flat-file and SQLite shipped, protocol for custom

### Non-Functional Requirements

- **Performance**: Appending should be fast (async write acceptable)
- **Durability**: Entries should survive process crashes (fsync on write)
- **Simplicity**: Zero-config default (flat file in project)
- **Queryability**: SQLite driver enables rich queries

## Entry Shape

Each audit log entry contains:

```clojure
{:id #uuid "..."              ;; Unique entry ID
 :ts #inst "2024-01-15T10:23:00.000Z"
 :session-id "claude-abc123"  ;; Optional, from dispatch-data

 ;; The effect that was dispatched
 :effect-key ::phandaal/write    ;; Keyword, for indexing
 :effect-args {:path "/project/src/app/core.clj"
               :content "..."  ;; Truncated if over limit
               :threshold 500}

 ;; Result from the effect
 :result {:path "/project/src/app/core.clj"
          :status :ok
          :loc {:before 245 :after 278 :delta 33}
          :threshold {:limit 500 :exceeded? false :remaining 222}}

 ;; Extracted for easy querying
 :file-path "/project/src/app/core.clj"
 :hints [{:type :threshold-exceeded ...}]  ;; Empty if none
 :status :ok}
```

### Content Truncation

The `:content` field in effect args can be large. Storage drivers may truncate:
- Flat-file: Store first 1000 chars + "... (truncated)"
- SQLite: Store full content in separate column, truncated in main

### Extracted Fields

These fields are denormalized for query efficiency:
- `:effect-key` - Enables "show all writes" queries
- `:file-path` - Enables "show changes to this file" queries
- `:status` - Enables "show failures" queries
- `:hints` - Enables "show threshold warnings" queries

## Storage Protocol

```clojure
(defprotocol AuditStorage
  (append! [this entry]
    "Append an entry to the log. Returns entry with :id if generated.")

  (query [this opts]
    "Query entries. Options:
     :since     - #inst, entries after this time
     :until     - #inst, entries before this time
     :limit     - int, max entries to return
     :session-id - string, filter by session
     :effect-key - keyword, filter by effect type
     :file-path  - string, filter by file (prefix match)
     :status     - keyword, filter by result status
     Returns vector of entries, newest first.")

  (clear! [this opts]
    "Clear entries. Options:
     :before - #inst, clear entries before this time
     :all    - boolean, clear everything
     Returns count of entries cleared."))
```

## Options Considered

### Option A: Single File Format (EDN)

**Description:** Append-only EDN file, one entry per line.

**Pros:**
- Zero dependencies
- Human-readable, greppable
- Works everywhere

**Cons:**
- Query requires reading entire file
- No indexing
- Can grow large

### Option B: SQLite

**Description:** SQLite database with indexed columns.

**Pros:**
- Rich queries with indexes
- Handles large logs efficiently
- Still single-file, portable

**Cons:**
- Requires SQLite dependency
- Binary format, not greppable
- Slightly more setup

### Option C: Both (Recommended)

**Description:** Ship both drivers, let user choose. Default to flat-file for simplicity.

**Pros:**
- Zero-config default works everywhere
- Upgrade path to SQLite when needed
- Protocol allows custom backends

**Cons:**
- More code to maintain
- User must choose (but default handles most cases)

## Recommendation

**Option C: Ship both drivers**. Default to flat-file for zero-config experience. Projects that need rich queries can switch to SQLite. The protocol makes it easy to add PostgreSQL or cloud backends.

## Driver Specifications

### Flat-File Driver

```clojure
(require '[ascolais.phandaal.storage.file :as file-storage])

(file-storage/create {:path ".phandaal/audit.edn"})
```

- One EDN map per line (for easy appending)
- `append!`: Opens file, appends, closes (fsync)
- `query`: Reads file, parses lines, filters in memory
- `clear!`: Rewrites file excluding cleared entries

### SQLite Driver

```clojure
(require '[ascolais.phandaal.storage.sqlite :as sqlite-storage])

(sqlite-storage/create {:path ".phandaal/audit.db"})
```

Schema:
```sql
CREATE TABLE audit_log (
  id TEXT PRIMARY KEY,
  ts TEXT NOT NULL,
  session_id TEXT,
  effect_key TEXT NOT NULL,
  effect_args TEXT,  -- EDN string
  result TEXT,       -- EDN string
  file_path TEXT,
  hints TEXT,        -- EDN string
  status TEXT
);

CREATE INDEX idx_ts ON audit_log(ts);
CREATE INDEX idx_session ON audit_log(session_id);
CREATE INDEX idx_effect ON audit_log(effect_key);
CREATE INDEX idx_file ON audit_log(file_path);
CREATE INDEX idx_status ON audit_log(status);
```

## Interceptor Design

The audit log interceptor hooks into `after-effect`:

```clojure
(defn audit-log-interceptor
  "Create interceptor that logs phandaal effects to storage."
  [storage & {:keys [effect-filter content-limit]
              :or {effect-filter (constantly true)
                   content-limit 1000}}]
  {:id ::audit-log
   :after-effect
   (fn [ctx]
     (let [{:keys [effect result]} ctx
           [effect-key & _] effect]
       (when (and (phandaal-effect? effect-key)
                  (effect-filter effect))
         (append! storage (build-entry ctx content-limit))))
     ctx)})
```

Options:
- `:effect-filter` - Predicate to skip logging certain effects
- `:content-limit` - Max chars for content fields

## Integration Pattern

```clojure
(require '[ascolais.phandaal :as phandaal]
         '[ascolais.phandaal.storage.file :as file-storage]
         '[ascolais.phandaal.interceptors :as phandaal-int])

(def audit-storage
  (file-storage/create {:path ".phandaal/audit.edn"}))

(def dispatch
  (s/create-dispatch
    {:registries [(phandaal/registry {:project-root "/project"})]
     ::s/interceptors [(phandaal-int/audit-log audit-storage)]}))
```

## Open Questions

- [x] Should we log non-phandaal effects? → No, focus on phandaal for now. Apps can add their own interceptors.
- [x] Should content be stored or just hashed? → Store truncated, full content is in the file anyway
- [ ] Should there be a max log size with automatic rotation? → Maybe, but keep simple for v1

## References

- [Sandestin interceptors](https://github.com/ascolais/sandestin)
- [SQLite in Clojure](https://github.com/seancorfield/next-jdbc)
- [Datomic-style append-only logs](https://docs.datomic.com/)
