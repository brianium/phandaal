---
title: "Core Primitives"
status: in-progress
date: 2025-01-30
priority: 10
---

# Core Primitives

## Overview

Phandaal's foundational layer: sandestin effects for source file modification with metadata tracking. These primitives provide consistent file I/O operations that return rich metadata (line counts, thresholds, hints) enabling higher-level domain libraries to build intelligent source modification workflows.

## Goals

- Provide atomic file operations (write, append, insert, replace) as sandestin effects
- Return consistent metadata shape from all operations (LOC before/after, status, threshold detection)
- Detect threshold violations so domain libraries can add actionable hints
- Track which Clojure namespaces need reloading after modifications
- Support optional post-write formatting with pluggable formatters
- Enable domain libraries (tsain, route managers, etc.) to compose on these primitives

## Non-Goals

- Performing reloads (that's the reload-tracking spec's optional executor)
- Storing audit logs (that's the audit-log spec)
- Domain-specific semantics (CSS rules, route handlers, etc. belong in domain libraries)
- Providing actionable hints (phandaal detects thresholds; domain libraries add guidance)
- Version control operations (git add, commit, etc.)

## Key Decisions

See [research.md](research.md) for details.

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Result shape | Consistent map with `:loc`, `:status`, `:threshold`, `:reload`, `:formatted?` | Enables uniform handling across all primitives |
| Threshold handling | Detection only; `:hints` always empty | Domain libraries add actionable, context-aware hints |
| Path handling | Absolute paths required | Avoids ambiguity, explicit is better |
| Namespace inference | Based on file path + configurable source paths | Follows Clojure conventions |
| Formatting | Pluggable via `:formatters` config, optional | No hard deps; projects choose their tools |

## Implementation Status

See [implementation-plan.md](implementation-plan.md) for detailed task breakdown.

- [x] Phase 1: Project setup and result shape
- [x] Phase 2: Core file effects
- [x] Phase 3: Namespace tracking
- [x] Phase 4: Formatting
- [x] Phase 5: Registry factory
- [x] Phase 6: Testing (core tests complete, formatter tests remaining)
- [ ] Phase 7: Documentation (README.md for library)
