(ns psi.rpc.session.frontend-actions
  "Frontend action result handlers for RPC session workflows."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.rpc.events :as events]
   [psi.rpc.session.emit :as emit]
   [psi.rpc.session.message-source :as message-source]
   [psi.rpc.transport :refer [response-frame]]))

(defn handle-frontend-action-result!
  [{:keys [ctx request params state session-id resolve-model]}]
  (let [request-id  (:request-id params)
        action-name (:action-name params)
        status      (:status params)
        value       (:value params)
        emit!       (emit/make-request-emitter (:emit-frame! request) state (:id request))]
    (cond
      (= status "cancelled")
      (do
        (emit/emit-command-result! emit! {:type "text"
                                          :message (str "Cancelled " action-name ".")})
        (response-frame (:id request) "frontend_action_result" true {:accepted true}))

      (= status "failed")
      (do
        (emit/emit-command-result! emit! {:type "error"
                                          :message (or (:error-message params)
                                                       (str "Frontend action failed: " action-name))})
        (response-frame (:id request) "frontend_action_result" true {:accepted true}))

      :else
      (do
        (case action-name
          "resume-selector"
          (when (string? value)
            (when (.exists (io/file value))
              (let [sd          (session/resume-session-in! ctx session-id value)
                    sid         (:session-id sd)
                    _           (events/set-focus-session-id! state sid)
                    msgs        (message-source/session-messages ctx sid)]
                (emit/emit-session-rehydration!
                 emit!
                 {:session-id sid
                  :session-file (:session-file sd)
                  :message-count (count msgs)
                  :messages msgs
                  :tool-calls {}
                  :tool-order []})
                (emit/emit-context-updated! emit! ctx state sid))))

          "context-session-selector"
          (cond
            (string? value)
            (do
              (session/ensure-session-loaded-in! ctx session-id value)
              (let [_    (events/set-focus-session-id! state value)
                    sd   (ss/get-session-data-in ctx value)
                    msgs (message-source/session-messages ctx value)]
                (emit/emit-session-rehydration!
                 emit!
                 {:session-id (:session-id sd)
                  :session-file (:session-file sd)
                  :message-count (count msgs)
                  :messages msgs
                  :tool-calls {}
                  :tool-order []})
                (emit/emit-context-updated! emit! ctx state value)))

            (map? value)
            (let [action-kind (or (:action/kind value)
                                  (get value "action/kind")
                                  (:type value)
                                  (get value "type"))
                  session-id* (or (:action/session-id value)
                                  (get value "action/session-id")
                                  (:session-id value)
                                  (get value "session-id")
                                  (get value "sessionId"))
                  entry-id    (or (:action/entry-id value)
                                  (get value "action/entry-id")
                                  (:entry-id value)
                                  (get value "entry-id")
                                  (get value "entryId"))]
              (cond
                (and (or (= action-kind :switch-session)
                         (= action-kind "switch-session")
                         (= action-kind "switch_session"))
                     (string? session-id*)
                     (not (str/blank? session-id*)))
                (do
                  (session/ensure-session-loaded-in! ctx session-id session-id*)
                  (let [_    (events/set-focus-session-id! state session-id*)
                        sd   (ss/get-session-data-in ctx session-id*)
                        msgs (message-source/session-messages ctx session-id*)]
                    (emit/emit-session-rehydration!
                     emit!
                     {:session-id (:session-id sd)
                      :session-file (:session-file sd)
                      :message-count (count msgs)
                      :messages msgs
                      :tool-calls {}
                      :tool-order []})
                    (emit/emit-context-updated! emit! ctx state session-id*)))

                (and (or (= action-kind :fork-session)
                         (= action-kind "fork-session")
                         (= action-kind "fork_session")
                         (= action-kind "fork-point"))
                     (string? entry-id)
                     (not (str/blank? entry-id)))
                (let [sd   (session/fork-session-in! ctx session-id entry-id)
                      sid  (:session-id sd)
                      _    (events/set-focus-session-id! state sid)
                      msgs (message-source/session-messages ctx sid)]
                  (emit/emit-session-rehydration!
                   emit!
                   {:session-id sid
                    :session-file (:session-file sd)
                    :message-count (count msgs)
                    :messages msgs
                    :tool-calls {}
                    :tool-order []})
                  (emit/emit-context-updated! emit! ctx state sid)))))

          "model-picker"
          (when (map? value)
            (let [provider (or (:provider value) (get value "provider"))
                  model-id (or (:id value) (get value "id"))
                  resolved (resolve-model provider model-id)]
              (when resolved
                (let [provider-str (name (:provider resolved))
                      model {:provider provider-str :id (:id resolved) :reasoning (:supports-reasoning resolved)}]
                  (session/set-model-in! ctx session-id model)
                  (emit/emit-command-result! emit! {:type "text"
                                                    :message (str "✓ Model set to " provider-str " " (:id resolved))})))))

          "thinking-picker"
          (when (string? value)
            (let [level  (keyword value)
                  result (session/set-thinking-level-in! ctx session-id level)]
              (emit/emit-command-result! emit! {:type "text"
                                                :message (str "✓ Thinking level set to " (name (:thinking-level result)))})))

          nil)
        (emit/emit-session-snapshots! emit! ctx state session-id)
        (response-frame (:id request)
                        "frontend_action_result"
                        true
                        {:accepted true
                         :request-id request-id})))))
