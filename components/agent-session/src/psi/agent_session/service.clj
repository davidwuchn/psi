(ns psi.agent-session.service
  "Session-id routed service surface for consumers.

   Consumers (TUI, extensions, RPC) interact with sessions exclusively via
   session-id. They never hold or receive the process-scoped runtime context.

   The service holds a reference to the process ctx internally. All operations
   resolve the session-id to the session entry in canonical state and delegate
   to the existing *-in! functions.

   When session-id is nil, the active-session-id is used as default."
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

(defn- resolve-session-id
  "Resolve an explicit session-id or fall back to active-session-id."
  [ctx session-id]
  (or session-id (ss/active-session-id-in ctx)))

;;; Service surface

(defn query
  "Run EQL `eql` against the session identified by `session-id`.
   Returns the query result map."
  ([eql] (query nil eql))
  ([session-id eql]
   (let [ctx (resolve-ctx)
         _sid (resolve-session-id ctx session-id)]
     ;; TODO: when multi-session queries need session-scoping,
     ;; the resolver seed will include session-id
     (core/query-in ctx eql))))

(defn prompt!
  "Submit `text` to the session identified by `session-id`."
  ([text] (prompt! nil text))
  ([session-id text]
   (let [ctx (resolve-ctx)
         _sid (resolve-session-id ctx session-id)]
     (core/prompt-in! ctx text))))

(defn abort!
  "Abort the session identified by `session-id`."
  ([] (abort! nil))
  ([session-id]
   (let [ctx (resolve-ctx)
         _sid (resolve-session-id ctx session-id)]
     (core/abort-in! ctx))))

(defn steer!
  "Inject a steering message into the session identified by `session-id`."
  ([text] (steer! nil text))
  ([session-id text]
   (let [ctx (resolve-ctx)
         _sid (resolve-session-id ctx session-id)]
     (core/steer-in! ctx text))))

(defn follow-up!
  "Queue a follow-up message for the session identified by `session-id`."
  ([text] (follow-up! nil text))
  ([session-id text]
   (let [ctx (resolve-ctx)
         _sid (resolve-session-id ctx session-id)]
     (core/follow-up-in! ctx text))))

(defn get-session-data
  "Return the session data for `session-id`."
  ([] (get-session-data nil))
  ([session-id]
   (let [ctx (resolve-ctx)
         sid (resolve-session-id ctx session-id)]
     (ss/get-session-data-in ctx sid))))

(defn idle?
  "True when the session identified by `session-id` is idle."
  ([] (idle? nil))
  ([_session-id]
   (let [ctx (resolve-ctx)]
     (ss/idle-in? ctx))))

(defn active-session-id
  "Return the current active session id."
  []
  (ss/active-session-id-in (resolve-ctx)))

(defn list-sessions
  "Return metadata for all sessions in the process."
  []
  (ss/list-context-sessions-in (resolve-ctx)))
