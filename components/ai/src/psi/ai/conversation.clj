(ns psi.ai.conversation
  "Conversation entity and lifecycle management"
  (:require [psi.ai.schemas :as schemas])
  (:import [java.util UUID]
           [java.time Instant]))

;; Conversation state management

(defn create
  "Create new conversation with optional system prompt"
  [system-prompt]
  (let [conversation {:id (str (UUID/randomUUID))
                      :system-prompt system-prompt
                      :status :active
                      :created-at (Instant/now)
                      :updated-at (Instant/now)
                      :messages []
                      :tools #{}}]
    (schemas/validate! schemas/Conversation conversation)))

(defn add-user-message
  "Add user message to conversation"
  [conversation content]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (string? content)]}
  (let [updated-conversation
        (-> conversation
            (update :messages conj {:id (str (UUID/randomUUID))
                                    :role :user
                                    :content {:kind :text
                                              :text content}
                                    :timestamp (Instant/now)})
            (assoc :updated-at (Instant/now)))]
    (schemas/validate! schemas/Conversation updated-conversation)))

(defn add-assistant-message
  "Add assistant message to conversation"
  [conversation message-data]
  (let [message (merge {:id (str (UUID/randomUUID))
                        :role :assistant
                        :timestamp (Instant/now)}
                       message-data)]
    (-> conversation
        (update :messages conj message)
        (assoc :updated-at (Instant/now)))))

(defn add-tool-result
  "Add tool result message to conversation"
  [conversation tool-call-id tool-name content is-error]
  (-> conversation
      (update :messages conj {:id (str (UUID/randomUUID))
                              :role :tool-result
                              :tool-call-id tool-call-id
                              :tool-name tool-name
                              :content content
                              :is-error is-error
                              :timestamp (Instant/now)})
      (assoc :updated-at (Instant/now))))

(defn add-tool
  "Add tool to conversation"
  [conversation tool]
  (update conversation :tools conj tool))

(defn complete-conversation
  "Mark conversation as completed"
  [conversation]
  (assoc conversation
         :status :completed
         :updated-at (Instant/now)))

(defn error-conversation
  "Mark conversation as errored"
  [conversation error-msg]
  (assoc conversation
         :status :error
         :error-message error-msg
         :updated-at (Instant/now)))

;; Derived data

(defn total-usage
  "Calculate total token usage across all messages"
  [conversation]
  (let [messages (:messages conversation)
        assistant-messages (filter #(= :assistant (:role %)) messages)]
    (reduce (fn [acc msg]
              (let [usage (:usage msg)]
                (when usage
                  (-> acc
                      (update :input-tokens + (:input-tokens usage 0))
                      (update :output-tokens + (:output-tokens usage 0))
                      (update :cache-read-tokens + (:cache-read-tokens usage 0))
                      (update :cache-write-tokens + (:cache-write-tokens usage 0))
                      (update :total-tokens + (:total-tokens usage 0))))))
            {:input-tokens 0
             :output-tokens 0
             :cache-read-tokens 0
             :cache-write-tokens 0
             :total-tokens 0}
            assistant-messages)))

(defn total-cost
  "Calculate total cost across all messages"
  [conversation]
  (let [messages (:messages conversation)
        assistant-messages (filter #(= :assistant (:role %)) messages)]
    (reduce (fn [acc msg]
              (let [usage (:usage msg)
                    cost (:cost usage)]
                (when cost
                  (-> acc
                      (update :input + (:input cost 0))
                      (update :output + (:output cost 0))
                      (update :cache-read + (:cache-read cost 0))
                      (update :cache-write + (:cache-write cost 0))
                      (update :total + (:total cost 0))))))
            {:input 0.0
             :output 0.0
             :cache-read 0.0
             :cache-write 0.0
             :total 0.0}
            assistant-messages)))

(defn has-tools?
  "Check if conversation has tools available"
  [conversation]
  (seq (:tools conversation)))

(defn requires-tool-response?
  "Check if conversation has pending tool calls"
  [conversation]
  (some (fn [msg]
          (and (= :assistant (:role msg))
               (= :tool-use (:stop-reason msg))))
        (:messages conversation)))

(defn active?
  "Check if conversation is active"
  [conversation]
  (= :active (:status conversation)))