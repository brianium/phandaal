(ns ascolais.phandaal-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ascolais.phandaal :as phandaal]
            [ascolais.phandaal.io :as pio]
            [ascolais.phandaal.ns :as pns]
            [ascolais.phandaal.result :as result]
            [ascolais.sandestin :as s]
            [clojure.java.io :as io]))

(def test-root "/tmp/phandaal-test")

(defn clean-test-dir [f]
  (let [dir (io/file test-root)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file))))
  (.mkdirs (io/file test-root "src"))
  (f))

(use-fixtures :each clean-test-dir)

;; =============================================================================
;; IO Utilities Tests
;; =============================================================================

(deftest count-lines-test
  (testing "counts lines in existing file"
    (spit (str test-root "/test.txt") "line 1\nline 2\nline 3\n")
    (is (= 3 (pio/count-lines (str test-root "/test.txt")))))

  (testing "returns 0 for non-existent file"
    (is (= 0 (pio/count-lines (str test-root "/nonexistent.txt"))))))

(deftest atomic-spit-test
  (testing "writes file atomically"
    (pio/atomic-spit (str test-root "/atomic.txt") "content")
    (is (= "content" (slurp (str test-root "/atomic.txt")))))

  (testing "creates parent directories when requested"
    (pio/atomic-spit (str test-root "/nested/dir/file.txt") "nested" :create-dirs? true)
    (is (= "nested" (slurp (str test-root "/nested/dir/file.txt"))))))

;; =============================================================================
;; Namespace Inference Tests
;; =============================================================================

(deftest clojure-file-test
  (testing "identifies Clojure files"
    (is (pns/clojure-file? "foo.clj"))
    (is (pns/clojure-file? "bar.cljc"))
    (is (pns/clojure-file? "baz.cljs")))

  (testing "rejects non-Clojure files"
    (is (not (pns/clojure-file? "foo.js")))
    (is (not (pns/clojure-file? "bar.txt")))))

(deftest path->namespace-test
  (testing "infers namespace from path"
    (is (= 'app.core
           (pns/path->namespace (str test-root "/src/app/core.clj")
                                test-root
                                ["src"]))))

  (testing "handles underscores in path"
    (is (= 'my-app.some-ns
           (pns/path->namespace (str test-root "/src/my_app/some_ns.clj")
                                test-root
                                ["src"]))))

  (testing "returns nil for non-Clojure files"
    (is (nil? (pns/path->namespace (str test-root "/src/app/core.js")
                                   test-root
                                   ["src"])))))

;; =============================================================================
;; Result Helpers Tests
;; =============================================================================

(deftest check-threshold-test
  (testing "detects threshold not exceeded"
    (is (= {:limit 100 :exceeded? false :remaining 50}
           (result/check-threshold 50 100))))

  (testing "detects threshold exceeded"
    (is (= {:limit 100 :exceeded? true :remaining -50}
           (result/check-threshold 150 100))))

  (testing "returns nil when no threshold"
    (is (nil? (result/check-threshold 50 nil)))))

