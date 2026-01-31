# Reload Tracking - Implementation Plan

## Overview

Implement automatic namespace tracking and pluggable reload execution. This extends core-primitives with reload awareness and provides the `::phandaal/reload` effect.

## Prerequisites

- [ ] Core primitives implemented with namespace inference
- [ ] Understanding of how dispatch-data flows through effects

## Phase 1: Pending Reload Tracking

- [ ] Modify core effect handlers to track reloads in context:
  ```clojure
  ;; After successful file modification
  (when-let [ns-sym (path->namespace path source-paths)]
    (update ctx :dispatch-data update ::phandaal/pending-reloads
            (fnil conj #{}) ns-sym))
  ```
- [ ] Implement `::phandaal/pending-reloads` placeholder:
  ```clojure
  {::s/placeholders
   {::pending-reloads
    {::s/description "Set of namespaces pending reload"
     ::s/handler (fn [dispatch-data]
                   (get dispatch-data ::pending-reloads #{}))}}}
  ```
- [ ] Ensure tracking survives through effect chains
- [ ] Test: multiple file effects accumulate correctly

## Phase 2: Executor Protocol and Presets

- [ ] Create `ascolais.phandaal.reload` namespace
- [ ] Define executor shape (map with `:type` and `:reload-fn`)
- [ ] Implement `clj-reload` preset:
  - Dynamic require of clj-reload.core
  - Call reload with `:only` option
  - Map result to standard shape
- [ ] Implement `tools-namespace` preset:
  - Dynamic require of tools.namespace.repl
  - Call refresh
  - Map result to standard shape
- [ ] Implement `require-reload` preset:
  - Iterate namespaces
  - Call `(require ns :reload)` for each
  - Collect successes/failures
- [ ] Implement `noop` preset:
  - Return all namespaces as skipped
  - Useful for testing or manual control

## Phase 3: Reload Effect

- [ ] Add `:reload-executor` to registry options
- [ ] Store executor in system map during registry creation
- [ ] Implement `::phandaal/reload` effect:
  ```clojure
  {::s/effects
   {::reload
    {::s/description "Reload pending namespaces using configured executor"
     ::s/schema [:tuple [:= ::reload]
                 [:map [:only {:optional true} [:set :symbol]]]]
     ::s/handler reload-handler}}}
  ```
- [ ] Handler implementation:
  - Get pending from dispatch-data
  - Filter by `:only` if specified
  - Get executor from system
  - Return error if no executor configured
  - Call executor's reload-fn
  - Remove reloaded from pending set
  - Return result with remaining pending
- [ ] Implement `::phandaal/clear-pending` effect:
  - Remove specified namespaces from pending (or all)
  - For "I'll handle this myself" use case

## Phase 4: After-Dispatch Summary (Optional)

- [ ] Create optional interceptor that summarizes reload status:
  ```clojure
  {:id ::reload-summary
   :after-dispatch
   (fn [ctx]
     (let [pending (get-in ctx [:dispatch-data ::pending-reloads])]
       (when (seq pending)
         (tap> {:phandaal/pending-reloads pending})))
     ctx)}
  ```
- [ ] Consider: should this be opt-in or default?
- [ ] Useful for REPL feedback without explicit check

## Phase 5: Registry Integration

- [ ] Update `phandaal/registry` to accept `:reload-executor` option
- [ ] Store executor in returned system-schema or closure
- [ ] Document configuration in docstring
- [ ] Example:
  ```clojure
  (phandaal/registry
    {:project-root "/project"
     :source-paths ["src/clj"]
     :reload-executor phandaal.reload/clj-reload})
  ```

## Phase 6: Testing

### Unit Tests
- [ ] Namespace inference from various paths
- [ ] Pending set accumulation
- [ ] Each executor preset (mock the underlying libs)
- [ ] Reload effect with/without executor
- [ ] Clear pending effect

### Integration Tests
- [ ] Full flow: file effect → pending tracked → reload → pending cleared
- [ ] Multiple executors with same test patterns
- [ ] Error handling: executor not configured
- [ ] Error handling: reload failure (bad namespace)

### Manual Testing
- [ ] Test with actual clj-reload in dev REPL
- [ ] Test with tools.namespace
- [ ] Verify state preservation with clj-reload

## Phase 7: Documentation

- [ ] Docstrings for all reload-related functions
- [ ] README section on reload tracking:
  - How tracking works
  - Choosing an executor
  - Manual reload workflow
- [ ] Executor comparison table:
  | Executor | Best For | Handles State | Dependency Order |
  |----------|----------|---------------|------------------|
  | clj-reload | Stateful systems | Yes | Yes |
  | tools.namespace | Simple projects | No | Yes |
  | require-reload | Quick iterations | No | No |
  | noop | Manual control | N/A | N/A |

## File Structure

```
phandaal/
├── src/clj/ascolais/phandaal/
│   ├── reload.clj             # Executor presets
│   └── effects.clj            # Add ::reload, ::clear-pending effects
```

## Configuration Examples

### With clj-reload (Recommended)

```clojure
(require '[ascolais.phandaal :as phandaal]
         '[ascolais.phandaal.reload :as reload])

(phandaal/registry
  {:project-root (System/getProperty "user.dir")
   :source-paths ["src/clj" "dev/src/clj"]
   :reload-executor reload/clj-reload})
```

### Track Only (No Automatic Reload)

```clojure
(phandaal/registry
  {:project-root (System/getProperty "user.dir")})
;; No :reload-executor - ::phandaal/reload returns error

;; Check pending manually
(get-in result [:dispatch-data ::phandaal/pending-reloads])
;; User runs (dev/reload) themselves
```

### Custom Executor

```clojure
(phandaal/registry
  {:project-root (System/getProperty "user.dir")
   :reload-executor
   {:type :custom
    :reload-fn (fn [namespaces]
                 ;; Custom reload logic
                 (my-reload-system namespaces)
                 {:reloaded (set namespaces)
                  :failed {}
                  :skipped #{}})}})
```

## Rollout Plan

1. Implement tracking in core primitives first
2. Add reload effect with noop as only preset
3. Add clj-reload preset (most common use case)
4. Add other presets
5. Document and release with core-primitives
