(ns psi.ai.conversation
  "Conversation entity and lifecycle management"
  (:require [psi.ai.schemas :as schemas])
  (:import [java.time Instant]
           [java.util UUID]))

;; Conversation state management

(defn- now []
  (Instant/now))

(defn- new-id []
  (str (UUID/randomUUID)))

(defn- validate-conversation [conversation]
  (schemas/validate! schemas/Conversation conversation))

(defn- update-conversation [conversation f]
  (let [timestamp (now)]
    (-> (f conversation timestamp)
        (assoc :updated-at timestamp)
        validate-conversation)))

(defn- append-message [conversation message]
  (update-conversation
   conversation
   (fn [conversation timestamp]
     (update conversation :messages conj (merge {:id (new-id)
                                                 :timestamp timestamp}
                                                message)))))

(defn- normalize-system-prompt-blocks
  [system-prompt system-prompt-blocks]
  (cond
    (seq system-prompt-blocks)
    (vec system-prompt-blocks)

    (some? system-prompt)
    [{:kind :text :text system-prompt}]

    :else
    nil))

(defn create
  "Create new conversation with optional system prompt.

   Accepts either a system prompt string/nil or an options map with:
   - :system-prompt string
   - :system-prompt-blocks vector of text blocks"
  [system-prompt-or-options]
  (let [{:keys [system-prompt system-prompt-blocks]}
        (if (map? system-prompt-or-options)
          system-prompt-or-options
          {:system-prompt system-prompt-or-options})
        timestamp             (now)
        normalized-blocks     (normalize-system-prompt-blocks system-prompt system-prompt-blocks)]
    (validate-conversation
     (cond-> {:id            (new-id)
              :system-prompt system-prompt
              :status        :active
              :created-at    timestamp
              :updated-at    timestamp
              :messages      []
              :tools         #{}}
       (some? normalized-blocks) (assoc :system-prompt-blocks normalized-blocks)))))

(defn add-user-message
  "Add user message to conversation.

   CONTENT may be either:
   - string text
   - vector of canonical text blocks {:type :text :text ... [:cache-control ...]}

   The vector form preserves richer user-message metadata until provider-specific
   transformation."
  [conversation content]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (or (string? content)
             (and (vector? content)
                  (every? #(and (map? %)
                                (= :text (:type %))
                                (string? (:text %)))
                          content)))]}
  (append-message conversation
                  {:role :user
                   :content (if (string? content)
                              {:kind :text
                               :text content}
                              content)}))

(defn add-assistant-message
  "Add assistant message to conversation"
  [conversation message-data]
  (append-message conversation
                  (merge {:role :assistant}
                         message-data)))

(defn add-tool-result
  "Add tool result message to conversation"
  [conversation tool-call-id tool-name content is-error]
  (append-message conversation
                  {:role :tool-result
                   :tool-call-id tool-call-id
                   :tool-name tool-name
                   :content content
                   :is-error is-error}))

(defn add-tool
  "Add tool to conversation"
  [conversation tool]
  (update-conversation conversation (fn [conversation _]
                                      (update conversation :tools conj tool))))

;; Derived data

(defn- assistant-messages [conversation]
  (filter #(= :assistant (:role %)) (:messages conversation)))

(defn- sum-fields [maps totals keys]
  (reduce (fn [acc m]
            (reduce (fn [acc k]
                      (update acc k + (get m k 0)))
                    acc
                    keys))
          totals
          maps))

(defn total-usage
  "Calculate total token usage across all messages"
  [conversation]
  (sum-fields (keep :usage (assistant-messages conversation))
              {:input-tokens 0
               :output-tokens 0
               :cache-read-tokens 0
               :cache-write-tokens 0
               :total-tokens 0}
              [:input-tokens
               :output-tokens
               :cache-read-tokens
               :cache-write-tokens
               :total-tokens]))

(defn total-cost
  "Calculate total cost across all messages"
  [conversation]
  (sum-fields (keep (comp :cost :usage) (assistant-messages conversation))
              {:input 0.0
               :output 0.0
               :cache-read 0.0
               :cache-write 0.0
               :total 0.0}
              [:input :output :cache-read :cache-write :total]))

