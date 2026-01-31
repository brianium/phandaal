(ns ascolais.phandaal.format
  "Formatter helpers for phandaal effects."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn sh
  "Create formatter from shell command. Use {path} as placeholder.

   Example:
   (sh \"cljfmt fix {path}\")

   Returns a function that takes a path and runs the command."
  [command-template]
  (fn [path]
    (let [cmd (str/replace command-template "{path}" path)
          {:keys [exit err]} (shell/sh "sh" "-c" cmd)]
      (when-not (zero? exit)
        (throw (ex-info "Formatter failed" {:cmd cmd :exit exit :err err}))))))

(defn get-extension
  "Get file extension including the dot, e.g. \".clj\""
  [path]
  (let [name (.getName (java.io.File. path))
        idx (.lastIndexOf name ".")]
    (when (pos? idx)
      (subs name idx))))

(defn run-formatter!
  "Run formatter for file if one is configured for its extension.
   Returns {:formatted? bool :error error-if-any}"
  [path formatters]
  (if-let [formatter (get formatters (get-extension path))]
    (try
      (formatter path)
      {:formatted? true}
      (catch Exception e
        (tap> {:phandaal/format-error {:path path :error (ex-message e)}})
        {:formatted? false :format-error (ex-data e)}))
    {:formatted? nil}))