(deftest result-map-test
  (testing "builds complete result map"
    (let [res (result/result-map {:path "/test/file.clj"
                                  :status :ok
                                  :loc-before 10
                                  :loc-after 15
                                  :threshold 100
                                  :namespace 'test.ns
                                  :formatted? true})]
      (is (= "/test/file.clj" (:path res)))
      (is (= :ok (:status res)))
      (is (= [] (:hints res)))
      (is (= {:before 10 :after 15 :delta 5} (:loc res)))
      (is (= {:limit 100 :exceeded? false :remaining 85} (:threshold res)))
      (is (= {:namespaces ['test.ns] :type :clj-reload} (:reload res)))
      (is (true? (:formatted? res))))))

;; =============================================================================
;; Effect Integration Tests
;; =============================================================================

(def test-dispatch
  (s/create-dispatch
   [[phandaal/registry {:project-root test-root
                        :source-paths ["src"]}]]))

(deftest write-effect-test
  (testing "creates new file"
    (let [{:keys [results errors]}
          (test-dispatch {} {} [[::phandaal/write
                                 {:path (str test-root "/new.txt")
                                  :content "hello\n"}]])]
      (is (empty? errors))
      (is (= 1 (count results)))
      (is (= :created (get-in results [0 :res :status])))
      (is (= {:before nil :after 1 :delta nil}
             (get-in results [0 :res :loc])))))

  (testing "overwrites existing file"
    (spit (str test-root "/existing.txt") "old\n")
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/write
                                 {:path (str test-root "/existing.txt")
                                  :content "new\n"}]])]
      (is (= :ok (get-in results [0 :res :status])))
      (is (= {:before 1 :after 1 :delta 0}
             (get-in results [0 :res :loc])))))

  (testing "detects threshold exceeded"
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/write
                                 {:path (str test-root "/big.txt")
                                  :content "1\n2\n3\n"
                                  :threshold 2}]])]
      (is (true? (get-in results [0 :res :threshold :exceeded?])))))

  (testing "infers namespace for Clojure files"
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/write
                                 {:path (str test-root "/src/app/core.clj")
                                  :content "(ns app.core)\n"
                                  :create-dirs? true}]])]
      (is (= ['app.core] (get-in results [0 :res :reload :namespaces]))))))

(deftest append-effect-test
  (testing "appends to existing file"
    (spit (str test-root "/append.txt") "line 1\n")
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/append
                                 {:path (str test-root "/append.txt")
                                  :content "line 2\n"}]])]
      (is (= :ok (get-in results [0 :res :status])))
      (is (= {:before 1 :after 2 :delta 1}
             (get-in results [0 :res :loc])))))

  (testing "creates file if it doesn't exist"
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/append
                                 {:path (str test-root "/new-append.txt")
                                  :content "first line\n"}]])]
      (is (= :created (get-in results [0 :res :status]))))))

(deftest insert-effect-test
  (testing "inserts at line number"
    (spit (str test-root "/insert.txt") "line 1\nline 3\n")
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/insert
                                 {:path (str test-root "/insert.txt")
                                  :content "line 2"
                                  :at {:line 2}}]])]
      (is (= :ok (get-in results [0 :res :status])))
      (is (= "line 1\nline 2\nline 3\n" (slurp (str test-root "/insert.txt"))))))

  (testing "inserts after pattern"
    (spit (str test-root "/insert-after.txt") "(ns foo)\n(defn bar [])\n")
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/insert
                                 {:path (str test-root "/insert-after.txt")
                                  :content "(require '[clojure.string :as str])"
                                  :at {:after "(ns foo)"}}]])]
      (is (= :ok (get-in results [0 :res :status])))))

  (testing "errors on non-existent file"
    (let [{:keys [errors]}
          (test-dispatch {} {} [[::phandaal/insert
                                 {:path (str test-root "/nonexistent.txt")
                                  :content "text"
                                  :at {:line 1}}]])]
      (is (= 1 (count errors))))))

(deftest replace-effect-test
  (testing "replaces first occurrence"
    (spit (str test-root "/replace.txt") "foo bar foo\n")
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/replace
                                 {:path (str test-root "/replace.txt")
                                  :find "foo"
                                  :replacement "baz"}]])]
      (is (= :ok (get-in results [0 :res :status])))
      (is (= "baz bar foo\n" (slurp (str test-root "/replace.txt"))))))

  (testing "replaces all occurrences when all? is true"
    (spit (str test-root "/replace-all.txt") "foo bar foo\n")
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/replace
                                 {:path (str test-root "/replace-all.txt")
                                  :find "foo"
                                  :replacement "baz"
                                  :all? true}]])]
      (is (= "baz bar baz\n" (slurp (str test-root "/replace-all.txt")))))))

(deftest read-meta-effect-test
  (testing "returns metadata for existing file"
    (spit (str test-root "/meta.txt") "line 1\nline 2\n")
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/read-meta (str test-root "/meta.txt")]])]
      (is (true? (get-in results [0 :res :exists?])))
      (is (= 2 (get-in results [0 :res :loc])))))

  (testing "returns exists? false for missing file"
    (let [{:keys [results]}
          (test-dispatch {} {} [[::phandaal/read-meta (str test-root "/missing.txt")]])]
      (is (false? (get-in results [0 :res :exists?]))))))
