(ns psi.ai.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [psi.ai.core :as core]
            [psi.ai.models :as models]
            [psi.ai.schemas :as schemas]))

(deftest test-conversation-creation
  (testing "Creating a conversation with system prompt"
    (let [conversation (core/create-conversation "You are a helpful assistant")]
      (is (schemas/valid? schemas/Conversation conversation))
      (is (= "You are a helpful assistant" (:system-prompt conversation)))
      (is (= :active (:status conversation)))
      (is (empty? (:messages conversation)))
      (is (empty? (:tools conversation)))))
  
  (testing "Creating a conversation without system prompt"
    (let [conversation (core/create-conversation)]
      (is (schemas/valid? schemas/Conversation conversation))
      (is (nil? (:system-prompt conversation)))
      (is (= :active (:status conversation))))))

(deftest test-message-handling
  (testing "Adding user message"
    (let [conversation (core/create-conversation "Test assistant")
          updated (core/send-message conversation "Hello!")]
      (is (schemas/valid? schemas/Conversation updated))
      (is (= 1 (count (:messages updated))))
      (let [message (first (:messages updated))]
        (is (= :user (:role message)))
        (is (= "Hello!" (get-in message [:content :text])))
        (is (= :text (get-in message [:content :kind])))))))

(deftest test-model-validation
  (testing "Claude model validation"
    (let [model (models/get-model :claude-3-5-sonnet)]
      (is (schemas/valid? schemas/Model model))
      (is (= :anthropic (:provider model)))
      (is (= :anthropic-messages (:api model)))))
  
  (testing "OpenAI model validation" 
    (let [model (models/get-model :gpt-4o)]
      (is (schemas/valid? schemas/Model model))
      (is (= :openai (:provider model)))
      (is (= :openai-completions (:api model))))))

(deftest test-stream-options-validation
  (testing "Valid stream options"
    (let [options {:temperature 0.7
                   :max-tokens 1000
                   :cache-retention :short}]
      (is (schemas/valid? schemas/StreamOptions options))))
  
  (testing "Invalid stream options"
    (let [options {:temperature 3.0  ;; Too high
                   :max-tokens -1}]  ;; Negative
      (is (not (schemas/valid? schemas/StreamOptions options))))))

(deftest test-usage-calculation
  (testing "Total usage calculation"
    (let [conversation (-> (core/create-conversation)
                          (core/send-message "Test"))]
      ;; Add mock assistant message with usage
      (let [updated (update conversation :messages conj
                           {:id "test-id"
                            :role :assistant
                            :content {:kind :text :text "Response"}
                            :timestamp (java.time.Instant/now)
                            :usage {:input-tokens 10
                                   :output-tokens 20
                                   :total-tokens 30
                                   :cost {:input 0.01 :output 0.02 :total 0.03}}})]
        (is (= 30 (:total-tokens (core/get-conversation-usage updated))))
        (is (= 0.03 (:total (core/get-conversation-cost updated))))))))