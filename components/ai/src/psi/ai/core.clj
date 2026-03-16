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
;; Isolated AI context (Nullable pattern)
;; ───────────────────────────────────────────────────────────────────────────

(defn create-context
  "Create an isolated AI context with its own provider registry atom.

  Options:
    :providers — map of provider-key → provider-impl to seed the registry
                 (default: empty map; pass {:anthropic stub-provider} for tests)

  Use in tests to stream responses through a stub provider without touching
  the global provider-registry."
  ([] (create-context {}))
  ([{:keys [providers] :or {providers {}}}]
   {:provider-registry (atom providers)}))

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

(def all-resolvers
  "All AI resolvers. Used by introspection to avoid duplicating the list."
  [ai-model-resolver
   ai-model-list-resolver
   ai-provider-models-resolver
   ai-provider-registry-resolver])

(defn register-resolvers-in!
  "Register all AI resolvers into an isolated `qctx` query context and rebuild its env."
  [qctx]
  (run! #(query/register-resolver-in! qctx %) all-resolvers)
  (query/rebuild-env-in! qctx))

(defn register-resolvers!
  "Register all AI resolvers into the global query graph and rebuild the environment."
  []
  (run! query/register-resolver! all-resolvers)
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
  "Create a new conversation.

   Accepts either:
   - a system prompt string
   - an options map supported by psi.ai.conversation/create"
  ([system-prompt-or-options]
   (conversation/create system-prompt-or-options))
  ([]
   (conversation/create {:system-prompt nil})))

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
;; Streaming — context-aware core (used by public API and tests)
;; ───────────────────────────────────────────────────────────────────────────

(defn- resolve-provider
  "Look up the provider impl for `model` from `registry-atom`, throwing if absent."
  [registry-atom model]
  (let [provider-impl (get @registry-atom (:provider model))]
    (when-not provider-impl
      (throw (ex-info "Unknown provider" {:provider (:provider model)})))
    provider-impl))

(defn stream-response-in
  "Stream assistant response in an isolated `ctx`, calling `consume-fn` for each event.

  Returns {:future ... :session atom}.  The provider is resolved from `ctx`'s
  provider registry, so tests can supply a stub without touching global state."
  [ctx conversation model options consume-fn]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (schemas/valid? schemas/Model model)
         (schemas/valid? schemas/StreamOptions options)]}
  (let [provider-impl (resolve-provider (:provider-registry ctx) model)]
    (streaming/stream-response provider-impl conversation model options consume-fn)))

(defn stream-response-seq-in
  "Stream assistant response as a lazy sequence of events in an isolated `ctx`.

  Returns {:events lazy-seq :session atom}."
  [ctx conversation model options]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (schemas/valid? schemas/Model model)
         (schemas/valid? schemas/StreamOptions options)]}
  (let [provider-impl (resolve-provider (:provider-registry ctx) model)]
    (streaming/stream-response-seq provider-impl conversation model options)))

;; ───────────────────────────────────────────────────────────────────────────
;; Public API — streaming (global wrappers)
;; ───────────────────────────────────────────────────────────────────────────

(defn stream-response
  "Stream assistant response using `consume-fn` callback.

   Runs the provider on a background thread; `consume-fn` is called for
   every event.  Returns {:future ... :session atom}.

   See `psi.ai.streaming/stream-response` for event shapes."
  [conversation model options consume-fn]
  (stream-response-in {:provider-registry provider-registry}
                      conversation model options consume-fn))

(defn stream-response-seq
  "Stream assistant response as a lazy sequence of events.

   Returns {:events lazy-seq :session atom}.
   Blocks per element until the next event arrives.

   See `psi.ai.streaming/stream-response-seq` for event shapes."
  [conversation model options]
  (stream-response-seq-in {:provider-registry provider-registry}
                          conversation model options))

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
