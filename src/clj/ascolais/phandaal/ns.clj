(ns ascolais.phandaal.ns
  "Namespace inference from file paths."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def clojure-extensions
  "File extensions for Clojure source files."
  #{".clj" ".cljc" ".cljs"})

(defn clojure-file?
  "Returns true if path is a Clojure source file."
  [path]
  (some #(str/ends-with? path %) clojure-extensions))

(defn path->namespace
  "Infer namespace from file path given source paths.
   Returns nil if path is not under any source path or not a Clojure file.

   Example:
   (path->namespace \"/project/src/clj/app/core.clj\"
                    \"/project\"
                    [\"src/clj\"])
   => app.core"
  [path project-root source-paths]
  (when (clojure-file? path)
    (let [abs-path (.getCanonicalPath (io/file path))
          root (.getCanonicalPath (io/file project-root))]
      (some (fn [src-path]
              (let [full-src (str root "/" src-path "/")
                    full-src-canonical (.getCanonicalPath (io/file full-src))]
                (when (str/starts-with? abs-path (str full-src-canonical "/"))
                  (let [rel (subs abs-path (inc (count full-src-canonical)))
                        without-ext (str/replace rel #"\.(clj[cs]?)$" "")
                        ns-str (-> without-ext
                                   (str/replace "/" ".")
                                   (str/replace "_" "-"))]
                    (symbol ns-str)))))
            source-paths))))

(defn namespace->path
  "Convert namespace symbol to relative file path.

   Example:
   (namespace->path 'app.core \"clj\")
   => \"app/core.clj\""
  [ns-sym extension]
  (-> (str ns-sym)
      (str/replace "." "/")
      (str/replace "-" "_")
      (str extension)))
