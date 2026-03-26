(ns psi.agent-session.state-accessors
  "Named semantic accessors and mutators for canonical session state slices.
   Thin wrappers over state-path + get-state-value-in / dispatch."
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.session-state :as session]
   [psi.ui.state :as ui-state]))

;;; Readers

(defn nrepl-runtime-in
  "Return canonical runtime-visible nREPL metadata."
  [ctx]
  (session/get-state-value-in ctx (session/state-path :nrepl-runtime)))

(defn rpc-trace-state-in
  "Return canonical runtime-visible RPC trace state."
  [ctx]
  (session/get-state-value-in ctx (session/state-path :rpc-trace)))

(defn oauth-projection-in
  "Return canonical runtime-visible oauth projection state."
  [ctx]
  (session/get-state-value-in ctx (session/state-path :oauth)))

(defn recursion-state-in
  "Return canonical recursion projection state."
  [ctx]
  (session/get-state-value-in ctx (session/state-path :recursion)))

(defn turn-context-in
  "Return the current turn statechart context from canonical runtime state."
  [ctx]
  (let [sid (session/active-session-id-in ctx)]
    (session/get-state-value-in ctx (session/state-path :turn-ctx sid))))

(defn journal-state-in
  "Return the canonical in-memory journal vector."
  [ctx]
  (let [sid (session/active-session-id-in ctx)]
    (session/get-state-value-in ctx (session/state-path :journal sid))))

(defn flush-state-in
  "Return canonical persistence flush-state metadata."
  [ctx]
  (let [sid (session/active-session-id-in ctx)]
    (session/get-state-value-in ctx (session/state-path :flush-state sid))))

(defn tool-output-stats-in
  "Return canonical tool-output statistics state."
  [ctx]
  (let [sid (session/active-session-id-in ctx)]
    (session/get-state-value-in ctx (session/state-path :tool-output-stats sid))))

(defn tool-call-attempts-in
  "Return canonical tool-call attempt telemetry vector."
  [ctx]
  (let [sid (session/active-session-id-in ctx)]
    (session/get-state-value-in ctx (session/state-path :tool-call-attempts sid))))

(defn tool-lifecycle-events-in
  "Return canonical tool lifecycle telemetry vector."
  [ctx]
  (let [sid (session/active-session-id-in ctx)]
    (session/get-state-value-in ctx (session/state-path :tool-lifecycle-events sid))))

(defn provider-requests-in
  "Return canonical provider request capture vector."
  [ctx]
  (let [sid (session/active-session-id-in ctx)]
    (session/get-state-value-in ctx (session/state-path :provider-requests sid))))

(defn provider-replies-in
  "Return canonical provider reply capture vector."
  [ctx]
  (let [sid (session/active-session-id-in ctx)]
    (session/get-state-value-in ctx (session/state-path :provider-replies sid))))

;;; Mutators

(defn set-nrepl-runtime-in!
  "Persist canonical runtime-visible nREPL metadata.
   Routed through the dispatch pipeline."
  [ctx runtime]
  (dispatch/dispatch! ctx :session/set-nrepl-runtime {:runtime runtime} {:origin :core})
  (nrepl-runtime-in ctx))

(defn set-oauth-projection-in!
  "Persist runtime-visible OAuth projection data.
   Routed through the dispatch pipeline."
  [ctx oauth]
  (dispatch/dispatch! ctx :session/set-oauth-projection {:oauth oauth} {:origin :core})
  (oauth-projection-in ctx))

(defn set-recursion-state-in!
  "Persist canonical recursion state projection.
   Routed through the dispatch pipeline."
  [ctx recursion-state]
  (dispatch/dispatch! ctx :session/set-recursion-state {:recursion-state recursion-state} {:origin :core})
  (recursion-state-in ctx))

(defn set-turn-context-in!
  "Persist the current turn statechart context into canonical runtime state.
   Intended for executor/runtime introspection plumbing.
   Routed through the dispatch pipeline."
  [ctx turn-ctx]
  (dispatch/dispatch! ctx :session/set-turn-context {:session-id (session/active-session-id-in ctx) :turn-ctx turn-ctx} {:origin :core})
  (turn-context-in ctx))

