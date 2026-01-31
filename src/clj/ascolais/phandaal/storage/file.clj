(ns ascolais.phandaal.storage.file
  "Flat-file EDN storage driver for phandaal audit logging.

   Stores one EDN entry per line for easy appending and grepping.
   Suitable for projects with moderate audit log sizes."
  (:require [ascolais.phandaal.storage :as storage]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io FileOutputStream]
           [java.nio.channels FileChannel]
           [java.util Date]))

;; =============================================================================
;; File Operations
;; =============================================================================

(defn- ensure-parent-dirs [path]
  (let [parent (.getParentFile (io/file path))]
    (when (and parent (not (.exists parent)))
      (.mkdirs parent))))

(defn- append-line!
  "Append a line to file with fsync for durability."
  [path line]
  (ensure-parent-dirs path)
  (with-open [fos (FileOutputStream. (io/file path) true)]
    (.write fos (.getBytes (str line "\n") "UTF-8"))
    (.getFD fos)
    (.sync (.getFD fos))))

(defn- read-entries
  "Read all entries from file, returning vector of maps.
   Skips malformed lines gracefully."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (->> (str/split-lines (slurp f))
           (remove str/blank?)
           (keep (fn [line]
                   (try
                     (edn/read-string line)
                     (catch Exception _
                       nil))))
           vec)
      [])))

(defn- write-entries!
  "Overwrite file with given entries."
  [path entries]
  (ensure-parent-dirs path)
  (let [content (->> entries
                     (map pr-str)
                     (str/join "\n"))]
    (spit path (if (str/blank? content) "" (str content "\n")))))

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn- matches-filter?
  "Check if entry matches all specified filters."
  [entry {:keys [since until session-id effect-key file-path status]}]
  (and (or (nil? since) (.after ^Date (:ts entry) since))
       (or (nil? until) (.before ^Date (:ts entry) until))
       (or (nil? session-id) (= session-id (:session-id entry)))
       (or (nil? effect-key) (= effect-key (:effect-key entry)))
       (or (nil? file-path) (str/starts-with? (or (:file-path entry) "") file-path))
       (or (nil? status) (= status (:status entry)))))

(defn- sort-by-ts-desc [entries]
  (sort-by :ts #(compare %2 %1) entries))

;; =============================================================================
;; Storage Implementation
;; =============================================================================

(defrecord FileStorage [path]
  storage/AuditStorage

  (append! [_this entry]
    (append-line! path (pr-str entry))
    entry)

  (query [_this opts]
    (let [{:keys [limit] :or {limit 100}} opts
          entries (read-entries path)]
      (->> entries
           (filter #(matches-filter? % opts))
           sort-by-ts-desc
           (take limit)
           vec)))

  (clear! [_this opts]
    (let [{:keys [before all]} opts]
      (cond
        all
        (let [count (count (read-entries path))]
          (spit path "")
          count)

        before
        (let [entries (read-entries path)
              to-keep (remove #(.before ^Date (:ts %) before) entries)
              cleared (- (count entries) (count to-keep))]
          (write-entries! path to-keep)
          cleared)

        :else 0))))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create
  "Create a flat-file audit storage.

   Options:
   - :path (required) - Path to the EDN file

   Example:
     (create {:path \".phandaal/audit.edn\"})"
  [{:keys [path]}]
  (when-not path
    (throw (ex-info "FileStorage requires :path option" {})))
  (->FileStorage path))
