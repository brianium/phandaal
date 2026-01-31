(ns ascolais.phandaal.storage.sqlite
  "SQLite storage driver for phandaal audit logging.

   Provides indexed queries for efficient filtering on large audit logs.
   Requires next.jdbc and SQLite JDBC driver dependencies."
  (:require [ascolais.phandaal.storage :as storage]
            [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str])
  (:import [java.util UUID Date]
           [java.time Instant]
           [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Date Conversion
;; =============================================================================

(def ^:private iso-formatter DateTimeFormatter/ISO_INSTANT)

(defn- date->iso [^Date d]
  (when d
    (.format iso-formatter (.toInstant d))))

(defn- iso->date [s]
  (when s
    (Date/from (Instant/parse s))))

;; =============================================================================
;; Schema
;; =============================================================================

(def ^:private schema
  "CREATE TABLE IF NOT EXISTS audit_log (
     id TEXT PRIMARY KEY,
     ts TEXT NOT NULL,
     session_id TEXT,
     effect_key TEXT NOT NULL,
     effect_args TEXT,
     result TEXT,
     file_path TEXT,
     hints TEXT,
     status TEXT,
     created_at TEXT DEFAULT CURRENT_TIMESTAMP
   );

   CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_log(ts);
   CREATE INDEX IF NOT EXISTS idx_audit_session ON audit_log(session_id);
   CREATE INDEX IF NOT EXISTS idx_audit_effect ON audit_log(effect_key);
   CREATE INDEX IF NOT EXISTS idx_audit_file ON audit_log(file_path);
   CREATE INDEX IF NOT EXISTS idx_audit_status ON audit_log(status);")

(defn- init-schema! [ds]
  (doseq [stmt (str/split schema #";")]
    (let [stmt (str/trim stmt)]
      (when-not (str/blank? stmt)
        (jdbc/execute! ds [(str stmt ";")])))))

;; =============================================================================
;; Conversion
;; =============================================================================

(defn- entry->row [entry]
  {:id (str (:id entry))
   :ts (date->iso (:ts entry))
   :session_id (:session-id entry)
   :effect_key (pr-str (:effect-key entry))
   :effect_args (pr-str (:effect-args entry))
   :result (pr-str (:result entry))
   :file_path (:file-path entry)
   :hints (pr-str (:hints entry))
   :status (name (:status entry))})

(defn- row->entry [row]
  {:id (UUID/fromString (:audit_log/id row))
   :ts (iso->date (:audit_log/ts row))
   :session-id (:audit_log/session_id row)
   :effect-key (edn/read-string (:audit_log/effect_key row))
   :effect-args (edn/read-string (:audit_log/effect_args row))
   :result (edn/read-string (:audit_log/result row))
   :file-path (:audit_log/file_path row)
   :hints (edn/read-string (:audit_log/hints row))
   :status (keyword (:audit_log/status row))})

;; =============================================================================
;; Query Building
;; =============================================================================

(defn- build-query-sql
  "Build SQL query with WHERE clauses based on opts."
  [{:keys [since until session-id effect-key file-path status limit]
    :or {limit 100}}]
  (let [conditions (cond-> []
                     since (conj "ts > ?")
                     until (conj "ts < ?")
                     session-id (conj "session_id = ?")
                     effect-key (conj "effect_key = ?")
                     file-path (conj "file_path LIKE ?")
                     status (conj "status = ?"))
        where (when (seq conditions)
                (str " WHERE " (str/join " AND " conditions)))
        params (cond-> []
                 since (conj (date->iso since))
                 until (conj (date->iso until))
                 session-id (conj session-id)
                 effect-key (conj (pr-str effect-key))
                 file-path (conj (str file-path "%"))
                 status (conj (name status)))]
    (into [(str "SELECT * FROM audit_log" where " ORDER BY ts DESC LIMIT ?")
           (conj params limit)]
          [])))

(defn- flatten-query-params [[sql params]]
  (into [sql] params))

;; =============================================================================
;; Storage Implementation
;; =============================================================================

(defrecord SQLiteStorage [ds]
  storage/AuditStorage

  (append! [_this entry]
    (let [row (entry->row entry)]
      (jdbc/execute! ds
                     ["INSERT INTO audit_log (id, ts, session_id, effect_key, effect_args, result, file_path, hints, status)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                      (:id row)
                      (:ts row)
                      (:session_id row)
                      (:effect_key row)
                      (:effect_args row)
                      (:result row)
                      (:file_path row)
                      (:hints row)
                      (:status row)]))
    entry)

  (query [_this opts]
    (let [[sql & params] (flatten-query-params (build-query-sql opts))
          rows (jdbc/execute! ds (into [sql] params)
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (->> rows
           (map (fn [row]
                  {:id (UUID/fromString (:id row))
                   :ts (iso->date (:ts row))
                   :session-id (:session_id row)
                   :effect-key (edn/read-string (:effect_key row))
                   :effect-args (edn/read-string (:effect_args row))
                   :result (edn/read-string (:result row))
                   :file-path (:file_path row)
                   :hints (edn/read-string (:hints row))
                   :status (keyword (:status row))}))
           vec)))

  (clear! [_this opts]
    (let [{:keys [before all]} opts]
      (cond
        all
        (let [{:keys [next.jdbc/update-count]}
              (jdbc/execute-one! ds ["DELETE FROM audit_log"])]
          (or update-count 0))

        before
        (let [{:keys [next.jdbc/update-count]}
              (jdbc/execute-one! ds ["DELETE FROM audit_log WHERE ts < ?"
                                     (date->iso before)])]
          (or update-count 0))

        :else 0))))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create
  "Create a SQLite audit storage.

   Options:
   - :path (required) - Path to the SQLite database file

   Dependencies (add to deps.edn):
     com.github.seancorfield/next.jdbc {:mvn/version \"1.3.939\"}
     org.xerial/sqlite-jdbc {:mvn/version \"3.45.1.0\"}

   Example:
     (create {:path \".phandaal/audit.db\"})"
  [{:keys [path]}]
  (when-not path
    (throw (ex-info "SQLiteStorage requires :path option" {})))
  (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname path})]
    (init-schema! ds)
    (->SQLiteStorage ds)))
