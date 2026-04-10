(ns psi.agent-session.dispatch-handlers.ui-handlers
  "Handlers for session/ui-* events — widget, dialog, status, renderer, tool-renderer."
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as session]
   [psi.ui.state :as ui-state]))

;;; UI state helpers

(def ^:private ui-state-path [:ui :extension-ui])

(defn- get-ui-state [ctx]
  (or (session/get-state-value-in ctx ui-state-path) {}))

(defn- ui-root-update [new-ui-state]
  (fn [root] (assoc-in root ui-state-path new-ui-state)))

;;; Registration

(defn register!
  "Register all session/ui-* handlers. Called once during context creation."
  [_ctx]
  (dispatch/register-handler!
   :session/ui-set-widget-spec
   (fn [ctx {:keys [extension-id spec]}]
     (let [ext-id           (or extension-id (:extension-id spec) "unknown")
           {:keys [state result]} (ui-state/set-widget-spec (get-ui-state ctx) ext-id spec)]
       {:root-state-update (ui-root-update state)
        :return (if result
                  {:accepted? false :errors (:errors result)}
                  {:accepted? true  :errors nil})})))

  (dispatch/register-handler!
   :session/ui-set-widget
   (fn [ctx {:keys [extension-id widget-id placement content]}]
     (let [{:keys [state]} (ui-state/set-widget (get-ui-state ctx) extension-id widget-id placement content)]
       {:root-state-update (ui-root-update state)
        :return {:accepted? true}})))

  (dispatch/register-handler!
   :session/ui-clear-widget
   (fn [ctx {:keys [extension-id widget-id]}]
     (let [{:keys [state]} (ui-state/clear-widget (get-ui-state ctx) extension-id widget-id)]
       {:root-state-update (ui-root-update state)
        :return {:cleared? true}})))

  (dispatch/register-handler!
   :session/ui-clear-widget-spec
   (fn [ctx {:keys [extension-id widget-id]}]
     (let [{:keys [state]} (ui-state/clear-widget-spec (get-ui-state ctx) extension-id widget-id)]
       {:root-state-update (ui-root-update state)
        :return {:cleared? true}})))

  (dispatch/register-handler!
   :session/ui-resolve-dialog
   (fn [ctx {:keys [dialog-id result]}]
     (let [{:keys [state result]} (ui-state/resolve-dialog (get-ui-state ctx) dialog-id result)
           accepted? result]
       {:root-state-update (ui-root-update state)
        :return {:accepted? (boolean accepted?)}})))

  (dispatch/register-handler!
   :session/ui-cancel-dialog
   (fn [ctx _]
     (let [{:keys [state result]} (ui-state/cancel-dialog (get-ui-state ctx))]
       {:root-state-update (ui-root-update state)
        :return {:accepted? (boolean result)}})))

  (dispatch/register-handler!
   :session/ui-set-status
   (fn [ctx {:keys [extension-id text]}]
     (let [{:keys [state]} (ui-state/set-status (get-ui-state ctx) extension-id text)]
       {:root-state-update (ui-root-update state)})))

  (dispatch/register-handler!
   :session/ui-clear-status
   (fn [ctx {:keys [extension-id]}]
     (let [{:keys [state]} (ui-state/clear-status (get-ui-state ctx) extension-id)]
       {:root-state-update (ui-root-update state)})))

  (dispatch/register-handler!
   :session/ui-register-tool-renderer
   (fn [ctx {:keys [tool-name extension-id render-call-fn render-result-fn]}]
     (let [{:keys [state]} (ui-state/register-tool-renderer (get-ui-state ctx) tool-name extension-id render-call-fn render-result-fn)]
       {:root-state-update (ui-root-update state)})))

  (dispatch/register-handler!
   :session/ui-register-message-renderer
   (fn [ctx {:keys [custom-type extension-id render-fn]}]
     (let [{:keys [state]} (ui-state/register-message-renderer (get-ui-state ctx) custom-type extension-id render-fn)]
       {:root-state-update (ui-root-update state)})))

  (dispatch/register-handler!
   :session/ui-set-tools-expanded
   (fn [ctx {:keys [expanded?]}]
     (let [{:keys [state]} (ui-state/set-tools-expanded (get-ui-state ctx) expanded?)]
       {:root-state-update (ui-root-update state)
        :return {:tools-expanded? (boolean expanded?)}})))

  (dispatch/register-handler!
   :session/ui-notify
   (fn [ctx {:keys [extension-id message level]}]
     (let [{:keys [state]} (ui-state/notify (get-ui-state ctx) extension-id message level)]
       {:root-state-update (ui-root-update state)})))

  (dispatch/register-handler!
   :session/ui-request-dialog
   (fn [_ctx {:keys [kind ext-id title message options placeholder]}]
     (let [p      (promise)
           dialog (merge {:id           (str (java.util.UUID/randomUUID))
                          :kind         kind
                          :extension-id ext-id
                          :promise      p
                          :resolved?    false}
                         (when title       {:title title})
                         (when message     {:message message})
                         (when options     {:options options})
                         (when placeholder {:placeholder placeholder}))]
       {:root-state-update
        (fn [root]
          (let [pending-path [:ui :extension-ui :dialog-queue :pending]
                active-path  [:ui :extension-ui :dialog-queue :active]
                root'        (update-in root pending-path conj dialog)
                active       (get-in root' active-path)
                pending'     (get-in root' pending-path)]
            (if (and (nil? active) (seq pending'))
              (-> root'
                  (assoc-in active-path (first pending'))
                  (assoc-in pending-path (vec (rest pending'))))
              root')))
        :return p})))

  (dispatch/register-handler!
   :session/ui-dismiss-expired
   (fn [ctx _]
     (let [{:keys [state]} (ui-state/dismiss-expired (get-ui-state ctx))]
       {:root-state-update (ui-root-update state)})))

  (dispatch/register-handler!
   :session/ui-dismiss-overflow
   (fn [ctx _]
     (let [{:keys [state]} (ui-state/dismiss-overflow (get-ui-state ctx))]
       {:root-state-update (ui-root-update state)}))))
