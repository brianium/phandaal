(ns ascolais.phandaal-test
  (:require [clojure.test :refer [deftest is testing]]
            [ascolais.phandaal :as phandaal]))

(deftest greet-test
  (testing "greet returns a greeting message"
    (is (= "Hello, World!" (phandaal/greet "World")))
    (is (= "Hello, Clojure!" (phandaal/greet "Clojure")))))
