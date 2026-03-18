(ns psi.agent-session.message-text-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.message-text :as message-text]))

(deftest content-text-parts-supports-canonical-and-structured-shapes-test
  (testing "canonical block vector"
    (is (= ["Hello"]
           (message-text/content-text-parts [{:type :text :text "Hello"}]))))

  (testing "structured content map"
    (is (= ["Hello from structured"]
           (message-text/content-text-parts
            {:kind :structured
             :blocks [{:kind :text :text "Hello from structured"}]}))))

  (testing "string content"
    (is (= ["raw"]
           (message-text/content-text-parts "raw")))))

(deftest content-display-text-includes-error-blocks-test
  (is (= "ok\n[error] boom"
         (message-text/content-display-text
          [{:type :text :text "ok"}
           {:type :error :text "boom"}])))
  (is (= "[error] broken"
         (message-text/content-display-text
          {:kind :structured
           :blocks [{:kind :error :text "broken"}]}))))
