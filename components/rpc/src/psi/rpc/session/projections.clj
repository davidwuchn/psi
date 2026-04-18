(ns psi.rpc.session.projections
  "Subscriber-aware RPC projection invalidation delivery helpers."
  (:require
   [psi.agent-session.session-state :as ss]
   [psi.rpc.events :as events]
   [psi.rpc.state :as rpc.state]))

(defn emit-context-updated!
  [ctx emit-frame! state session-id]
  (events/emit-event! emit-frame! state
                      {:event "context/updated"
                       :data  (events/context-updated-payload ctx state session-id)}))

(defn emit-ui-snapshot!
  [ctx emit-frame! state]
  (events/emit-ui-snapshot-events! emit-frame! state {} (or (events/ui-snapshot ctx) {})))

(defn handle-projection-change!
  [{:keys [ctx emit-frame! state]} change]
  (case (:projection/type change)
    :context-changed
    (let [sid (or (:session-id change)
                  (rpc.state/focus-session-id state)
                  (some-> (ss/list-context-sessions-in ctx) first :session-id))]
      (when sid
        (emit-context-updated! ctx emit-frame! state sid)))

    :ui-changed
    (emit-ui-snapshot! ctx emit-frame! state)

    nil))

(defn projection-topic-subscribed?
  [state]
  (let [topics (rpc.state/subscribed-topics state)]
    (or (contains? topics "context/updated")
        (some events/extension-ui-topic? topics))))

(defn ensure-projection-listener!
  [ctx emit-frame! state]
  (when (and (projection-topic-subscribed? state)
             (not (rpc.state/projection-listener-id state)))
    (when-let [register-fn (:register-projection-listener-fn ctx)]
      (let [listener-id (register-fn ctx (fn [change]
                                           (handle-projection-change! {:ctx ctx
                                                                       :emit-frame! emit-frame!
                                                                       :state state}
                                                                      change)))]
        (rpc.state/set-projection-listener-id! state listener-id)
        listener-id))))

(defn unregister-projection-listener!
  [ctx state]
  (when-let [listener-id (rpc.state/projection-listener-id state)]
    (when-let [unregister-fn (:unregister-projection-listener-fn ctx)]
      (unregister-fn ctx listener-id))
    (rpc.state/set-projection-listener-id! state nil)
    true))
