# Reload Tracking - Research

## Problem Statement

After modifying Clojure source files, the REPL's loaded namespaces are stale. Different projects use different reload strategies:
- clj-reload for running systems with state
- tools.namespace for simpler cases
- Plain `require :reload` for quick iterations
- Manual reload for careful control

Phandaal needs to track which namespaces changed without prescribing how to reload them. The tracking should be automatic; the reloading should be configurable.

## Requirements

### Functional Requirements

1. **Track affected namespaces**: After file effects, record which namespaces need reload
2. **Accumulate across effects**: Multiple file changes in one dispatch batch together
3. **Expose pending reloads**: Placeholder and effect for querying pending set
4. **Pluggable executor**: Configure how reloads actually happen
5. **Explicit trigger**: User dispatches `::phandaal/reload` to execute
6. **Clear after reload**: Remove from pending set after successful reload
7. **Preset executors**: Ship with clj-reload, tools.namespace, require presets

### Non-Functional Requirements

- **Safety**: Never reload without explicit trigger
- **Flexibility**: Support any reload strategy via executor protocol
- **Transparency**: Pending reloads visible in dispatch results

## Tracking Mechanism

After each file-modifying effect, the handler (or interceptor) adds affected namespaces to dispatch-data:

```clojure
;; In effect handler
(fn [{:keys [dispatch-data]} system args]
  (let [result (perform-file-op args)
        ns-sym (path->namespace (:path result))]
    ;; Return result, tracking happens via ctx update
    {:result result
     :pending-reload ns-sym}))

;; Interceptor or handler adds to dispatch-data
(update ctx :dispatch-data update ::phandaal/pending-reloads
        (fnil conj #{}) ns-sym)
```

Accumulated in dispatch-data:

```clojure
{::phandaal/pending-reloads #{sandbox.ui sandbox.routes app.core}}
```

Accessible via placeholder:

```clojure
[::phandaal/pending-reloads]  ;; => #{sandbox.ui sandbox.routes app.core}
```

## Executor Protocol

```clojure
(defprotocol ReloadExecutor
  (reload! [this namespaces]
    "Reload the given namespaces. Returns map with:
     :reloaded - set of successfully reloaded namespaces
     :failed   - map of {ns-sym error} for failures
     :skipped  - set of namespaces skipped (e.g., not found)"))
```

## Options Considered

### Option A: Automatic Reload After Every Effect

**Description:** Interceptor that reloads immediately after each file effect.

**Pros:**
- Always up to date
- No manual step

**Cons:**
- Slow for batch operations
- No control over timing
- Can break running systems

### Option B: Explicit Reload Effect (Recommended)

**Description:** Track automatically, reload only when `::phandaal/reload` dispatched.

**Pros:**
- Batch multiple changes, reload once
- User controls timing
- Safe for stateful systems

**Cons:**
- Requires extra dispatch call
- User must remember to reload

### Option C: After-Dispatch Hint Only

**Description:** Don't provide reload effect, just surface hint in results.

**Pros:**
- Maximum flexibility
- Zero opinion on reload

**Cons:**
- No convenience for common case
- Every user reimplements reload

## Recommendation

**Option B: Explicit reload effect** with tracking. This gives:
- Automatic tracking (always know what changed)
- Controlled reload (user decides when)
- Configurable executor (user decides how)
- Reasonable default (track only, no executor = safe)

## Executor Presets

### clj-reload (Recommended for Stateful Systems)

```clojure
(ns ascolais.phandaal.reload)

(def clj-reload
  "Uses clj-reload library. Best for systems with running state.
   Requires clj-reload on classpath."
  {:type :clj-reload
   :reload-fn
   (fn [namespaces]
     (require '[clj-reload.core :as reload])
     (let [result ((resolve 'reload/reload) {:only (vec namespaces)})]
       {:reloaded (set (:loaded result))
        :failed {}
        :skipped #{}}))})
```

### tools.namespace

```clojure
(def tools-namespace
  "Uses clojure.tools.namespace. Simpler but doesn't handle state.
   Requires tools.namespace on classpath."
  {:type :tools.namespace
   :reload-fn
   (fn [_namespaces]
     ;; tools.namespace refreshes based on file changes, not specific namespaces
     (require '[clojure.tools.namespace.repl :as tn])
     (let [result ((resolve 'tn/refresh))]
       (if (= :ok result)
         {:reloaded :all :failed {} :skipped #{}}
         {:reloaded #{} :failed {:refresh result} :skipped #{}})))})
```

### require :reload

```clojure
(def require-reload
  "Simple require with :reload flag. Doesn't handle dependency order.
   Good for quick iterations on isolated namespaces."
  {:type :require
   :reload-fn
   (fn [namespaces]
     (let [results (for [ns namespaces]
                     (try
                       (require ns :reload)
                       {:ns ns :status :ok}
                       (catch Exception e
                         {:ns ns :status :error :error e})))]
       {:reloaded (->> results (filter #(= :ok (:status %))) (map :ns) set)
        :failed (->> results (filter #(= :error (:status %)))
                     (map (juxt :ns :error)) (into {}))
        :skipped #{}}))})
```

### noop (Track Only)

```clojure
(def noop
  "Track pending reloads but don't actually reload.
   For projects that prefer manual control."
  {:type :noop
   :reload-fn (fn [namespaces]
                {:reloaded #{}
                 :failed {}
                 :skipped (set namespaces)})})
```

## Reload Effect

```clojure
;; Schema
[:tuple [:= ::phandaal/reload]
 [:map
  [:only {:optional true} [:set :symbol]]]]  ;; Reload specific, or all pending

;; Usage - reload all pending
[::phandaal/reload]

;; Usage - reload specific
[::phandaal/reload {:only #{sandbox.ui}}]

;; Result
{:executor :clj-reload
 :requested #{sandbox.ui sandbox.routes}
 :reloaded #{sandbox.ui sandbox.routes}
 :failed {}
 :skipped #{}
 :pending-remaining #{}}  ;; What's still pending after this reload
```

## Configuration

In registry options:

```clojure
(phandaal/registry
  {:project-root "/project"
   :source-paths ["src/clj" "dev/src/clj"]
   :reload-executor phandaal.reload/clj-reload})  ;; Or tools-namespace, require-reload, noop
```

If no executor provided, `::phandaal/reload` effect returns error indicating no executor configured.

## Integration Pattern

```clojure
;; Make multiple file changes
(dispatch [[::phandaal/append {:path "src/app/core.clj" :content "..."}]
           [::phandaal/append {:path "src/app/routes.clj" :content "..."}]])

;; Check what needs reloading
(get-in result [:dispatch-data ::phandaal/pending-reloads])
;; => #{app.core app.routes}

;; Reload when ready
(dispatch [[::phandaal/reload]])

;; Result shows what was reloaded
;; => {:reloaded #{app.core app.routes} :pending-remaining #{}}
```

## Open Questions

- [x] Should reload clear all pending or just what was reloaded? → Just what was reloaded, preserve failures
- [x] Should there be a `::phandaal/clear-pending` for manual clearing? → Yes, useful for "I'll handle this myself"
- [ ] Should failed reloads be retried automatically? → No, user should investigate and retry explicitly

## References

- [clj-reload](https://github.com/tonsky/clj-reload) - Recommended for stateful systems
- [tools.namespace](https://github.com/clojure/tools.namespace) - Classic approach
- [Sandestin dispatch-data](https://github.com/ascolais/sandestin) - Where pending tracked
