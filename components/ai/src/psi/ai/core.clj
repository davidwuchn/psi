(ns psi.ai.core
  "Core AI provider implementation.

   Integrates with the engine and query components:
   - Registers Pathom resolvers so AI capabilities are queryable via EQL.
   - Notifies the engine when the AI component becomes ready.
   - Keeps direct core.async usage out of this namespace entirely.
   "
  (:require [psi.ai.conversation :as conversation]
            [psi.ai.streaming :as streaming]
            [psi.ai.models :as models]
            [psi.ai.schemas :as schemas]
            [psi.ai.providers.anthropic :as anthropic]
            [psi.ai.providers.openai :as openai]
            [psi.engine.core :as engine]
            [psi.query.core :as query]
            [com.wsscode.pathom3.connect.operation :as pco]))

;; ───────────────────────────────────────────────────────────────────────────
;; Provider registry
;; ───────────────────────────────────────────────────────────────────────────

(defonce ^:private provider-registry
  (atom {:anthropic anthropic/provider
         :openai    openai/provider}))

;; ───────────────────────────────────────────────────────────────────────────
;; Pathom resolvers — AI capabilities exposed via EQL
;; ───────────────────────────────────────────────────────────────────────────

(pco/defresolver ai-model-resolver
  "Resolve a model map by qualified key, e.g. :ai.model/key → model map."
  [{model-key :ai.model/key}]
  {::pco/input  [:ai.model/key]
   ::pco/output [:ai.model/data]}
  {:ai.model/data (models/get-model model-key)})

(pco/defresolver ai-model-list-resolver
  "Resolve the full model catalogue (all providers)."
  [_]
  {::pco/output [:ai/all-models]}
  {:ai/all-models models/all-models})

(pco/defresolver ai-provider-models-resolver
  "Resolve models for a given provider key."
  [{provider :ai/provider}]
  {::pco/input  [:ai/provider]
   ::pco/output [:ai/provider-models]}
  {:ai/provider-models (models/list-for-provider provider)})

(pco/defresolver ai-provider-registry-resolver
  "Resolve the set of registered provider keys."
  [_]
  {::pco/output [:ai/registered-providers]}
  {:ai/registered-providers (set (keys @provider-registry))})

;; ───────────────────────────────────────────────────────────────────────────
;; Register resolvers with the query component
;; ───────────────────────────────────────────────────────────────────────────

(defn register-resolvers!
  "Register all AI resolvers into the query graph and rebuild the environment."
  []
  (query/register-resolver! ai-model-resolver)
  (query/register-resolver! ai-model-list-resolver)
  (query/register-resolver! ai-provider-models-resolver)
  (query/register-resolver! ai-provider-registry-resolver)
  (query/rebuild-env!))

;; ───────────────────────────────────────────────────────────────────────────
;; Initialisation
;; ───────────────────────────────────────────────────────────────────────────

(defn init!
  "Initialise the AI component: register resolvers and mark engine ready."
  []
  (register-resolvers!)
  (engine/update-system-component! :engine-ready true)
  :ok)

;; ───────────────────────────────────────────────────────────────────────────
;; Public API — conversations
;; ───────────────────────────────────────────────────────────────────────────

(defn create-conversation
  "Create a new conversation with optional system prompt."
  ([system-prompt]
   (conversation/create system-prompt))
  ([]
   (conversation/create nil)))

(defn send-message
  "Add a user message to a conversation."
  [conversation content]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (string? content)]}
  (conversation/add-user-message conversation content))

(defn add-tool
  "Add a tool to conversation."
  [conversation tool]
  (conversation/add-tool conversation tool))

(defn get-conversation-cost
  "Get total cost for conversation."
  [conversation]
  (conversation/total-cost conversation))

(defn get-conversation-usage
  "Get total token usage for conversation."
  [conversation]
  (conversation/total-usage conversation))

;; ───────────────────────────────────────────────────────────────────────────
;; Public API — streaming
;; ───────────────────────────────────────────────────────────────────────────

(defn stream-response
  "Stream assistant response using `consume-fn` callback.

   Runs the provider on a background thread; `consume-fn` is called for
   every event.  Returns {:future ... :session atom}.

   See `psi.ai.streaming/stream-response` for event shapes."
  [conversation model options consume-fn]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (schemas/valid? schemas/Model model)
         (schemas/valid? schemas/StreamOptions options)]}
  (let [provider-impl (get @provider-registry (:provider model))]
    (when-not provider-impl
      (throw (ex-info "Unknown provider" {:provider (:provider model)})))
    (streaming/stream-response provider-impl conversation model options consume-fn)))

(defn stream-response-seq
  "Stream assistant response as a lazy sequence of events.

   Returns {:events lazy-seq :session atom}.
   Blocks per element until the next event arrives (30-second timeout).

   See `psi.ai.streaming/stream-response-seq` for event shapes."
  [conversation model options]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (schemas/valid? schemas/Model model)
         (schemas/valid? schemas/StreamOptions options)]}
  (let [provider-impl (get @provider-registry (:provider model))]
    (when-not provider-impl
      (throw (ex-info "Unknown provider" {:provider (:provider model)})))
    (streaming/stream-response-seq provider-impl conversation model options)))

;; ───────────────────────────────────────────────────────────────────────────
;; Public API — models
;; ───────────────────────────────────────────────────────────────────────────

(defn list-models
  "List available models for provider."
  [provider]
  (models/list-for-provider provider))

;; ───────────────────────────────────────────────────────────────────────────
;; Provider registration
;; ───────────────────────────────────────────────────────────────────────────

(defn register-provider!
  "Register a new provider implementation."
  [provider-key provider-impl]
  (swap! provider-registry assoc provider-key provider-impl))

(defn get-provider
  "Get provider implementation."
  [provider-key]
  (get @provider-registry provider-key))
