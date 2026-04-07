(ns psi.rpc.session.frontend-actions
  "Frontend action result handlers for RPC session workflows."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.app-runtime.navigation :as navigation]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.rpc.events :as events]
   [psi.rpc.session.emit :as emit]
   [psi.rpc.transport :refer [response-frame]]))

(defn handle-frontend-action-result!
  [{:keys [ctx request params state session-id resolve-model]}]
  (let [{:ui.result/keys [request-id action-name action-key status value error-message]}
        (ui-actions/action-result {:request-id (:request-id params)
                                   :action-name (:action-name params)
                                   :ui-action (:ui/action params)
                                   :status (:status params)
                                   :value (:value params)
                                   :error-message (:error-message params)})
        emit!       (emit/make-request-emitter (:emit-frame! request) state (:id request))
        nav-result* (volatile! nil)]
    (cond
      (= status :cancelled)
      (do
        (emit/emit-command-result! emit! {:type "text"
                                          :message (str "Cancelled " action-name ".")})
        (response-frame (:id request) "frontend_action_result" true {:accepted true}))

      (= status :failed)
      (do
        (emit/emit-command-result! emit! {:type "error"
                                          :message (or error-message
                                                       (str "Frontend action failed: " action-name))})
        (response-frame (:id request) "frontend_action_result" true {:accepted true}))

      :else
      (do
        (case action-key
          :select-resume-session
          (when (string? value)
            (when (.exists (io/file value))
              (let [nav (navigation/resume-session-result ctx state session-id value)]
                (vreset! nav-result* nav)
                (events/set-focus-session-id! state (:nav/session-id nav))
                (emit/emit-navigation-result! emit! ctx state nav))))

          :select-session
          (cond
            (string? value)
            (let [nav (navigation/switch-session-result ctx state session-id value)]
              (vreset! nav-result* nav)
              (events/set-focus-session-id! state (:nav/session-id nav))
              (emit/emit-navigation-result! emit! ctx state nav))

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
                (let [nav (navigation/switch-session-result ctx state session-id session-id*)]
                  (vreset! nav-result* nav)
                  (events/set-focus-session-id! state (:nav/session-id nav))
                  (emit/emit-navigation-result! emit! ctx state nav))

                (and (or (= action-kind :fork-session)
                         (= action-kind "fork-session")
                         (= action-kind "fork_session")
                         (= action-kind "fork-point"))
                     (string? entry-id)
                     (not (str/blank? entry-id)))
                (let [nav (navigation/fork-session-result ctx state session-id entry-id)]
                  (vreset! nav-result* nav)
                  (events/set-focus-session-id! state (:nav/session-id nav))
                  (emit/emit-navigation-result! emit! ctx state nav)))))

          :select-model
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

          :select-thinking-level
          (when (string? value)
            (let [level  (keyword value)
                  result (session/set-thinking-level-in! ctx session-id level)]
              (emit/emit-command-result! emit! {:type "text"
                                                :message (str "✓ Thinking level set to " (name (:thinking-level result)))})))

          nil)
        (when-not @nav-result*
          (emit/emit-session-snapshots! emit! ctx state session-id))
        (response-frame (:id request)
                        "frontend_action_result"
                        true
                        {:accepted true
                         :request-id request-id})))))
