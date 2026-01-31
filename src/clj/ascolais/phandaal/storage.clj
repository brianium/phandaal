(ns ascolais.phandaal.storage
  "Storage protocol and entry helpers for phandaal audit logging."
  (:require [clojure.string :as str])
  (:import [java.util UUID]))

;; =============================================================================
;; Storage Protocol
;; =============================================================================

(defprotocol AuditStorage
  "Protocol for audit log storage backends."
  (append! [this entry]
    "Append an entry to the log. Returns entry with :id if generated.")
  (query [this opts]
    "Query entries. Options:
     :since      - #inst, entries after this time
     :until      - #inst, entries before this time
     :limit      - int, max entries to return (default 100)
     :session-id - string, filter by session
     :effect-key - keyword, filter by effect type
     :file-path  - string, filter by file (prefix match)
     :status     - keyword, filter by result status
     Returns vector of entries, newest first.")
  (clear! [this opts]
    "Clear entries. Options:
     :before - #inst, clear entries before this time
     :all    - boolean, clear everything
     Returns count of entries cleared."))

;; =============================================================================
;; Entry Schema (Malli)
;; =============================================================================

(def Entry
  "Malli schema for audit log entries."
  [:map
   [:id :uuid]
   [:ts inst?]
   [:session-id {:optional true} :string]
   [:effect-key :keyword]
   [:effect-args :map]
   [:result :map]
   [:file-path {:optional true} :string]
   [:hints [:vector :map]]
   [:status :keyword]])

;; =============================================================================
;; Entry Building
;; =============================================================================

(defn truncate-content
  "Truncate string content to max-length, adding ellipsis if truncated."
  [s max-length]
  (if (and s (> (count s) max-length))
    (str (subs s 0 max-length) "... (truncated)")
    s))

(defn truncate-effect-args
  "Truncate :content field in effect args if present."
  [args content-limit]
  (if (contains? args :content)
    (update args :content truncate-content content-limit)
    args))

(defn extract-file-path
  "Extract file path from effect args or result."
  [effect-args result]
  (or (:path effect-args)
      (:path result)
      (when (string? effect-args) effect-args)))

(defn extract-hints
  "Extract hints from result, defaulting to empty vector."
  [result]
  (or (:hints result) []))

(defn build-entry
  "Build an audit log entry from effect context.

   Options:
   - :content-limit - max chars for content fields (default 1000)
   - :session-id - session identifier to include"
  [{:keys [effect result]} {:keys [content-limit session-id]
                            :or {content-limit 1000}}]
  (let [[effect-key effect-args] (if (sequential? effect)
                                   [(first effect) (second effect)]
                                   [effect nil])
        truncated-args (if (map? effect-args)
                         (truncate-effect-args effect-args content-limit)
                         effect-args)]
    (cond-> {:id (UUID/randomUUID)
             :ts (java.util.Date.)
             :effect-key effect-key
             :effect-args (or truncated-args {})
             :result (or result {})
             :file-path (extract-file-path effect-args result)
             :hints (extract-hints result)
             :status (or (:status result) :unknown)}
      session-id (assoc :session-id session-id))))

;; =============================================================================
;; Phandaal Effect Detection
;; =============================================================================

(def phandaal-effects
  "Set of phandaal effect keywords."
  #{:ascolais.phandaal/write
    :ascolais.phandaal/append
    :ascolais.phandaal/insert
    :ascolais.phandaal/replace
    :ascolais.phandaal/read-meta})

(defn phandaal-effect?
  "Check if effect-key is a phandaal effect."
  [effect-key]
  (or (contains? phandaal-effects effect-key)
      (and (keyword? effect-key)
           (= "ascolais.phandaal" (namespace effect-key)))))
