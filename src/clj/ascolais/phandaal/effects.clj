(ns ascolais.phandaal.effects
  "Effect handlers for phandaal file operations."
  (:require [ascolais.phandaal.io :as pio]
            [ascolais.phandaal.ns :as pns]
            [ascolais.phandaal.result :as result]
            [ascolais.phandaal.format :as format]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- file-exists? [path]
  (.exists (io/file path)))

(defn- infer-namespace [{:keys [project-root source-paths]} path]
  (when (pns/clojure-file? path)
    (pns/path->namespace path project-root source-paths)))

(defn- track-reload!
  "Track namespace for pending reload in dispatch-data atom."
  [dispatch-data namespace]
  (when (and namespace (:ascolais.phandaal/pending-reloads-atom dispatch-data))
    (swap! (:ascolais.phandaal/pending-reloads-atom dispatch-data)
           (fnil conj #{})
           namespace)))

(defn make-write-handler
  "Create handler for ::phandaal/write effect.
   Replaces entire file contents."
  [opts]
  (fn [{:keys [dispatch-data]} _system {:keys [path content create-dirs? threshold]}]
    (let [existed? (file-exists? path)
          loc-before (when existed? (pio/count-lines path))
          _ (pio/atomic-spit path content :create-dirs? create-dirs?)
          {:keys [formatted? format-error]} (format/run-formatter! path (:formatters opts))
          loc-after (pio/count-lines path)
          namespace (infer-namespace opts path)]
      (track-reload! dispatch-data namespace)
      (cond-> (result/result-map
               {:path path
                :status (if existed? :ok :created)
                :loc-before loc-before
                :loc-after loc-after
                :threshold threshold
                :namespace namespace
                :formatted? formatted?})
        format-error (assoc :format-error format-error)))))

(defn make-append-handler
  "Create handler for ::phandaal/append effect.
   Appends content to end of file."
  [opts]
  (fn [{:keys [dispatch-data]} _system {:keys [path content threshold]}]
    (let [existed? (file-exists? path)
          loc-before (when existed? (pio/count-lines path))
          current (if existed? (slurp path) "")
          new-content (str current content)
          _ (pio/atomic-spit path new-content)
          {:keys [formatted? format-error]} (format/run-formatter! path (:formatters opts))
          loc-after (pio/count-lines path)
          namespace (infer-namespace opts path)]
      (track-reload! dispatch-data namespace)
      (cond-> (result/result-map
               {:path path
                :status (if existed? :ok :created)
                :loc-before loc-before
                :loc-after loc-after
                :threshold threshold
                :namespace namespace
                :formatted? formatted?})
        format-error (assoc :format-error format-error)))))

(defn make-insert-handler
  "Create handler for ::phandaal/insert effect.
   Inserts content at specified location."
  [opts]
  (fn [{:keys [dispatch-data]} _system {:keys [path content at threshold]}]
    (let [existed? (file-exists? path)
          _ (when-not existed?
              (throw (ex-info "Cannot insert into non-existent file" {:path path})))
          loc-before (pio/count-lines path)
          lines (vec (str/split-lines (slurp path)))
          insert-idx (cond
                       (:line at)
                       (dec (:line at))

                       (:after at)
                       (let [pattern (if (string? (:after at))
                                       (re-pattern (java.util.regex.Pattern/quote (:after at)))
                                       (:after at))
                             idx (some (fn [[i line]]
                                         (when (re-find pattern line) i))
                                       (map-indexed vector lines))]
                         (if idx
                           (inc idx)
                           (throw (ex-info "Pattern not found" {:pattern (:after at) :path path}))))

                       (:before at)
                       (let [pattern (if (string? (:before at))
                                       (re-pattern (java.util.regex.Pattern/quote (:before at)))
                                       (:before at))
                             idx (some (fn [[i line]]
                                         (when (re-find pattern line) i))
                                       (map-indexed vector lines))]
                         (if idx
                           idx
                           (throw (ex-info "Pattern not found" {:pattern (:before at) :path path})))))
          content-lines (str/split-lines content)
          new-lines (concat (take insert-idx lines)
                            content-lines
                            (drop insert-idx lines))
          new-content (str (str/join "\n" new-lines) "\n")
          _ (pio/atomic-spit path new-content)
          {:keys [formatted? format-error]} (format/run-formatter! path (:formatters opts))
          loc-after (pio/count-lines path)
          namespace (infer-namespace opts path)]
      (track-reload! dispatch-data namespace)
      (cond-> (result/result-map
               {:path path
                :status :ok
                :loc-before loc-before
                :loc-after loc-after
                :threshold threshold
                :namespace namespace
                :formatted? formatted?})
        format-error (assoc :format-error format-error)))))

(defn make-replace-handler
  "Create handler for ::phandaal/replace effect.
   Find and replace within file."
  [opts]
  (fn [{:keys [dispatch-data]} _system {:keys [path find replacement all?]}]
    (let [existed? (file-exists? path)
          _ (when-not existed?
              (throw (ex-info "Cannot replace in non-existent file" {:path path})))
          loc-before (pio/count-lines path)
          current (slurp path)
          pattern (if (string? find)
                    (re-pattern (java.util.regex.Pattern/quote find))
                    find)
          new-content (if all?
                        (str/replace current pattern replacement)
                        (str/replace-first current pattern replacement))
          _ (pio/atomic-spit path new-content)
          {:keys [formatted? format-error]} (format/run-formatter! path (:formatters opts))
          loc-after (pio/count-lines path)
          namespace (infer-namespace opts path)]
      (track-reload! dispatch-data namespace)
      (cond-> (result/result-map
               {:path path
                :status :ok
                :loc-before loc-before
                :loc-after loc-after
                :namespace namespace
                :formatted? formatted?})
        format-error (assoc :format-error format-error)))))

(defn make-read-meta-handler
  "Create handler for ::phandaal/read-meta effect.
   Returns file metadata without reading full content."
  [opts]
  (fn [_ctx _system path]
    (let [f (io/file path)
          exists? (.exists f)]
      (if exists?
        {:path path
         :exists? true
         :loc (pio/count-lines path)
         :modified (java.util.Date. (.lastModified f))
         :namespace (infer-namespace opts path)}
        {:path path
         :exists? false}))))
