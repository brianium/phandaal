(ns ascolais.phandaal.io
  "File I/O utilities for phandaal effects."
  (:require [clojure.java.io :as io])
  (:import [java.io File]))

(defn count-lines
  "Count lines in a file. Returns 0 if file doesn't exist."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (with-open [rdr (io/reader f)]
        (count (line-seq rdr)))
      0)))

(defn ensure-parent-dirs
  "Create parent directories for path if they don't exist."
  [path]
  (let [parent (.getParentFile (io/file path))]
    (when (and parent (not (.exists parent)))
      (.mkdirs parent))))

(defn atomic-spit
  "Write content to path atomically (write to temp, then rename).
   Options:
   - :create-dirs? - create parent directories if missing"
  [path content & {:keys [create-dirs?]}]
  (when create-dirs?
    (ensure-parent-dirs path))
  (let [target (io/file path)
        parent (.getParentFile target)
        temp (File/createTempFile "phandaal" ".tmp" parent)]
    (try
      (spit temp content)
      (.renameTo temp target)
      (finally
        (when (.exists temp)
          (.delete temp))))))
