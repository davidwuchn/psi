(ns psi.rpc.session.navigation
  "Session navigation/new-switch-fork helpers for RPC session workflows."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.rpc.events :as events]
   [psi.rpc.session.emit :as emit]
   [psi.rpc.transport :refer [response-frame]]))

(defn handle-new-session!
  [{:keys [ctx request state on-new-session!]}]
  (let [source-session-id (events/focus-session-id state)
        [rehydrate new-sd]
        (if on-new-session!
          [(on-new-session! source-session-id) nil]
          (let [sd (session/new-session-in! ctx source-session-id {})]
            [{:agent-messages [] :messages [] :tool-calls {} :tool-order []} sd]))
        new-sid   (or (:session-id new-sd) (:session-id rehydrate))
        _         (events/set-focus-session-id! state new-sid)
        sd        (ss/get-session-data-in ctx new-sid)
        msgs      (or (:agent-messages rehydrate)
                      (:messages (agent/get-data-in (ss/agent-ctx-in ctx new-sid))))
        emit!     (emit/make-request-emitter (:emit-frame! request) state (:id request))]
    (emit/emit-session-rehydration!
     emit!
     {:session-id (:session-id sd)
      :session-file (:session-file sd)
      :message-count (count msgs)
      :messages msgs
      :tool-calls (or (:tool-calls rehydrate) {})
      :tool-order (or (:tool-order rehydrate) [])})
    (emit/emit-session-snapshots! emit! ctx state new-sid {:context? true})
    (response-frame (:id request) "new_session" true {:session-id (:session-id sd)
                                                       :session-file (:session-file sd)})))

(defn handle-switch-session!
  [{:keys [ctx request params state session-id]}]
  (if-let [sid (:session-id params)]
    (do
      (when-not (and (string? sid) (not (str/blank? sid)))
        (throw (ex-info "invalid request parameter :session-id: non-empty string"
                        {:error-code "request/invalid-params"})))
      (let [source-session-id session-id
            _    (session/ensure-session-loaded-in! ctx source-session-id sid)
            _    (events/set-focus-session-id! state sid)
            sd   (ss/get-session-data-in ctx sid)
            msgs (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))
            emit! (emit/make-request-emitter (:emit-frame! request) state (:id request))]
        (emit/emit-session-rehydration!
         emit!
         {:session-id (:session-id sd)
          :session-file (:session-file sd)
          :message-count (count msgs)
          :messages msgs
          :tool-calls {}
          :tool-order []})
        (emit/emit-session-snapshots! emit! ctx state sid {:context? true})
        (response-frame (:id request) "switch_session" true {:session-id (:session-id sd)
                                                              :session-file (:session-file sd)})))
    (let [session-path (get params :session-path)]
      (when-not (and (string? session-path) (not (str/blank? session-path)))
        (throw (ex-info "invalid request parameter :session-path: non-empty path string"
                        {:error-code "request/invalid-params"})))
      (when-not (.exists (io/file session-path))
        (throw (ex-info "session file not found"
                        {:error-code "request/not-found"})))
      (let [current-sid session-id
            sd          (session/resume-session-in! ctx current-sid session-path)
            sid         (:session-id sd)
            _           (events/set-focus-session-id! state sid)
            msgs        (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))
            emit!       (emit/make-request-emitter (:emit-frame! request) state (:id request))]
        (emit/emit-session-rehydration!
         emit!
         {:session-id (:session-id sd)
          :session-file (:session-file sd)
          :message-count (count msgs)
          :messages msgs
          :tool-calls {}
          :tool-order []})
        (emit/emit-session-snapshots! emit! ctx state sid {:context? true})
        (response-frame (:id request) "switch_session" true {:session-id (:session-id sd)
                                                              :session-file (:session-file sd)})))))

(defn handle-fork!
  [{:keys [ctx request params state session-id]}]
  (let [entry-id          (:entry-id params)
        _                 (when-not (and (string? entry-id) (not (str/blank? entry-id)))
                            (throw (ex-info "invalid request parameter :entry-id: non-empty entry id"
                                            {:error-code "request/invalid-params"})))
        parent-session-id session-id
        sd                (session/fork-session-in! ctx parent-session-id entry-id)
        sid               (:session-id sd)
        _                 (events/set-focus-session-id! state sid)
        emit!             (emit/make-request-emitter (:emit-frame! request) state (:id request))]
    (emit/emit-context-updated! emit! ctx state sid)
    (response-frame (:id request) "fork" true {:session-id (:session-id sd)
                                                :session-file (:session-file sd)})))
