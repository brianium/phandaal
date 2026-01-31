(ns ascolais.phandaal.reload
  "Reload executor presets for phandaal.

   Executors are maps with :type and :reload-fn keys.
   The :reload-fn takes a set of namespace symbols and returns:
     {:reloaded #{syms...}  ; Successfully reloaded
      :failed {sym error}   ; Failed with errors
      :skipped #{syms...}}  ; Skipped (e.g., not found)")

;; =============================================================================
;; clj-reload Executor
;; =============================================================================

(def clj-reload
  "Uses clj-reload library. Best for systems with running state.

   Requires clj-reload on classpath:
     io.github.tonsky/clj-reload {:mvn/version \"1.0.0\"}

   Features:
   - Preserves defonce state
   - Handles dependency ordering
   - Unloads removed namespaces"
  {:type :clj-reload
   :reload-fn
   (fn [namespaces]
     (try
       (require 'clj-reload.core)
       (let [reload-fn (resolve 'clj-reload.core/reload)
             result (reload-fn {:only (vec namespaces)})]
         {:reloaded (set (:loaded result))
          :failed (if (:error result)
                    {(:error-ns result) (:error result)}
                    {})
          :skipped (set (:unloaded result))})
       (catch Exception e
         {:reloaded #{}
          :failed {:clj-reload e}
          :skipped (set namespaces)})))})

;; =============================================================================
;; tools.namespace Executor
;; =============================================================================

(def tools-namespace
  "Uses clojure.tools.namespace. Classic approach for simpler projects.

   Requires tools.namespace on classpath:
     org.clojure/tools.namespace {:mvn/version \"1.5.0\"}

   Note: Refreshes all changed files, not just specified namespaces.
   Does not preserve state - defonce values reset."
  {:type :tools.namespace
   :reload-fn
   (fn [namespaces]
     (try
       (require 'clojure.tools.namespace.repl)
       (let [refresh-fn (resolve 'clojure.tools.namespace.repl/refresh)
             result (refresh-fn)]
         (if (= :ok result)
           {:reloaded (set namespaces)
            :failed {}
            :skipped #{}}
           {:reloaded #{}
            :failed {:refresh result}
            :skipped (set namespaces)}))
       (catch Exception e
         {:reloaded #{}
          :failed {:tools.namespace e}
          :skipped (set namespaces)})))})

;; =============================================================================
;; require :reload Executor
;; =============================================================================

(def require-reload
  "Simple require with :reload flag.

   No additional dependencies required.

   Note: Does not handle dependency ordering. Good for quick
   iterations on isolated namespaces."
  {:type :require
   :reload-fn
   (fn [namespaces]
     (let [results (for [ns-sym namespaces]
                     (try
                       (require ns-sym :reload)
                       {:ns ns-sym :status :ok}
                       (catch Exception e
                         {:ns ns-sym :status :error :error e})))]
       {:reloaded (->> results
                       (filter #(= :ok (:status %)))
                       (map :ns)
                       set)
        :failed (->> results
                     (filter #(= :error (:status %)))
                     (map (juxt :ns :error))
                     (into {}))
        :skipped #{}}))})

;; =============================================================================
;; noop Executor
;; =============================================================================

(def noop
  "Track pending reloads but don't actually reload.

   Useful for:
   - Projects that prefer manual control
   - Testing reload tracking without side effects
   - CI environments where reload isn't needed"
  {:type :noop
   :reload-fn
   (fn [namespaces]
     {:reloaded #{}
      :failed {}
      :skipped (set namespaces)})})

;; =============================================================================
;; Executor Helpers
;; =============================================================================

(defn execute
  "Execute a reload using the given executor.

   Arguments:
   - executor - A reload executor map with :reload-fn
   - namespaces - Set of namespace symbols to reload

   Returns map with :reloaded, :failed, :skipped sets/maps."
  [executor namespaces]
  (if-let [reload-fn (:reload-fn executor)]
    (reload-fn namespaces)
    {:reloaded #{}
     :failed {:executor "No :reload-fn in executor"}
     :skipped (set namespaces)}))
