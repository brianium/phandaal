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

;; =============================================================================
;; Shared Schemas
;; =============================================================================

(def ^:private file-result-schema
  "Schema for file operation results returned by write, append, insert, replace."
  [:map
   [:path {:description "Absolute path to the file that was modified"} :string]
   [:status {:description "Operation outcome: :ok (modified existing), :created (new file), :error (failed)"}
    [:enum :ok :created :error]]
   [:hints {:description "Vector of threshold hints when limits exceeded (empty otherwise)"}
    [:vector :any]]
   [:loc {:description "Line count metrics for the operation" :optional true}
    [:map
     [:before {:description "Lines before operation (nil for new files)"} [:maybe :int]]
     [:after {:description "Lines after operation"} :int]
     [:delta {:description "Change in line count (nil if :before was nil)"} [:maybe :int]]]]
   [:threshold {:description "Threshold check result (present only if :threshold arg provided)" :optional true}
    [:map
     [:limit {:description "The configured line limit"} :int]
     [:exceeded? {:description "True if file exceeds the limit"} :boolean]
     [:remaining {:description "Lines remaining before limit (negative if exceeded)"} :int]]]
   [:reload {:description "Namespace reload info for Clojure source files" :optional true}
    [:map
     [:namespaces {:description "Namespace symbols affected by this change"} [:vector :symbol]]
     [:type {:description "Reload executor type (:clj-reload, :tools-namespace, etc.)"} :keyword]]]
   [:formatted? {:description "True if a formatter was run on the file" :optional true} :boolean]
   [:format-error {:description "Error message if formatting failed" :optional true} :string]])

(def ^:private read-meta-result-schema
  "Schema for read-meta operation results."
  [:map
   [:path {:description "Absolute path to the file"} :string]
   [:exists? {:description "True if file exists on disk"} :boolean]
   [:loc {:description "Line count (only present if file exists)" :optional true} :int]
   [:modified {:description "Last modification timestamp (only if exists)" :optional true} inst?]
   [:namespace {:description "Inferred Clojure namespace symbol (only for .clj/.cljc/.cljs files)" :optional true}
    [:maybe :symbol]]])

(def ^:private reload-result-schema
  "Schema for reload operation results."
  [:map
   [:executor {:description "Reload executor type used, or nil if none configured"} [:maybe :keyword]]
   [:requested {:description "Set of namespaces that were requested for reload"} [:set :symbol]]
   [:reloaded {:description "Set of namespaces successfully reloaded"} [:set :symbol]]
   [:failed {:description "Map of namespace to error for failed reloads"} [:map-of :symbol :any]]
   [:skipped {:description "Set of namespaces skipped (not found, etc.)"} [:set :symbol]]
   [:pending-remaining {:description "Namespaces still pending after this reload"} [:set :symbol]]])

