(ns psi.rpc.session.navigation
  "Session navigation/new-switch-fork helpers for RPC session workflows."
  (:require
   [clojure.string]
   [psi.app-runtime.navigation :as navigation]
   [psi.rpc.events :as events]
   [psi.rpc.session.emit :as emit]
   [psi.rpc.transport :refer [response-frame]]))

(defn- emit-navigation!
  [emit! ctx state {:keys [nav/session-id nav/session-file nav/rehydration nav/context-snapshot]}]
  (events/set-focus-session-id! state session-id)
  (emit/emit-session-rehydration! emit! rehydration)
  (emit! "session/updated" (events/session-updated-payload ctx session-id))
  (emit! "footer/updated" (assoc (events/footer-updated-payload ctx session-id)
                                  :session-id session-id))
  (emit! "context/updated" context-snapshot)
  {:session-id session-id
   :session-file session-file})

(defn handle-new-session!
  [{:keys [ctx request state on-new-session!]}]
  (let [source-session-id (events/focus-session-id state)
        nav               (navigation/new-session-result ctx state source-session-id {:on-new-session! on-new-session!})
        emit!             (emit/make-request-emitter (:emit-frame! request) state (:id request))
        response-data     (emit-navigation! emit! ctx state nav)]
    (response-frame (:id request) "new_session" true response-data)))

(defn handle-switch-session!
  [{:keys [ctx request params state session-id]}]
  (let [nav (if-let [sid (:session-id params)]
              (do
                (when-not (and (string? sid) (not (clojure.string/blank? sid)))
                  (throw (ex-info "invalid request parameter :session-id: non-empty string"
                                  {:error-code "request/invalid-params"})))
                (navigation/switch-session-result ctx state session-id sid))
              (navigation/resume-session-result ctx state session-id (get params :session-path)))
        emit! (emit/make-request-emitter (:emit-frame! request) state (:id request))
        response-data (emit-navigation! emit! ctx state nav)]
    (response-frame (:id request) "switch_session" true response-data)))

(defn handle-fork!
  [{:keys [ctx request params state session-id]}]
  (let [nav           (navigation/fork-session-result ctx state session-id (:entry-id params))
        emit!         (emit/make-request-emitter (:emit-frame! request) state (:id request))
        response-data (emit-navigation! emit! ctx state nav)]
    (response-frame (:id request) "fork" true response-data)))
