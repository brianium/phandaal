(ns ascolais.phandaal.reload-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ascolais.phandaal :as phandaal]
            [ascolais.phandaal.reload :as reload]
            [ascolais.sandestin :as s]
            [clojure.java.io :as io]))

(def test-root "/tmp/phandaal-reload-test")

(defn clean-test-dir [f]
  (let [dir (io/file test-root)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file))))
  (.mkdirs (io/file test-root "src"))
  (f))

(use-fixtures :each clean-test-dir)

;; =============================================================================
;; Executor Preset Tests
;; =============================================================================

(deftest noop-executor-test
  (testing "noop executor skips all namespaces"
    (let [result (reload/execute reload/noop #{'foo.bar 'baz.qux})]
      (is (= #{} (:reloaded result)))
      (is (= {} (:failed result)))
      (is (= #{'foo.bar 'baz.qux} (:skipped result))))))

(deftest require-reload-executor-test
  (testing "require-reload handles non-existent namespace"
    (let [result (reload/execute reload/require-reload #{'nonexistent.namespace.xyz})]
      (is (= #{} (:reloaded result)))
      (is (= 1 (count (:failed result))))
      (is (contains? (:failed result) 'nonexistent.namespace.xyz)))))

(deftest execute-with-no-reload-fn-test
  (testing "execute handles executor without :reload-fn"
    (let [result (reload/execute {:type :broken} #{'foo.bar})]
      (is (= #{} (:reloaded result)))
      (is (contains? (:failed result) :executor))
      (is (= #{'foo.bar} (:skipped result))))))

;; =============================================================================
;; Dispatch with Interceptor
;; =============================================================================

(def test-dispatch
  (s/create-dispatch
   [[phandaal/registry {:project-root test-root
                        :source-paths ["src"]
                        :reload-executor reload/noop}]
    {::s/interceptors [phandaal/pending-reloads-interceptor]}]))

(def test-dispatch-no-executor
  (s/create-dispatch
   [[phandaal/registry {:project-root test-root
                        :source-paths ["src"]}]
    {::s/interceptors [phandaal/pending-reloads-interceptor]}]))

;; =============================================================================
;; Pending Reload Tracking Tests
;; =============================================================================

(deftest pending-reloads-tracked-test
  (testing "file effects include reload info in result"
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/write
                                 {:path (str test-root "/src/app/core.clj")
                                  :content "(ns app.core)\n"
                                  :create-dirs? true}]])]
      (is (= 1 (count results)))
      (is (= 'app.core (get-in results [0 :res :reload :namespaces 0]))))))

;; =============================================================================
;; Reload Effect Tests (in same dispatch)
;; =============================================================================

(deftest reload-effect-with-noop-executor-test
  (testing "reload effect uses noop executor"
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/write
                                 {:path (str test-root "/src/app/core.clj")
                                  :content "(ns app.core)\n"
                                  :create-dirs? true}]
                                [::phandaal/reload {}]])]
      (is (= 2 (count results)))
      (let [reload-res (get-in results [1 :res])]
        (is (= :noop (:executor reload-res)))
        (is (= #{'app.core} (:requested reload-res)))
        (is (= #{} (:reloaded reload-res)))
        (is (= #{'app.core} (:skipped reload-res)))))))

(deftest reload-effect-with-only-filter-test
  (testing "reload effect respects :only filter"
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/write
                                 {:path (str test-root "/src/app/core.clj")
                                  :content "(ns app.core)\n"
                                  :create-dirs? true}]
                                [::phandaal/write
                                 {:path (str test-root "/src/app/routes.clj")
                                  :content "(ns app.routes)\n"
                                  :create-dirs? true}]
                                [::phandaal/reload {:only #{'app.core}}]])]
      (is (= 3 (count results)))
      (let [reload-res (get-in results [2 :res])]
        (is (= #{'app.core} (:requested reload-res)))
        (is (contains? (:pending-remaining reload-res) 'app.routes))))))

(deftest reload-effect-without-executor-test
  (testing "reload effect returns error when no executor configured"
    (let [{:keys [results]}
          (test-dispatch-no-executor {} {} [[::phandaal/write
                                             {:path (str test-root "/src/app/core.clj")
                                              :content "(ns app.core)\n"
                                              :create-dirs? true}]
                                            [::phandaal/reload {}]])]
      (is (= 2 (count results)))
      (is (= :no-executor (get-in results [1 :res :error]))))))

(deftest reload-effect-empty-pending-test
  (testing "reload effect handles empty pending set"
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/reload {}]])]
      (is (= #{} (get-in results [0 :res :requested])))
      (is (= #{} (get-in results [0 :res :reloaded]))))))

;; =============================================================================
;; Clear Pending Effect Tests
;; =============================================================================

(deftest clear-pending-all-test
  (testing "clear-pending with :all clears everything"
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/write
                                 {:path (str test-root "/src/app/core.clj")
                                  :content "(ns app.core)\n"
                                  :create-dirs? true}]
                                [::phandaal/write
                                 {:path (str test-root "/src/app/routes.clj")
                                  :content "(ns app.routes)\n"
                                  :create-dirs? true}]
                                [::phandaal/clear-pending {:all true}]])]
      (is (= 3 (count results)))
      (let [clear-res (get-in results [2 :res])]
        (is (= #{'app.core 'app.routes} (:cleared clear-res)))
        (is (= #{} (:remaining clear-res)))))))

(deftest clear-pending-only-test
  (testing "clear-pending with :only clears specific namespaces"
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/write
                                 {:path (str test-root "/src/app/core.clj")
                                  :content "(ns app.core)\n"
                                  :create-dirs? true}]
                                [::phandaal/write
                                 {:path (str test-root "/src/app/routes.clj")
                                  :content "(ns app.routes)\n"
                                  :create-dirs? true}]
                                [::phandaal/clear-pending {:only #{'app.core}}]])]
      (is (= 3 (count results)))
      (let [clear-res (get-in results [2 :res])]
        (is (= #{'app.core} (:cleared clear-res)))
        (is (= #{'app.routes} (:remaining clear-res)))))))

(deftest clear-pending-no-args-test
  (testing "clear-pending with no args does nothing"
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/write
                                 {:path (str test-root "/src/app/core.clj")
                                  :content "(ns app.core)\n"
                                  :create-dirs? true}]
                                [::phandaal/clear-pending {}]])]
      (is (= 2 (count results)))
      (let [clear-res (get-in results [1 :res])]
        (is (= #{} (:cleared clear-res)))
        (is (= #{'app.core} (:remaining clear-res)))))))

;; =============================================================================
;; Multiple Effects Accumulate Test
;; =============================================================================

(deftest multiple-effects-accumulate-pending-test
  (testing "multiple file effects accumulate pending reloads"
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/write
                                 {:path (str test-root "/src/app/core.clj")
                                  :content "(ns app.core)\n"
                                  :create-dirs? true}]
                                [::phandaal/write
                                 {:path (str test-root "/src/app/routes.clj")
                                  :content "(ns app.routes)\n"
                                  :create-dirs? true}]
                                [::phandaal/reload {}]])]
      (is (= 3 (count results)))
      (let [reload-res (get-in results [2 :res])]
        (is (= #{'app.core 'app.routes} (:requested reload-res)))))))
