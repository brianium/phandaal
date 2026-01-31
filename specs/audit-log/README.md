---
title: "Audit Log"
status: planned
date: 2025-01-30
priority: 20
---

# Audit Log

## Overview

A pluggable audit logging system for phandaal operations. Provides an interceptor that records all file modifications with timestamps, effect details, and results. Storage is abstracted behind a protocol, shipping with flat-file and SQLite drivers, with easy extension for PostgreSQL or custom backends.

## Goals

- Provide append-only audit trail of all phandaal operations
- Abstract storage behind a protocol for pluggable backends
- Ship with flat-file (EDN) and SQLite drivers out of the box
- Support querying logs by time range, effect type, session
- Enable cross-session context (new Claude session reads previous activity)
- Make it trivial to add custom storage backends

## Non-Goals

- Real-time log streaming (batch queries are sufficient)
- Log rotation or archival (leave to storage backend or external tools)
- Undo/rollback functionality (audit log is read-only record)
- Storing full file contents (just metadata and effect args)

## Key Decisions

See [research.md](research.md) for details.

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Storage abstraction | Protocol with simple 3-method interface | Easy to implement custom backends |
| Entry shape | Flat map with extracted hints | Queryable, not deeply nested |
| Session tracking | Optional `:session-id` in dispatch-data | Enables grouping without requiring it |
| Default storage | Flat EDN file | Zero dependencies, human-readable, greppable |

## Implementation Status

See [implementation-plan.md](implementation-plan.md) for detailed task breakdown.

- [ ] Phase 1: Storage protocol and entry shape
- [ ] Phase 2: Flat-file driver
- [ ] Phase 3: SQLite driver
- [ ] Phase 4: Interceptor factory
- [ ] Phase 5: Query API
- [ ] Phase 6: Testing and documentation
