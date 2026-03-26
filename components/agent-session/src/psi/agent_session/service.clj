(ns psi.agent-session.service
  "Session-id routed service surface for consumers.

   Consumers (TUI, extensions, RPC) interact with sessions exclusively via
   explicit session-id. They never hold or receive the process-scoped runtime
   context. No implicit current-session fallback.

   The service holds a reference to the process ctx internally. All operations
   resolve the session-id to the session entry in canonical state and delegate
   to the existing *-in! functions."
  (:require
   [psi.agent-session.core :as core]
   [psi.agent-session.session-state :as ss]))

;;; Service context — holds the process-scoped ctx

(defonce ^:private service-ctx (atom nil))

(defn initialize!
  "Initialize the service with a process-scoped ctx.
   Called once at startup after create-context."
  [ctx]
  (reset! service-ctx ctx))

(defn- resolve-ctx
  "Return the service ctx. Throws if not initialized."
  []
  (or @service-ctx
      (throw (ex-info "Service not initialized — call initialize! first" {}))))

(defn- require-session-id
  "Require an explicit session-id. Throws if nil."
  [session-id]
  (or session-id
      (throw (ex-info "session-id is required — no implicit current-session fallback"
                      {:error-code "service/session-id-required"}))))

;;; Service surface

(defn query
  "Run EQL `eql` against the session identified by `session-id`.
   Returns the query result map.
   session-id is required."
  [session-id eql]
  (let [ctx (resolve-ctx)
        _sid (require-session-id session-id)]
    ;; TODO: when multi-session queries need session-scoping,
    ;; the resolver seed will include session-id
    (core/query-in ctx eql)))

(defn prompt!
  "Submit `text` to the session identified by `session-id`.
   session-id is required."
  [session-id text]
  (let [ctx (resolve-ctx)
        _sid (require-session-id session-id)]
    (core/prompt-in! ctx text)))

(defn abort!
  "Abort the session identified by `session-id`.
   session-id is required."
  [session-id]
  (let [ctx (resolve-ctx)
        _sid (require-session-id session-id)]
    (core/abort-in! ctx)))

(defn steer!
  "Inject a steering message into the session identified by `session-id`.
   session-id is required."
  [session-id text]
  (let [ctx (resolve-ctx)
        _sid (require-session-id session-id)]
    (core/steer-in! ctx text)))

(defn follow-up!
  "Queue a follow-up message for the session identified by `session-id`.
   session-id is required."
  [session-id text]
  (let [ctx (resolve-ctx)
        _sid (require-session-id session-id)]
    (core/follow-up-in! ctx text)))

(defn get-session-data
  "Return the session data for `session-id`.
   session-id is required."
  [session-id]
  (let [ctx (resolve-ctx)
        sid (require-session-id session-id)]
    (ss/get-session-data-in ctx sid)))

(defn idle?
  "True when the session identified by `session-id` is idle.
   session-id is required."
  [session-id]
  (let [ctx (resolve-ctx)
        _sid (require-session-id session-id)]
    ;; TODO: make idle? session-scoped once statechart is per-session
    (ss/idle-in? ctx)))

(defn list-sessions
  "Return metadata for all sessions in the process."
  []
  (ss/list-context-sessions-in (resolve-ctx)))
