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

(deftest sanitize-session-entries-test
  (testing "extracts visible lines from journal-style session entries"
    (is (= ["User: Fix footer"
            "Assistant: Inspecting selector path"]
           (#'sut/sanitize-session-entries
            [{:psi.session-entry/kind :message
              :psi.session-entry/data {:message {:role "user"
                                                 :content [{:type :text :text "Fix footer"}]}}}
             {:psi.session-entry/kind :message
              :psi.session-entry/data {:message {:role "assistant"
                                                 :content [{:type :text :text "Inspecting selector path"}]}}}
             {:psi.session-entry/kind :other
              :psi.session-entry/data {:ignored true}}])))))

(deftest build-rename-prompt-test
  (testing "builds minimal helper prompt with bounded conversation text"
    (let [{:keys [system-prompt user-prompt]}
          (#'sut/build-rename-prompt ["User: Fix footer"
                                      "Assistant: I will inspect the selector path."])]
      (is (string? system-prompt))
      (is (.contains system-prompt "Return title text only"))
      (is (.contains user-prompt "Conversation excerpt:"))
      (is (.contains user-prompt "User: Fix footer"))
      (is (.contains user-prompt "Assistant: I will inspect the selector path."))))

  (testing "truncates long conversation text to trailing 4000 chars"
    (let [long-line (apply str (repeat 4500 "a"))
          {:keys [user-prompt]} (#'sut/build-rename-prompt [(str "User: " long-line)])
          excerpt (subs user-prompt (inc (.indexOf user-prompt "\n\n")))]
      (is (<= (count excerpt) (+ 2 4000)))
      (is (.endsWith user-prompt (apply str (repeat 4000 "a"))))))

  (testing "returns nil when nothing remains after sanitization/truncation"
    (is (nil? (#'sut/build-rename-prompt [])))))

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
