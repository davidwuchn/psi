(ns psi.ai.core
  "Core AI provider implementation following allium behavioral spec"
  (:require [psi.ai.conversation :as conversation]
            [psi.ai.streaming :as streaming]
            [psi.ai.models :as models]
            [psi.ai.schemas :as schemas]
            [psi.ai.providers.anthropic :as anthropic]
            [psi.ai.providers.openai :as openai]))

;; Provider registry
(defonce ^:private provider-registry
  (atom {:anthropic anthropic/provider
         :openai openai/provider}))

;; Public API

(defn create-conversation
  "Create a new conversation with optional system prompt"
  ([system-prompt]
   (conversation/create system-prompt))
  ([]
   (conversation/create nil)))

(defn send-message
  "Add a user message to conversation"
  [conversation content]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (string? content)]}
  (conversation/add-user-message conversation content))

(defn stream-response
  "Stream assistant response using specified model and options"
  [conversation model options]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (schemas/valid? schemas/Model model)
         (schemas/valid? schemas/StreamOptions options)]}
  (let [provider-impl (get @provider-registry (:provider model))]
    (when-not provider-impl
      (throw (ex-info "Unknown provider" {:provider (:provider model)})))
    (streaming/stream-response provider-impl conversation model options)))

(defn add-tool
  "Add a tool to conversation"
  [conversation tool]
  (conversation/add-tool conversation tool))

(defn get-conversation-cost
  "Get total cost for conversation"
  [conversation]
  (conversation/total-cost conversation))

(defn get-conversation-usage
  "Get total token usage for conversation"
  [conversation]
  (conversation/total-usage conversation))

(defn list-models
  "List available models for provider"
  [provider]
  (models/list-for-provider provider))

;; Provider registration

(defn register-provider!
  "Register a new provider implementation"
  [provider-key provider-impl]
  (swap! provider-registry assoc provider-key provider-impl))

(defn get-provider
  "Get provider implementation"
  [provider-key]
  (get @provider-registry provider-key))