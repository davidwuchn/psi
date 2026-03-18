(ns psi.ai.conversation-test
  "Tests for psi.ai.conversation."
  (:require [clojure.test :refer [deftest testing is]]
            [psi.ai.conversation :as conversation]
            [psi.ai.schemas :as schemas]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Conversation lifecycle and message handling
;; ─────────────────────────────────────────────────────────────────────────────

(deftest test-conversation-creation
  (testing "Creating a conversation with system prompt"
    (let [conversation (conversation/create "You are a helpful assistant")]
      (is (schemas/valid? schemas/Conversation conversation))
      (is (= "You are a helpful assistant" (:system-prompt conversation)))
      (is (= [{:kind :text :text "You are a helpful assistant"}]
             (:system-prompt-blocks conversation)))
      (is (= :active (:status conversation)))
      (is (empty? (:messages conversation)))
      (is (empty? (:tools conversation)))))

  (testing "Creating a conversation without system prompt"
    (let [conversation (conversation/create nil)]
      (is (schemas/valid? schemas/Conversation conversation))
      (is (nil? (:system-prompt conversation)))
      (is (nil? (:system-prompt-blocks conversation)))
      (is (= :active (:status conversation)))))

  (testing "Creating a conversation with explicit system prompt blocks"
    (let [conversation (conversation/create {:system-prompt "joined"
                                             :system-prompt-blocks [{:kind :text
                                                                     :text "base"
                                                                     :cache-control {:type :ephemeral}}]})]
      (is (schemas/valid? schemas/Conversation conversation))
      (is (= [{:kind :text
               :text "base"
               :cache-control {:type :ephemeral}}]
             (:system-prompt-blocks conversation))))))

(deftest test-message-handling
  (testing "Adding user message"
    (let [conversation (conversation/create "Test assistant")
          updated      (conversation/add-user-message conversation "Hello!")
          message      (first (:messages updated))]
      (is (schemas/valid? schemas/Conversation updated))
      (is (= 1 (count (:messages updated))))
      (is (= :user (:role message)))
      (is (= "Hello!" (get-in message [:content :text])))
      (is (= :text (get-in message [:content :kind])))
      (is (string? (:id message)))
      (is (inst? (:timestamp message)))
      (is (= (:timestamp message) (:updated-at updated)))))

  (testing "Adding canonical user text blocks normalizes to schema-valid content"
    (let [conversation (conversation/create "Test assistant")
          updated      (conversation/add-user-message
                        conversation
                        [{:type :text
                          :text "Hello"
                          :cache-control {:type :ephemeral}}])
          message      (first (:messages updated))]
      (is (schemas/valid? schemas/Conversation updated))
      (is (= :user (:role message)))
      (is (= :text (get-in message [:content :kind])))
      (is (= "Hello" (get-in message [:content :text])))
      (is (= {:type :ephemeral}
             (get-in message [:content :cache-control])))))

  (testing "Adding assistant message preserves supplied fields and adds identity"
    (let [conversation (conversation/create "Test assistant")
          updated      (conversation/add-assistant-message
                        conversation
                        {:content {:kind :text :text "Response"}
                         :stop-reason :stop
                         :usage {:input-tokens 2
                                 :output-tokens 3
                                 :total-tokens 5
                                 :cost {:input 0.01
                                        :output 0.02
                                        :cache-read 0.0
                                        :cache-write 0.0
                                        :total 0.03}}})
          message      (first (:messages updated))]
      (is (schemas/valid? schemas/Conversation updated))
      (is (= :assistant (:role message)))
      (is (= "Response" (get-in message [:content :text])))
      (is (= :stop (:stop-reason message)))
      (is (= 5 (get-in message [:usage :total-tokens])))
      (is (string? (:id message)))
      (is (inst? (:timestamp message)))
      (is (= (:timestamp message) (:updated-at updated)))))

  (testing "Adding tool result message records tool metadata"
    (let [conversation (conversation/create "Test assistant")
          updated      (conversation/add-tool-result
                        conversation
                        "call-1"
                        "bash"
                        {:kind :text :text "ok"}
                        false)
          message      (first (:messages updated))]
      (is (schemas/valid? schemas/Conversation updated))
      (is (= :tool-result (:role message)))
      (is (= "call-1" (:tool-call-id message)))
      (is (= "bash" (:tool-name message)))
      (is (= {:kind :text :text "ok"} (:content message)))
      (is (= false (:is-error message)))
      (is (string? (:id message)))
      (is (inst? (:timestamp message)))
      (is (= (:timestamp message) (:updated-at updated))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Derived data
;; ─────────────────────────────────────────────────────────────────────────────

(deftest test-tool-handling
  (testing "Adding a tool updates the tool set and updated-at"
    (let [conversation (conversation/create "Test assistant")
          before-ts    (:updated-at conversation)
          tool         {:name "echo_tool"
                        :description "Echo a value."
                        :parameters {:type "object"
                                     :properties {:value {:type "string"}}
                                     :required ["value"]}}
          updated      (conversation/add-tool conversation tool)]
      (is (schemas/valid? schemas/Conversation updated))
      (is (= #{tool} (:tools updated)))
      (is (not= before-ts (:updated-at updated))))))

(deftest test-usage-calculation
  (testing "Total usage calculation"
    (let [updated (-> (conversation/create nil)
                      (conversation/add-user-message "Test")
                      (conversation/add-assistant-message
                       {:content {:kind :text :text "Response"}
                        :usage   {:input-tokens  10
                                  :output-tokens 20
                                  :total-tokens  30
                                  :cost          {:input       0.01
                                                  :output      0.02
                                                  :cache-read  0.0
                                                  :cache-write 0.0
                                                  :total       0.03}}}))]
      (is (= 30 (:total-tokens (conversation/total-usage updated))))
      (is (= 0.03 (:total (conversation/total-cost updated))))))

  (testing "Messages without usage do not break totals"
    (let [updated (-> (conversation/create nil)
                      (conversation/add-user-message "Test")
                      (conversation/add-assistant-message
                       {:content {:kind :text :text "No usage attached"}})
                      (conversation/add-assistant-message
                       {:content {:kind :text :text "Has usage"}
                        :usage   {:input-tokens  10
                                  :output-tokens 20
                                  :total-tokens  30
                                  :cost          {:input       0.01
                                                  :output      0.02
                                                  :cache-read  0.0
                                                  :cache-write 0.0
                                                  :total       0.03}}}))]
      (is (= {:input-tokens 10
              :output-tokens 20
              :cache-read-tokens 0
              :cache-write-tokens 0
              :total-tokens 30}
             (conversation/total-usage updated)))
      (is (= {:input 0.01
              :output 0.02
              :cache-read 0.0
              :cache-write 0.0
              :total 0.03}
             (conversation/total-cost updated))))))
