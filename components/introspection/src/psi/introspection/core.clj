(ns psi.introspection.core
  "Introspection component — makes the Psi system queryable via EQL.

   The introspection component bridges the engine (statechart state,
   transitions, system readiness) and the query graph (registered
   resolvers/mutations) into a uniform EQL query surface.

   Usage pattern — isolated (tests):

     (let [ctx (introspection/create-context)]
       (engine/bootstrap-system-in! (:engine-ctx ctx))
       (introspection/register-resolvers-in! ctx)
       (introspection/query-system-state-in ctx))

   Usage pattern — global (production):

     (introspection/register-resolvers!)        ; once at startup
     (introspection/query-system-state)         ; thereafter

   Public API:
     register-resolvers!       — register all introspection resolvers into the
                                 global query graph (call once at startup)
     register-resolvers-in!    — same but for an isolated IntrospectionContext
     create-context            — create isolated context (Nullable pattern)
     query-system-state-in     — system state + derived properties (ctx)
     query-transitions-in      — full transition log (ctx)
     query-recent-transitions-in — last 20 transitions (ctx)
     query-graph-summary-in    — query graph statistics (ctx)
     query-all-engines-in      — all engines + count (ctx)
     query-engine-detail-in    — single-engine diagnostics (ctx)"
  (:require
   [psi.introspection.resolvers :as resolvers]
   [psi.engine.core :as engine]
   [psi.query.core :as query]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Global registration
;; ─────────────────────────────────────────────────────────────────────────────

(defn register-resolvers!
  "Register all introspection resolvers into the global query graph.
   Call once at system startup before issuing introspection queries."
  []
  (doseq [r resolvers/all-resolvers]
    (query/register-resolver! r))
  (query/rebuild-env!))

;; ─────────────────────────────────────────────────────────────────────────────
;; Isolated introspection context (Nullable pattern)
;; ─────────────────────────────────────────────────────────────────────────────

(defrecord IntrospectionContext [engine-ctx query-ctx])

(defn create-context
  "Create an isolated introspection context with its own engine and query atoms.
   Use in tests (pass instead of global context) to avoid shared state.

   `engine-ctx` — an isolated engine context (default: fresh from engine/create-context)
   `query-ctx`  — an isolated query context  (default: fresh from query/create-query-context)"
  ([]
   (create-context (engine/create-context) (query/create-query-context)))
  ([engine-ctx query-ctx]
   (->IntrospectionContext engine-ctx query-ctx)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Context-aware helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn register-resolvers-in!
  "Register all introspection resolvers into `ctx`'s query context,
   then rebuild the env so queries work immediately."
  [ctx]
  (let [qctx (:query-ctx ctx)]
    (doseq [r resolvers/all-resolvers]
      (query/register-resolver-in! qctx r))
    (query/rebuild-env-in! qctx)))

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
  "Return query graph statistics via EQL using `ctx`."
  [ctx]
  (let [{:keys [query-ctx]} ctx]
    (query/query-in query-ctx {:psi/query-ctx query-ctx}
                    [:psi.graph/resolver-count
                     :psi.graph/mutation-count
                     :psi.graph/resolver-syms
                     :psi.graph/mutation-syms
                     :psi.graph/env-built])))

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
