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

(deftest short-display-text-normalizes-and-truncates-test
  (is (= "hello world"
         (message-text/short-display-text " hello\n\tworld  ")))
  (is (= "abcd…"
         (message-text/short-display-text "abcdef" 5))))

(deftest last-user-message-text-picks-most-recent-user-message-test
  (is (= "latest user text"
         (message-text/last-user-message-text
          [{:role "user" :content [{:type :text :text "first"}]}
           {:role "assistant" :content [{:type :text :text "reply"}]}
           {:role "user" :content [{:type :text :text "latest  user\ntext"}]}]))))

(deftest session-display-name-prefers-explicit-name-over-last-user-message-test
  (is (= "Named"
         (message-text/session-display-name
          "Named"
          [{:role "user" :content [{:type :text :text "ignored"}]}])))
  (is (= "latest"
         (message-text/session-display-name
          nil
          [{:role "user" :content [{:type :text :text "latest"}]}])))
  (is (nil? (message-text/session-display-name nil []))))

(deftest session-display-name-derives-from-canonical-journal-message-shapes-test
  (is (= "Investigate failing tests"
         (message-text/session-display-name
          nil
          [{:role "user" :content [{:type :text :text "Investigate failing tests"}]}
           {:role "assistant" :content [{:type :text :text "ok"}]}])))
  (is (= "string content also works"
         (message-text/session-display-name
          nil
          [{:role "user" :content "string content also works"}]))))
