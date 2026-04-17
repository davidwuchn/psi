(ns extensions.auto-session-name-pure-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.auto-session-name :as sut]))

(deftest sanitize-message-history-test
  (testing "keeps visible user and assistant text while excluding commands, tool results, and custom-type messages"
    (let [messages [{:role "user" :content [{:type :text :text "/tree"}]}
                    {:role "user" :content [{:type :text :text "Investigate prompt lifecycle naming"}]}
                    {:role "assistant" :content [{:type :text :text "We should infer a better title."}]}
                    {:role "toolResult" :content [{:type :text :text "ignored tool output"}]}
                    {:role "assistant" :custom-type "background-job-terminal"
                     :content [{:type :text :text "ignored status"}]}]]
      (is (= ["User: Investigate prompt lifecycle naming"
              "Assistant: We should infer a better title."]
             (#'sut/sanitize-message-history messages)))))

  (testing "returns empty vector when nothing visible remains"
    (is (= []
           (#'sut/sanitize-message-history
            [{:role "user" :content [{:type :text :text "/status"}]}
             {:role "assistant" :custom-type "x" :content [{:type :text :text "hidden"}]}])))))

(deftest build-rename-prompt-test
  (testing "embeds sanitized conversation lines in terse-purpose prompt"
    (let [prompt (#'sut/build-rename-prompt ["User: Fix footer"
                                             "Assistant: I will inspect the selector path."])]
      (is (string? prompt))
      (is (.contains prompt "current purpose of the session"))
      (is (.contains prompt "User: Fix footer"))
      (is (.contains prompt "Assistant: I will inspect the selector path.")))))

(deftest title-validation-test
  (testing "normalizes quoted titles"
    (is (= "Fix footer rendering"
           (#'sut/normalize-title "  \"Fix footer rendering\"  "))))

  (testing "accepts compact single-line titles"
    (is (true? (#'sut/valid-title? "Fix footer rendering"))))

  (testing "rejects blank or oversized titles"
    (is (false? (#'sut/valid-title? nil)))
    (is (false? (#'sut/valid-title? "")))
    (is (false? (#'sut/valid-title? (apply str (repeat 61 "a"))))))

  (testing "rejects multiline titles"
    (is (false? (#'sut/valid-title? "Fix footer\nrendering")))))
