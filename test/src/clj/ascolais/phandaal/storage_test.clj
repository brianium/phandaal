(ns ascolais.phandaal.storage-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ascolais.phandaal.storage :as storage]
            [ascolais.phandaal.storage.file :as file-storage]
            [clojure.java.io :as io])
  (:import [java.util UUID Date]))

(def test-root "/tmp/phandaal-storage-test")

(defn clean-test-dir [f]
  (let [dir (io/file test-root)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file))))
  (.mkdirs (io/file test-root))
  (f))

(use-fixtures :each clean-test-dir)

;; =============================================================================
;; Entry Building Tests
;; =============================================================================

(deftest truncate-content-test
  (testing "returns nil for nil input"
    (is (nil? (storage/truncate-content nil 100))))

  (testing "returns string unchanged if under limit"
    (is (= "hello" (storage/truncate-content "hello" 100))))

  (testing "truncates string with ellipsis if over limit"
    (is (= "hel... (truncated)" (storage/truncate-content "hello world" 3)))))

(deftest truncate-effect-args-test
  (testing "truncates :content field"
    (is (= {:path "/foo" :content "hel... (truncated)"}
           (storage/truncate-effect-args {:path "/foo" :content "hello world"} 3))))

  (testing "leaves args without :content unchanged"
    (is (= {:path "/foo" :other "long string here"}
           (storage/truncate-effect-args {:path "/foo" :other "long string here"} 3)))))

(deftest extract-file-path-test
  (testing "extracts from effect-args :path"
    (is (= "/foo/bar.clj" (storage/extract-file-path {:path "/foo/bar.clj"} {}))))

  (testing "falls back to result :path"
    (is (= "/result/path.clj" (storage/extract-file-path {} {:path "/result/path.clj"}))))

  (testing "handles string effect-args"
    (is (= "/string/path.clj" (storage/extract-file-path "/string/path.clj" {})))))

(deftest build-entry-test
  (testing "builds entry from context"
    (let [ctx {:effect [:ascolais.phandaal/write {:path "/test.clj" :content "code"}]
               :result {:path "/test.clj" :status :ok :hints []}}
          entry (storage/build-entry ctx {})]
      (is (uuid? (:id entry)))
      (is (inst? (:ts entry)))
      (is (= :ascolais.phandaal/write (:effect-key entry)))
      (is (= {:path "/test.clj" :content "code"} (:effect-args entry)))
      (is (= :ok (:status entry)))
      (is (= "/test.clj" (:file-path entry)))
      (is (= [] (:hints entry)))))

  (testing "includes session-id when provided"
    (let [ctx {:effect [:ascolais.phandaal/write {:path "/test.clj"}]
               :result {:status :ok}}
          entry (storage/build-entry ctx {:session-id "sess-123"})]
      (is (= "sess-123" (:session-id entry)))))

  (testing "truncates content"
    (let [ctx {:effect [:ascolais.phandaal/write {:path "/test.clj"
                                                  :content "very long content here"}]
               :result {:status :ok}}
          entry (storage/build-entry ctx {:content-limit 10})]
      (is (= "very long ... (truncated)" (get-in entry [:effect-args :content]))))))

(deftest phandaal-effect-test
  (testing "recognizes phandaal effects"
    (is (storage/phandaal-effect? :ascolais.phandaal/write))
    (is (storage/phandaal-effect? :ascolais.phandaal/append))
    (is (storage/phandaal-effect? :ascolais.phandaal/insert))
    (is (storage/phandaal-effect? :ascolais.phandaal/replace))
    (is (storage/phandaal-effect? :ascolais.phandaal/read-meta)))

  (testing "rejects non-phandaal effects"
    (is (not (storage/phandaal-effect? :other/effect)))
    (is (not (storage/phandaal-effect? :plain-keyword)))))

;; =============================================================================
;; File Storage Tests
;; =============================================================================

