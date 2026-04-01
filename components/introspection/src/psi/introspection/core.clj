(ns psi.introspection.core
  "Introspection component — makes the Psi system queryable via EQL.

   The introspection component bridges the engine (statechart state,
   transitions, system readiness), the query graph (registered
   resolvers/mutations), and optionally the agent-session into a uniform
   EQL query surface.

   Usage pattern — isolated (tests):

     (let [ctx (introspection/create-context)]
       (engine/bootstrap-system-in! (:engine-ctx ctx))
       (introspection/register-resolvers-in! ctx)
       (introspection/query-system-state-in ctx))

   With agent-session:

     (let [session-ctx (session/create-context)
           _          (session/new-session-in! session-ctx nil {})
           ctx        (introspection/create-context {:agent-session-ctx session-ctx})]
       (introspection/register-resolvers-in! ctx)
       (introspection/query-agent-session-in ctx [:psi.agent-session/phase]))

   Usage pattern — global (production):

     (introspection/register-resolvers!)        ; once at startup
     (introspection/query-system-state)         ; thereafter

   Public API:
     register-resolvers!           — register engine + agent-session resolvers into
                                     the global query graph (call once at startup)
     register-resolvers-in!        — same but for an isolated IntrospectionContext
     create-context                — create isolated context (Nullable pattern)
     query-system-state-in         — system state + derived properties (ctx)
     query-transitions-in          — full transition log (ctx)
     query-recent-transitions-in   — last 20 transitions (ctx)
     query-graph-summary-in        — query graph statistics (ctx)
     query-all-engines-in          — all engines + count (ctx)
     query-engine-detail-in        — single-engine diagnostics (ctx)
     query-agent-session-in        — EQL query over :psi.agent-session/* (ctx, q)"
  (:require

   [psi.graph.analysis :as graph]

   [psi.engine.core :as engine]
   [psi.query.core :as query]
   [psi.query.registry :as registry]
   [psi.memory.core :as memory]
   [psi.system-bootstrap.core :as bootstrap]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Global registration
;; ─────────────────────────────────────────────────────────────────────────────

(defn register-resolvers!
  "Register startup domains into the global query graph and rebuild once.

   Domains:
   - AI resolvers
   - History resolvers
   - Introspection resolvers
   - Memory resolvers
   - Recursion resolvers
   - Agent-session resolvers + mutations

   Uses common dependency extraction to avoid circular dependencies.
   Idempotent: skips operations already present in the global registry."
  []
  (bootstrap/register-all-domains!))

;; ─────────────────────────────────────────────────────────────────────────────
;; Isolated introspection context (Nullable pattern)
;; ─────────────────────────────────────────────────────────────────────────────

(defrecord IntrospectionContext [engine-ctx query-ctx memory-ctx recursion-ctx agent-session-ctx])

(defn create-context
  "Create an isolated introspection context with its own engine and query atoms.
   Use in tests (pass instead of global context) to avoid shared state.

   Options (map, all optional):
     :engine-ctx        — an isolated engine context
                          (default: fresh from engine/create-context)
     :query-ctx         — an isolated query context
                          (default: fresh from query/create-query-context)
     :memory-ctx        — a memory context to expose via EQL
                          (default: fresh from memory/create-context)
     :recursion-ctx     — a recursion context to expose via EQL
                          (default: nil — recursion resolvers still registered
                          but queries require seeding :psi/recursion-ctx)
     :agent-session-ctx — an agent-session context to expose via EQL
                          (default: nil — agent-session resolvers not registered)"
  ([]
   (create-context {}))
  ([{:keys [engine-ctx query-ctx memory-ctx recursion-ctx agent-session-ctx]}]
   (->IntrospectionContext
    (or engine-ctx (engine/create-context))
    (or query-ctx (query/create-query-context))
    (or memory-ctx (memory/create-context))
    recursion-ctx
    agent-session-ctx)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Context-aware helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn reconcile-graph-readiness-in!
  "Derive Step 7 graph readiness from computed graph shape and update engine state.

   Ready gate:
   - node-count > 0
   - edge-count > 0

   Stage semantics:
   - ready => :integrating
   - not ready => :developing"
  [ctx]
  (let [qctx    (:query-ctx ctx)
        ectx    (:engine-ctx ctx)
        cgraph  (graph/derive-capability-graph
                 {:resolver-ops (mapv #(graph/operation->metadata :resolver %)
                                      (registry/all-resolvers-in (:reg qctx)))
                  :mutation-ops (mapv #(graph/operation->metadata :mutation %)
                                      (registry/all-mutations-in (:reg qctx)))})
        ready?  (and (pos? (count (:nodes cgraph)))
                     (pos? (count (:edges cgraph))))
        stage   (if ready? :integrating :developing)]
    (engine/update-system-component-in! ectx :query-ready true)
    (engine/update-system-component-in! ectx :graph-ready ready?)
    (engine/set-evolution-stage-in! ectx stage)
    {:graph-ready ready?
     :evolution-stage stage
     :node-count (count (:nodes cgraph))
     :edge-count (count (:edges cgraph))}))

(defn register-resolvers-in!
  "Register startup domains into `ctx`'s query context and rebuild once.

   Domains:
   - AI resolvers
   - History resolvers
   - Introspection resolvers
   - Memory resolvers
   - Recursion resolvers + mutations
   - Agent-session resolvers + mutations (when :agent-session-ctx is present)

   If `ctx` carries an :agent-session-ctx, agent-session resolvers + mutations
   are also registered so :psi.agent-session/* and mutation-backed workflows are
   queryable/executable.

   Idempotent for an isolated qctx: skips operations already present before
   rebuilding.

   Also derives and applies runtime Step 7 readiness:
   - :graph-ready true when graph has nodes and edges, else false
   - :evolution-stage set to :integrating when ready, else :developing"
  [ctx]
  (let [qctx        (:query-ctx ctx)
        session-ctx (:agent-session-ctx ctx)]
    ;; Use system bootstrap component to register resolvers cleanly
    (bootstrap/register-domains-in! qctx session-ctx)

    ;; Single env rebuild after all operations are registered.
    (query/rebuild-env-in! qctx)
    (reconcile-graph-readiness-in! ctx)))

(defn query-system-state-in
  "Return system state + derived properties via EQL using `ctx`."
  [ctx]
  (let [{:keys [engine-ctx query-ctx]} ctx]
    (query/query-in query-ctx {:psi/engine-ctx engine-ctx}
                    [:psi.system/state
                     :psi.system/mode
                     :psi.system/evolution-stage
                     :psi.system/readiness
                     :psi.system/has-interface
                     :psi.system/has-substrate
                     :psi.system/has-memory-layer
                     :psi.system/is-ai-complete])))

(defn query-transitions-in
  "Return the full transition log via EQL using `ctx`."
  [ctx]
  (let [{:keys [engine-ctx query-ctx]} ctx]
    (query/query-in query-ctx {:psi/engine-ctx engine-ctx}
                    [:psi.engine/transitions
                     :psi.engine/transition-count])))

(defn query-recent-transitions-in
  "Return the last 20 transitions (newest-first) via EQL using `ctx`."
  [ctx]
  (let [{:keys [engine-ctx query-ctx]} ctx]
    (query/query-in query-ctx {:psi/engine-ctx engine-ctx}
                    [:psi.engine/recent-transitions])))

(defn query-graph-summary-in
  "Return query graph statistics and Step 7 capability graph attrs via EQL using `ctx`."
  [ctx]
  (let [{:keys [query-ctx]} ctx]
    (query/query-in query-ctx {:psi/query-ctx query-ctx}
                    [:psi.graph/resolver-count
                     :psi.graph/mutation-count
                     :psi.graph/resolver-syms
                     :psi.graph/mutation-syms
                     :psi.graph/env-built
                     :psi.graph/nodes
                     :psi.graph/edges
                     :psi.graph/capabilities
                     :psi.graph/domain-coverage])))

(defn query-all-engines-in
  "Return all registered engines and count via EQL using `ctx`."
  [ctx]
  (let [{:keys [engine-ctx query-ctx]} ctx]
    (query/query-in query-ctx {:psi/engine-ctx engine-ctx}
                    [:psi.engine/all-engines
                     :psi.engine/engine-count])))

(defn query-engine-detail-in
  "Return diagnostics for a single engine by `engine-id` using `ctx`."
  [ctx engine-id]
  (let [{:keys [engine-ctx query-ctx]} ctx]
    (query/query-in query-ctx {:psi/engine-ctx engine-ctx
                               :psi.engine/id  engine-id}
                    [:psi.engine/status
                     :psi.engine/active-states
                     :psi.engine/diagnostics])))

(defn query-agent-session-in
  "Run EQL query `q` over the live graph using `ctx`.
   Requires ctx to have been created with an :agent-session-ctx option.
   Also seeds :psi/memory-ctx so :psi.memory/* attrs are queryable in-session."
  [ctx q]
  (let [{:keys [agent-session-ctx memory-ctx query-ctx]} ctx]
    (when-not agent-session-ctx
      (throw (ex-info "No :agent-session-ctx in introspection context" {})))
    (query/query-in query-ctx {:psi/agent-session-ctx agent-session-ctx
                               :psi/memory-ctx memory-ctx}
                    q)))
