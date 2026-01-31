(ns ascolais.phandaal.interceptors
  "Interceptors for phandaal effect processing."
  (:require [ascolais.phandaal.storage :as storage]))

(defn audit-log
  "Create an interceptor that logs phandaal effects to storage.

   Arguments:
   - storage - An AuditStorage implementation

   Options:
   - :effect-filter - Predicate (fn [effect]) to skip logging certain effects
   - :content-limit - Max chars for content fields (default 1000)
   - :session-id - Session identifier to include in entries

   Example:
     (require '[ascolais.phandaal.storage.file :as file-storage]
              '[ascolais.phandaal.interceptors :as phandaal-int])

     (def audit-storage (file-storage/create {:path \".phandaal/audit.edn\"}))

     (s/create-dispatch
       {:registries [(phandaal/registry {...})]
        ::s/interceptors [(phandaal-int/audit-log audit-storage
                            :content-limit 500
                            :session-id \"session-123\")]})"
  [storage & {:keys [effect-filter content-limit session-id]
              :or {effect-filter (constantly true)
                   content-limit 1000}}]
  {:id ::audit-log
   :after-effect
   (fn [ctx]
     (let [{:keys [effect result]} ctx
           effect-key (if (sequential? effect) (first effect) effect)]
       (when (and (storage/phandaal-effect? effect-key)
                  (effect-filter effect))
         (storage/append! storage
                          (storage/build-entry ctx {:content-limit content-limit
                                                    :session-id session-id}))))
     ctx)})
