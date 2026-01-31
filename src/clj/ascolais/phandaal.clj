(ns ascolais.phandaal
  "Phandaal - Sandestin effect library for source file modifications with metadata tracking."
  (:require [ascolais.sandestin :as s]
            [ascolais.phandaal.effects :as effects]
            [ascolais.phandaal.storage :as storage]))

(defn registry
  "Create a phandaal registry for file operation effects.

   Options:
   - :project-root (required) - Absolute path to project root
   - :source-paths (optional) - Vector of source paths relative to project-root
                                Defaults to [\"src\"]
   - :default-threshold (optional) - Default line threshold for all operations
   - :formatters (optional) - Map of file extension to formatter function
                              e.g. {\".clj\" (phandaal.format/sh \"cljfmt fix {path}\")}

   Example:
     (def dispatch
       (s/create-dispatch
         [[phandaal/registry {:project-root \"/project\"
                              :source-paths [\"src/clj\"]
                              :formatters {\".clj\" my-formatter}}]]))

     (dispatch {} {} [[::phandaal/write {:path \"/project/src/clj/app/core.clj\"
                                          :content \"(ns app.core)\"}]])"
  [{:keys [project-root source-paths default-threshold formatters]
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
       ::s/handler (effects/make-read-meta-handler opts)}}

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
