(ns ascolais.phandaal.format-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ascolais.phandaal.format :as format]
            [ascolais.phandaal :as phandaal]
            [ascolais.sandestin :as s]
            [cljfmt.core :as cljfmt]
            [clojure.java.io :as io]))

(def test-root "/tmp/phandaal-format-test")

(defn clean-test-dir [f]
  (let [dir (io/file test-root)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file))))
  (.mkdirs (io/file test-root))
  (f))

(use-fixtures :each clean-test-dir)

;; =============================================================================
;; sh Helper Tests
;; =============================================================================

(deftest sh-helper-test
  (testing "sh creates formatter from command template"
    (let [path (str test-root "/sh-test.txt")
          ;; Use tr to uppercase content as a simple transformation
          formatter (format/sh "cat {path} | tr 'a-z' 'A-Z' > {path}.tmp && mv {path}.tmp {path}")]
      (spit path "hello world")
      (formatter path)
      (is (= "HELLO WORLD" (slurp path)))))

  (testing "sh throws on non-zero exit"
    (let [formatter (format/sh "exit 1")]
      (is (thrown? clojure.lang.ExceptionInfo
                   (formatter "/nonexistent/path"))))))

;; =============================================================================
;; run-formatter! Tests
;; =============================================================================

(deftest run-formatter!-test
  (testing "runs formatter for matching extension"
    (let [path (str test-root "/test.clj")
          called (atom false)
          formatters {".clj" (fn [p]
                               (reset! called true)
                               (is (= path p)))}]
      (spit path "content")
      (let [result (format/run-formatter! path formatters)]
        (is (true? @called))
        (is (true? (:formatted? result))))))

  (testing "returns nil formatted? when no formatter configured"
    (let [path (str test-root "/test.txt")
          formatters {".clj" (fn [_] nil)}]
      (spit path "content")
      (let [result (format/run-formatter! path formatters)]
        (is (nil? (:formatted? result))))))

  (testing "returns false and error on formatter failure"
    (let [path (str test-root "/fail.clj")
          formatters {".clj" (fn [_] (throw (ex-info "Format failed" {:reason :test})))}]
      (spit path "content")
      (let [result (format/run-formatter! path formatters)]
        (is (false? (:formatted? result)))
        (is (some? (:format-error result)))))))

;; =============================================================================
;; cljfmt Integration Tests
;; =============================================================================

(defn cljfmt-formatter
  "Formatter using cljfmt library directly."
  [path]
  (let [content (slurp path)
        formatted (cljfmt/reformat-string content)]
    (spit path formatted)))

(deftest cljfmt-formatter-test
  (testing "cljfmt reformats poorly formatted code"
    (let [path (str test-root "/poorly-formatted.clj")
          ugly-code "(defn foo[x y](+ x y))"
          expected "(defn foo [x y] (+ x y))"]
      (spit path ugly-code)
      (cljfmt-formatter path)
      (is (= expected (slurp path)))))

  (testing "cljfmt fixes indentation"
    (let [path (str test-root "/bad-indent.clj")
          ugly-code "(defn bar []\n(let [x 1]\nx))"]
      (spit path ugly-code)
      (cljfmt-formatter path)
      ;; Just verify it changed and contains proper structure
      (is (not= ugly-code (slurp path)))
      (is (clojure.string/includes? (slurp path) "(defn bar []")))))

;; =============================================================================
;; Effect Integration with Formatter
;; =============================================================================

(deftest effect-with-formatter-test
  (let [dispatch (s/create-dispatch
                  [[phandaal/registry
                    {:project-root test-root
                     :source-paths ["src"]
                     :formatters {".clj" cljfmt-formatter}}]])]

    (testing "write effect runs formatter and reports formatted? true"
      (let [path (str test-root "/write-fmt.clj")
            ugly-code "(defn foo[x](+ x 1))"
            {:keys [results errors]} (dispatch {} {} [[::phandaal/write
                                                       {:path path
                                                        :content ugly-code}]])]
        (is (empty? errors))
        (is (true? (get-in results [0 :res :formatted?])))
        ;; Content should be formatted
        (is (= "(defn foo [x] (+ x 1))" (slurp path)))))

    (testing "write effect LOC reflects formatted output"
      (let [path (str test-root "/loc-fmt.clj")
            ;; This ugly code becomes multi-line when formatted
            ugly-code "(defn foo [x] (let [y 1] (+ x y)))"
            {:keys [results]} (dispatch {} {} [[::phandaal/write
                                                {:path path
                                                 :content ugly-code}]])]
        ;; LOC should reflect the formatted file, not the input
        (let [actual-lines (count (clojure.string/split-lines (slurp path)))]
          (is (= actual-lines (get-in results [0 :res :loc :after]))))))

    (testing "append effect runs formatter"
      (let [path (str test-root "/append-fmt.clj")]
        (spit path "(ns test.core)\n")
        (let [{:keys [results]} (dispatch {} {} [[::phandaal/append
                                                  {:path path
                                                   :content "(defn bar[](+ 1 2))"}]])]
          (is (true? (get-in results [0 :res :formatted?])))
          ;; Check formatting was applied
          (is (clojure.string/includes? (slurp path) "(defn bar [] (+ 1 2))")))))

    (testing "non-clj files are not formatted"
      (let [path (str test-root "/readme.txt")
            content "some text content"
            {:keys [results]} (dispatch {} {} [[::phandaal/write
                                                {:path path
                                                 :content content}]])]
        ;; No formatter for .txt, so formatted? should be nil
        (is (nil? (get-in results [0 :res :formatted?])))
        (is (= content (slurp path)))))))

(deftest formatter-failure-doesnt-fail-write-test
  (let [bad-formatter (fn [_] (throw (ex-info "Formatter crashed" {})))
        dispatch (s/create-dispatch
                  [[phandaal/registry
                    {:project-root test-root
                     :source-paths ["src"]
                     :formatters {".clj" bad-formatter}}]])]

    (testing "write succeeds even when formatter fails"
      (let [path (str test-root "/survive.clj")
            content "(defn foo [] 42)"
            {:keys [results errors]} (dispatch {} {} [[::phandaal/write
                                                       {:path path
                                                        :content content}]])]
        ;; No dispatch errors
        (is (empty? errors))
        ;; File was written
        (is (= content (slurp path)))
        ;; Result indicates format failure
        (is (false? (get-in results [0 :res :formatted?])))
        (is (some? (get-in results [0 :res :format-error])))))))
