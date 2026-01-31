(ns ascolais.phandaal
  "Phandaal - Sandestin effect library for source file modifications with metadata tracking."
  (:require [ascolais.sandestin :as s]
            [ascolais.phandaal.effects :as effects]
            [ascolais.phandaal.storage :as storage]
            [ascolais.phandaal.reload :as reload]
            [clojure.set :as set]))

;; =============================================================================
;; Pending Reloads Interceptor
;; =============================================================================

(def pending-reloads-interceptor
  "Interceptor that initializes and manages the pending reloads atom in dispatch-data.
   Add this to your dispatch interceptors for reload tracking to work."
  {:id ::pending-reloads
   :before-dispatch
   (fn [ctx]
     (if (get-in ctx [:dispatch-data ::pending-reloads-atom])
       ctx  ; Already initialized
       (assoc-in ctx [:dispatch-data ::pending-reloads-atom] (atom #{}))))})

;; =============================================================================
;; Reload Effect Handlers
;; =============================================================================

(defn- make-reload-handler
  "Create handler for ::phandaal/reload effect."
  [{:keys [reload-executor]}]
  (fn [{:keys [dispatch-data]} _system {:keys [only]}]
    (let [pending-atom (::pending-reloads-atom dispatch-data)
          pending (if pending-atom @pending-atom #{})
          to-reload (if only
                      (set/intersection pending only)
                      pending)]
      (if (empty? to-reload)
        {:executor (when reload-executor (:type reload-executor))
         :requested #{}
         :reloaded #{}
         :failed {}
         :skipped #{}
         :pending-remaining pending}
        (if-not reload-executor
          {:error :no-executor
           :message "No :reload-executor configured in registry options"
           :pending pending}
          (let [result (reload/execute reload-executor to-reload)
                reloaded (:reloaded result)
                remaining (set/difference pending reloaded)]
            ;; Update pending set to remove successfully reloaded
            (when pending-atom
              (swap! pending-atom (fn [p] (set/difference p reloaded))))
            {:executor (:type reload-executor)
             :requested to-reload
             :reloaded reloaded
             :failed (:failed result)
             :skipped (:skipped result)
             :pending-remaining remaining}))))))

(defn- make-clear-pending-handler
  "Create handler for ::phandaal/clear-pending effect."
  [_opts]
  (fn [{:keys [dispatch-data]} _system {:keys [only all]}]
    (let [pending-atom (::pending-reloads-atom dispatch-data)
          pending (if pending-atom @pending-atom #{})]
      (cond
        all
        (do
          (when pending-atom (reset! pending-atom #{}))
          {:cleared pending
           :remaining #{}})

        only
        (let [to-clear (set/intersection pending only)]
          (when pending-atom
            (swap! pending-atom (fn [p] (set/difference p to-clear))))
          {:cleared to-clear
           :remaining (set/difference pending to-clear)})

        :else
        {:cleared #{}
         :remaining pending}))))

(defn registry
  "Create a phandaal registry for file operation effects.

   Options:
   - :project-root (required) - Absolute path to project root
   - :source-paths (optional) - Vector of source paths relative to project-root
                                Defaults to [\"src\"]
   - :default-threshold (optional) - Default line threshold for all operations
   - :formatters (optional) - Map of file extension to formatter function
                              e.g. {\".clj\" (phandaal.format/sh \"cljfmt fix {path}\")}
   - :reload-executor (optional) - Reload executor for ::phandaal/reload effect
                                   Use presets from ascolais.phandaal.reload:
                                   clj-reload, tools-namespace, require-reload, noop

   Example:
     (def dispatch
       (s/create-dispatch
         [[phandaal/registry {:project-root \"/project\"
                              :source-paths [\"src/clj\"]
                              :formatters {\".clj\" my-formatter}
                              :reload-executor phandaal.reload/clj-reload}]]))

     (dispatch {} {} [[::phandaal/write {:path \"/project/src/clj/app/core.clj\"
                                          :content \"(ns app.core)\"}]])"
  [{:keys [project-root source-paths default-threshold formatters reload-executor]
    :or {source-paths ["src"]}
    :as opts}]
  (when-not project-root
    (throw (ex-info "phandaal/registry requires :project-root option" {})))
  (let [opts (assoc opts :source-paths source-paths)]
    {::s/effects
     {::write
      {::s/description "Replace entire file contents with metadata tracking"
       ::s/schema [:tuple
                   [:= ::write]
                   [:map
                    [:path :string]
                    [:content :string]
                    [:create-dirs? {:optional true} :boolean]
                    [:threshold {:optional true} :int]]]
       ::s/handler (effects/make-write-handler opts)}

      ::append
      {::s/description "Append content to end of file with metadata tracking"
       ::s/schema [:tuple
                   [:= ::append]
                   [:map
                    [:path :string]
                    [:content :string]
                    [:threshold {:optional true} :int]]]
       ::s/handler (effects/make-append-handler opts)}

      ::insert
      {::s/description "Insert content at specific location with metadata tracking"
       ::s/schema [:tuple
                   [:= ::insert]
                   [:map
                    [:path :string]
                    [:content :string]
                    [:at [:or
                          [:map [:line :int]]
                          [:map [:after [:or :string :any]]]
                          [:map [:before [:or :string :any]]]]]
                    [:threshold {:optional true} :int]]]
       ::s/handler (effects/make-insert-handler opts)}

      ::replace
      {::s/description "Find and replace within file with metadata tracking"
       ::s/schema [:tuple
                   [:= ::replace]
                   [:map
                    [:path :string]
                    [:find [:or :string :any]]
                    [:replacement :string]
                    [:all? {:optional true} :boolean]]]
       ::s/handler (effects/make-replace-handler opts)}

      ::read-meta
      {::s/description "Get file metadata without reading full content"
       ::s/schema [:tuple [:= ::read-meta] :string]
       ::s/handler (effects/make-read-meta-handler opts)}

      ::reload
      {::s/description "Reload pending namespaces using configured executor"
       ::s/schema [:tuple
                   [:= ::reload]
                   [:map
                    [:only {:optional true} [:set :symbol]]]]
       ::s/handler (make-reload-handler opts)}

      ::clear-pending
      {::s/description "Clear namespaces from pending reload set"
       ::s/schema [:tuple
                   [:= ::clear-pending]
                   [:map
                    [:only {:optional true} [:set :symbol]]
                    [:all {:optional true} :boolean]]]
       ::s/handler (make-clear-pending-handler opts)}}

     ::s/placeholders
     {::pending-reloads
      {::s/description "Get the set of namespaces pending reload"
       ::s/handler (fn [dispatch-data]
                     (when-let [atom (::pending-reloads-atom dispatch-data)]
                       @atom))}}}))

;; =============================================================================
;; Audit Log Query Convenience Functions
;; =============================================================================

(defn recent-activity
  "Query recent audit log activity.

   Arguments:
   - storage - An AuditStorage implementation

   Options:
   - :hours - Hours to look back (default 24)
   - :limit - Max entries to return (default 100)

   Returns vector of entries, newest first."
  [storage & {:keys [hours limit] :or {hours 24 limit 100}}]
  (let [since (java.util.Date.
               (- (System/currentTimeMillis) (* hours 60 60 1000)))]
    (storage/query storage {:since since :limit limit})))

(defn session-activity
  "Query audit log activity for a specific session.

   Arguments:
   - storage - An AuditStorage implementation
   - session-id - Session identifier string

   Options:
   - :limit - Max entries to return (default 100)

   Returns vector of entries, newest first."
  [storage session-id & {:keys [limit] :or {limit 100}}]
  (storage/query storage {:session-id session-id :limit limit}))

(defn file-history
  "Query audit log history for a specific file.

   Arguments:
   - storage - An AuditStorage implementation
   - path - File path (prefix match supported)

   Options:
   - :limit - Max entries to return (default 100)

   Returns vector of entries, newest first."
  [storage path & {:keys [limit] :or {limit 100}}]
  (storage/query storage {:file-path path :limit limit}))

(defn warnings
  "Query audit log entries that have hints (warnings/threshold exceeded).

   Arguments:
   - storage - An AuditStorage implementation

   Options:
   - :hours - Hours to look back (default 24)
   - :limit - Max entries to return (default 100)

   Returns vector of entries with non-empty hints, newest first."
  [storage & {:keys [hours limit] :or {hours 24 limit 100}}]
  (let [since (java.util.Date.
               (- (System/currentTimeMillis) (* hours 60 60 1000)))
        entries (storage/query storage {:since since :limit (* limit 10)})]
    (->> entries
         (filter #(seq (:hints %)))
         (take limit)
         vec)))
