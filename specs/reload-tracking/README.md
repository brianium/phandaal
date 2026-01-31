---
title: "Reload Tracking"
status: planned
date: 2025-01-30
priority: 30
---

# Reload Tracking

## Overview

Automatic tracking of which Clojure namespaces need reloading after file modifications, with a pluggable executor system for performing the actual reload. Phandaal always tracks pending reloads; executing them is optional and configurable based on the project's reload strategy (clj-reload, tools.namespace, manual, etc.).

## Goals

- Automatically track namespaces affected by file modifications
- Accumulate pending reloads across multiple effects in a dispatch
- Provide pluggable reload executors for different strategies
- Ship with presets for common reload tools (clj-reload, tools.namespace, require)
- Support "track only" mode for projects preferring manual reload
- Clear pending reloads after execution

## Non-Goals

- Implementing reload logic ourselves (delegate to existing tools)
- Handling ClojureScript reloads (different toolchain)
- Dependency ordering (leave to reload tool)
- Automatic reload on every effect (explicit trigger preferred)

## Key Decisions

See [research.md](research.md) for details.

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Tracking location | `::phandaal/pending-reloads` in dispatch-data | Accessible via placeholder, survives effect chain |
| Reload trigger | Explicit `::phandaal/reload` effect | User controls when reload happens |
| Executor config | Registry option `:reload-executor` | One-time setup, consistent behavior |
| Default behavior | Track only, no executor | Safe default, user opts into reload |

## Implementation Status

See [implementation-plan.md](implementation-plan.md) for detailed task breakdown.

- [ ] Phase 1: Pending reload tracking
- [ ] Phase 2: Executor protocol and presets
- [ ] Phase 3: Reload effect
- [ ] Phase 4: After-dispatch interceptor (optional)
- [ ] Phase 5: Testing and documentation