(def ^:private clear-pending-result-schema
  "Schema for clear-pending operation results."
  [:map
   [:cleared {:description "Set of namespaces removed from pending set"} [:set :symbol]]
   [:remaining {:description "Set of namespaces still pending after clear"} [:set :symbol]]])

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
      {::s/description "Write content to a file, replacing any existing content. Creates parent directories if :create-dirs? is true. Tracks line counts and optionally checks against a threshold limit. For Clojure source files, infers the namespace and adds it to the pending reload set."
       ::s/schema [:tuple
                   [:= ::write]
                   [:map
                    [:path {:description "Absolute path to the target file"} :string]
                    [:content {:description "Complete file contents to write"} :string]
                    [:create-dirs? {:optional true :description "Create parent directories if they don't exist (default false)"} :boolean]
                    [:threshold {:optional true :description "Line count limit; if exceeded, :threshold in result shows details"} :int]]]
       ::returns file-result-schema
       ::examples [{:desc "Write a new Clojure namespace"
                    :effect [::write {:path "/project/src/app/core.clj"
                                      :content "(ns app.core)\n\n(defn greet [name]\n  (str \"Hello, \" name))"
                                      :create-dirs? true}]
                    :returns {:path "/project/src/app/core.clj"
                              :status :created
                              :hints []
                              :loc {:before nil :after 4 :delta nil}
                              :reload {:namespaces ['app.core] :type :clj-reload}}}
                   {:desc "Write with threshold check"
                    :effect [::write {:path "/project/src/app/big.clj"
                                      :content "(ns app.big)\n;; ... 150 lines ..."
                                      :threshold 100}]
                    :returns {:path "/project/src/app/big.clj"
                              :status :ok
                              :hints []
                              :loc {:before 50 :after 150 :delta 100}
                              :threshold {:limit 100 :exceeded? true :remaining -50}
                              :reload {:namespaces ['app.big] :type :clj-reload}}}]
       ::s/handler (effects/make-write-handler opts)}

      ::append
      {::s/description "Append content to the end of an existing file. Does not add a newline before the content - include leading newline in content if needed. Tracks line counts and optionally checks against a threshold limit."
       ::s/schema [:tuple
                   [:= ::append]
                   [:map
                    [:path {:description "Absolute path to the target file"} :string]
                    [:content {:description "Content to append (include leading newline if needed)"} :string]
                    [:threshold {:optional true :description "Line count limit for the resulting file"} :int]]]
       ::returns file-result-schema
       ::examples [{:desc "Append a function to existing file"
                    :effect [::append {:path "/project/src/app/core.clj"
                                       :content "\n\n(defn farewell [name]\n  (str \"Goodbye, \" name))"}]
                    :returns {:path "/project/src/app/core.clj"
                              :status :ok
                              :hints []
                              :loc {:before 4 :after 7 :delta 3}
                              :reload {:namespaces ['app.core] :type :clj-reload}}}]
       ::s/handler (effects/make-append-handler opts)}

      ::insert
      {::s/description "Insert content at a specific location in an existing file. Location is specified via :at with one of: {:line N} for 1-based line number, {:after pattern} to insert after first matching line, or {:before pattern} to insert before first matching line. Patterns can be literal strings or regex patterns. Tracks line counts and optionally checks against a threshold limit."
       ::s/schema [:tuple
                   [:= ::insert]
                   [:map
                    [:path {:description "Absolute path to the target file (must exist)"} :string]
                    [:content {:description "Content to insert (may be multiple lines)"} :string]
                    [:at {:description "Insertion point specification"}
                     [:or
                      [:map [:line {:description "1-based line number to insert before"} :int]]
                      [:map [:after {:description "Insert after first line matching this string or regex"}
                             [:or :string [:fn {:description "java.util.regex.Pattern"} #(instance? java.util.regex.Pattern %)]]]]
                      [:map [:before {:description "Insert before first line matching this string or regex"}
                             [:or :string [:fn {:description "java.util.regex.Pattern"} #(instance? java.util.regex.Pattern %)]]]]]]
                    [:threshold {:optional true :description "Line count limit for the resulting file"} :int]]]
       ::returns file-result-schema
       ::examples [{:desc "Insert after namespace declaration"
                    :effect [::insert {:path "/project/src/app/core.clj"
                                       :content "\n(require '[clojure.string :as str])"
                                       :at {:after "(ns app.core)"}}]
                    :returns {:path "/project/src/app/core.clj"
                              :status :ok
                              :hints []
                              :loc {:before 4 :after 6 :delta 2}
                              :reload {:namespaces ['app.core] :type :clj-reload}}}
                   {:desc "Insert at specific line"
                    :effect [::insert {:path "/project/src/app/core.clj"
                                       :content ";; Copyright 2024"
                                       :at {:line 1}}]
                    :returns {:path "/project/src/app/core.clj"
                              :status :ok
                              :hints []
                              :loc {:before 4 :after 5 :delta 1}
                              :reload {:namespaces ['app.core] :type :clj-reload}}}]
       ::s/handler (effects/make-insert-handler opts)}

      ::replace
      {::s/description "Find and replace text within an existing file. The :find pattern can be a literal string or a regex pattern. By default replaces only the first match; set :all? true to replace all occurrences."
       ::s/schema [:tuple
                   [:= ::replace]
                   [:map
                    [:path {:description "Absolute path to the target file (must exist)"} :string]
                    [:find {:description "Pattern to find: literal string or java.util.regex.Pattern"}
                     [:or :string [:fn {:description "java.util.regex.Pattern"} #(instance? java.util.regex.Pattern %)]]]
                    [:replacement {:description "Text to replace matches with"} :string]
                    [:all? {:optional true :description "Replace all occurrences (default false, first match only)"} :boolean]]]
       ::returns file-result-schema
       ::examples [{:desc "Rename a function"
                    :effect [::replace {:path "/project/src/app/core.clj"
                                        :find "defn greet"
                                        :replacement "defn say-hello"
                                        :all? true}]
                    :returns {:path "/project/src/app/core.clj"
                              :status :ok
                              :hints []
                              :loc {:before 4 :after 4 :delta 0}
                              :reload {:namespaces ['app.core] :type :clj-reload}}}
                   {:desc "Replace with regex"
                    :effect [::replace {:path "/project/src/app/core.clj"
                                        :find #"TODO:.*"
                                        :replacement "DONE"
                                        :all? true}]
                    :returns {:path "/project/src/app/core.clj"
                              :status :ok
                              :hints []
                              :loc {:before 10 :after 8 :delta -2}
                              :reload {:namespaces ['app.core] :type :clj-reload}}}]
       ::s/handler (effects/make-replace-handler opts)}

      ::read-meta
      {::s/description "Get metadata about a file without reading its full content. Returns existence status, line count, modification time, and inferred namespace for Clojure files. Useful for checking file state before deciding on an operation."
       ::s/schema [:tuple
                   [:= ::read-meta]
                   [:string {:description "Absolute path to the file to inspect"}]]
       ::returns read-meta-result-schema
       ::examples [{:desc "Check existing Clojure file"
                    :effect [::read-meta "/project/src/app/core.clj"]
                    :returns {:path "/project/src/app/core.clj"
                              :exists? true
                              :loc 42
                              :modified #inst "2024-01-15T10:30:00Z"
                              :namespace 'app.core}}
                   {:desc "Check non-existent file"
                    :effect [::read-meta "/project/src/app/missing.clj"]
                    :returns {:path "/project/src/app/missing.clj"
                              :exists? false}}]
       ::s/handler (effects/make-read-meta-handler opts)}

      ::reload
      {::s/description "Reload namespaces that have pending changes. File effects (write, append, insert, replace) automatically track modified Clojure namespaces. This effect triggers the configured :reload-executor to reload them. Use :only to reload a subset of pending namespaces. Requires pending-reloads-interceptor in the dispatch chain."
       ::s/schema [:tuple
                   [:= ::reload]
                   [:map
                    [:only {:optional true :description "Subset of pending namespaces to reload; omit to reload all pending"}
                     [:set :symbol]]]]
       ::returns reload-result-schema
       ::see-also [::clear-pending ::pending-reloads]
       ::examples [{:desc "Reload all pending namespaces"
                    :effect [::reload {}]
                    :returns {:executor :clj-reload
                              :requested #{'app.core 'app.routes}
                              :reloaded #{'app.core 'app.routes}
                              :failed {}
                              :skipped #{}
                              :pending-remaining #{}}}
                   {:desc "Reload specific namespace only"
                    :effect [::reload {:only #{'app.core}}]
                    :returns {:executor :clj-reload
                              :requested #{'app.core}
                              :reloaded #{'app.core}
                              :failed {}
                              :skipped #{}
                              :pending-remaining #{'app.routes}}}]
       ::s/handler (make-reload-handler opts)}

      ::clear-pending
      {::s/description "Remove namespaces from the pending reload set without reloading them. Useful when you want to discard pending reloads (e.g., after reverting file changes) or manage the pending set manually. Use :only to clear specific namespaces, or :all true to clear everything."
       ::s/schema [:tuple
                   [:= ::clear-pending]
                   [:map
                    [:only {:optional true :description "Specific namespaces to remove from pending set"}
                     [:set :symbol]]
                    [:all {:optional true :description "Clear all pending namespaces (default false)"} :boolean]]]
       ::returns clear-pending-result-schema
       ::see-also [::reload ::pending-reloads]
       ::examples [{:desc "Clear all pending"
                    :effect [::clear-pending {:all true}]
                    :returns {:cleared #{'app.core 'app.routes}
                              :remaining #{}}}
                   {:desc "Clear specific namespace"
                    :effect [::clear-pending {:only #{'app.core}}]
                    :returns {:cleared #{'app.core}
                              :remaining #{'app.routes}}}]
       ::s/handler (make-clear-pending-handler opts)}}

     ::s/placeholders
     {::pending-reloads
      {::s/description "Returns the set of namespace symbols pending reload. Namespaces are added automatically when file effects modify Clojure source files (.clj, .cljc, .cljs). Use ::reload to reload them or ::clear-pending to discard. Requires pending-reloads-interceptor in the dispatch chain."
       ::returns [:set :symbol]
       ::see-also [::reload ::clear-pending]
       ::examples [{:desc "Check pending namespaces"
                    :usage "[::pending-reloads]"
                    :returns #{'app.core 'app.routes}}]
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
