(ns psi.ai.providers.openai
  "OpenAI provider implementation.

   Supports two API variants behind the same :openai provider key:
   - :openai-completions      → OpenAI Chat Completions API
   - :openai-codex-responses  → ChatGPT Codex Responses endpoint"
  (:require [psi.ai.providers.openai.chat-completions :as chat]
            [psi.ai.providers.openai.codex-responses :as codex]))

(def transform-messages chat/transform-messages)
(def build-request chat/build-request)
(def codex-input-messages codex/codex-input-messages)
(def codex-reasoning codex/codex-reasoning)
(def build-codex-request codex/build-codex-request)
(def stream-openai chat/stream-openai)
(def stream-openai-codex codex/stream-openai-codex)

(defn stream-openai-dispatch
  [conversation model options consume-fn]
  (if (= :openai-codex-responses (:api model))
    (stream-openai-codex conversation model options consume-fn)
    (stream-openai conversation model options consume-fn)))

(def provider
  {:name   :openai
   :stream stream-openai-dispatch})