(deftest file-storage-create-test
  (testing "requires :path option"
    (is (thrown? clojure.lang.ExceptionInfo
                 (file-storage/create {})))))

(deftest file-storage-append-test
  (testing "appends entry to file"
    (let [storage (file-storage/create {:path (str test-root "/audit.edn")})
          entry {:id (UUID/randomUUID)
                 :ts (Date.)
                 :effect-key :ascolais.phandaal/write
                 :effect-args {:path "/test.clj"}
                 :result {:status :ok}
                 :file-path "/test.clj"
                 :hints []
                 :status :ok}
          result (storage/append! storage entry)]
      (is (= entry result))
      (is (.exists (io/file test-root "audit.edn"))))))

(deftest file-storage-query-test
  (let [storage (file-storage/create {:path (str test-root "/audit.edn")})
        now (System/currentTimeMillis)
        entries [(storage/build-entry {:effect [:ascolais.phandaal/write {:path "/a.clj"}]
                                       :result {:status :ok}} {})
                 (storage/build-entry {:effect [:ascolais.phandaal/append {:path "/b.clj"}]
                                       :result {:status :created}} {})
                 (storage/build-entry {:effect [:ascolais.phandaal/write {:path "/c.clj"}]
                                       :result {:status :error}} {})]]
    ;; Append all entries
    (doseq [e entries]
      (storage/append! storage e))

    (testing "queries all entries"
      (let [results (storage/query storage {})]
        (is (= 3 (count results)))))

    (testing "filters by effect-key"
      (let [results (storage/query storage {:effect-key :ascolais.phandaal/write})]
        (is (= 2 (count results)))))

    (testing "filters by status"
      (let [results (storage/query storage {:status :ok})]
        (is (= 1 (count results)))))

    (testing "filters by file-path prefix"
      (let [results (storage/query storage {:file-path "/a"})]
        (is (= 1 (count results)))))

    (testing "applies limit"
      (let [results (storage/query storage {:limit 2})]
        (is (= 2 (count results)))))))

(deftest file-storage-clear-test
  (let [storage (file-storage/create {:path (str test-root "/audit.edn")})]
    ;; Add some entries
    (doseq [_ (range 5)]
      (storage/append! storage
                       (storage/build-entry {:effect [:ascolais.phandaal/write {:path "/x.clj"}]
                                             :result {:status :ok}} {})))

    (testing "clears all entries"
      (let [cleared (storage/clear! storage {:all true})]
        (is (= 5 cleared))
        (is (= 0 (count (storage/query storage {}))))))

    ;; Add more entries
    (doseq [_ (range 3)]
      (storage/append! storage
                       (storage/build-entry {:effect [:ascolais.phandaal/write {:path "/y.clj"}]
                                             :result {:status :ok}} {})))

    (testing "clears entries before timestamp"
      (Thread/sleep 10)
      (let [cutoff (Date.)]
        (Thread/sleep 10)
        ;; Add one more after cutoff
        (storage/append! storage
                         (storage/build-entry {:effect [:ascolais.phandaal/write {:path "/z.clj"}]
                                               :result {:status :ok}} {}))
        (let [cleared (storage/clear! storage {:before cutoff})]
          (is (= 3 cleared))
          (is (= 1 (count (storage/query storage {})))))))))

(deftest file-storage-handles-missing-file
  (testing "returns empty results for non-existent file"
    (let [storage (file-storage/create {:path (str test-root "/nonexistent.edn")})]
      (is (= [] (storage/query storage {}))))))

(deftest file-storage-creates-parent-dirs
  (testing "creates parent directories on append"
    (let [storage (file-storage/create {:path (str test-root "/nested/deep/audit.edn")})
          entry (storage/build-entry {:effect [:ascolais.phandaal/write {:path "/test.clj"}]
                                      :result {:status :ok}} {})]
      (storage/append! storage entry)
      (is (.exists (io/file test-root "nested/deep/audit.edn"))))))