(defn append-tool-call-attempt-in!
  "Append one tool-call attempt telemetry entry into canonical state.
   Intended for executor-side telemetry capture.
   Routed through the dispatch pipeline."
  [ctx attempt]
  (dispatch/dispatch! ctx :session/append-tool-call-attempt {:session-id (session/active-session-id-in ctx) :attempt attempt} {:origin :core})
  (tool-call-attempts-in ctx))

(defn append-provider-request-capture-in!
  "Append one provider request capture into canonical state with bounded retention.
   Intended for executor-side provider telemetry capture.
   Routed through the dispatch pipeline."
  [ctx capture]
  (dispatch/dispatch! ctx :session/append-provider-request-capture {:session-id (session/active-session-id-in ctx) :capture capture} {:origin :core})
  (provider-requests-in ctx))

(defn append-provider-reply-capture-in!
  "Append one provider reply capture into canonical state with bounded retention.
   Intended for executor-side provider telemetry capture.
   Routed through the dispatch pipeline."
  [ctx capture]
  (dispatch/dispatch! ctx :session/append-provider-reply-capture {:session-id (session/active-session-id-in ctx) :capture capture} {:origin :core})
  (provider-replies-in ctx))

(defn record-tool-output-stat-in!
  "Record one tool-output statistics entry into canonical state.
   Intended for executor-side tool output accounting.
   Routed through the dispatch pipeline."
  [ctx stat context-bytes-added limit-hit?]
  (dispatch/dispatch! ctx
                      :session/record-tool-output-stat
                      {:session-id          (session/active-session-id-in ctx)
                       :stat                stat
                       :context-bytes-added context-bytes-added
                       :limit-hit?          limit-hit?}
                      {:origin :core})
  (tool-output-stats-in ctx))

(defn update-oauth-authenticated-providers-in!
  "Update only the authenticated-providers field of the canonical oauth projection."
  [ctx ids]
  (let [oauth* (assoc (or (oauth-projection-in ctx) {})
                      :authenticated-providers ids)]
    (set-oauth-projection-in! ctx oauth*)))

(defn refresh-oauth-authenticated-providers-in!
  "Refresh canonical authenticated-provider ids from the oauth runtime context.
   Public named API so adjacent namespaces do not own oauth projection refresh logic."
  [ctx]
  (let [ids (if-let [oauth-ctx (:oauth-ctx ctx)]
              (->> (oauth/available-providers oauth-ctx)
                   (keep (fn [provider]
                           (let [provider-id (:id provider)]
                             (when (and provider-id
                                        (oauth/has-auth? oauth-ctx provider-id))
                               (name provider-id)))))
                   distinct
                   sort
                   vec)
              [])]
    (update-oauth-authenticated-providers-in! ctx ids)
    ids))

(defn resolve-active-dialog-in!
  "Resolve the active canonical UI dialog and advance the queue.
   Public named API so transport code does not own dialog queue transition logic."
  [ctx dialog-id result]
  (ui-state/resolve-dialog! (session/ui-state-view-in ctx) dialog-id result))

(defn cancel-active-dialog-in!
  "Cancel the active canonical UI dialog and advance the queue.
   Public named API so transport code does not own dialog queue transition logic."
  [ctx]
  (ui-state/cancel-dialog! (session/ui-state-view-in ctx)))

(defn set-oauth-pending-login-in!
  "Set the canonical oauth pending-login projection.
   Intended for RPC login_begin flows."
  [ctx {:keys [provider-id provider-name login-state]}]
  (let [oauth* (assoc (or (oauth-projection-in ctx) {})
                      :pending-login {:provider-id provider-id
                                      :provider-name provider-name
                                      :login-state login-state})]
    (set-oauth-projection-in! ctx oauth*)))

(defn complete-oauth-login-in!
  "Clear canonical oauth pending-login and record last-login metadata.
   Intended for RPC login_complete flows."
  [ctx provider-id]
  (let [oauth* (assoc (or (oauth-projection-in ctx) {})
                      :pending-login nil
                      :last-login-provider (name provider-id)
                      :last-login-at (java.time.Instant/now))]
    (set-oauth-projection-in! ctx oauth*)))
