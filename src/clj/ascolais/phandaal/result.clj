(ns ascolais.phandaal.result
  "Result shape helpers for phandaal effects.")

(defn check-threshold
  "Check if line count exceeds threshold.
   Returns threshold map or nil if no threshold specified."
  [loc-after threshold]
  (when threshold
    {:limit threshold
     :exceeded? (> loc-after threshold)
     :remaining (- threshold loc-after)}))

(defn result-map
  "Construct a consistent result map for file effects.

   Required keys:
   - :path - absolute file path
   - :status - :ok, :created, or :error

   Optional keys:
   - :loc-before - line count before operation (nil for new files)
   - :loc-after - line count after operation
   - :threshold - threshold limit to check against
   - :namespace - inferred namespace symbol
   - :formatted? - whether formatter was run"
  [{:keys [path status loc-before loc-after threshold namespace formatted? error]}]
  (cond-> {:path path
           :status status
           :hints []}
    (or loc-before loc-after)
    (assoc :loc {:before loc-before
                 :after loc-after
                 :delta (when (and loc-before loc-after)
                          (- loc-after loc-before))})

    threshold
    (assoc :threshold (check-threshold loc-after threshold))

    namespace
    (assoc :reload {:namespaces [namespace]
                    :type :clj-reload})

    (some? formatted?)
    (assoc :formatted? formatted?)

    error
    (assoc :error error)))
