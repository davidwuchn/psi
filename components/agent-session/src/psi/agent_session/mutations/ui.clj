(ns psi.agent-session.mutations.ui
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.dispatch :as dispatch]))

(pco/defmutation set-widget-spec
  "Register or replace a declarative WidgetSpec for the calling extension.
   `spec` must be a valid psi.ui.widget-spec map (see psi.ui.widget-spec/validate-spec).
   Returns {:psi.ui/widget-spec-accepted? true} on success, or an error."
  [_ {:keys [psi/agent-session-ctx session-id spec]}]
  {::pco/op-name 'psi.ui/set-widget-spec
   ::pco/params  [:psi/agent-session-ctx :session-id :spec]
   ::pco/output  [:psi.ui/widget-spec-accepted?
                  :psi.ui/widget-spec-errors]}
  (let [{:keys [accepted? errors]}
        (dispatch/dispatch! agent-session-ctx
                            :session/ui-set-widget-spec
                            {:session-id   session-id
                             :extension-id (or (:extension-id spec) "unknown")
                             :spec         spec}
                            {:origin :mutations})]
    {:psi.ui/widget-spec-accepted? (boolean accepted?)
     :psi.ui/widget-spec-errors    errors}))

(pco/defmutation clear-widget-spec
  "Remove a declarative WidgetSpec by widget-id for the calling extension."
  [_ {:keys [psi/agent-session-ctx session-id extension-id widget-id]}]
  {::pco/op-name 'psi.ui/clear-widget-spec
   ::pco/params  [:psi/agent-session-ctx :session-id :extension-id :widget-id]
   ::pco/output  [:psi.ui/widget-spec-cleared?]}
  (dispatch/dispatch! agent-session-ctx
                      :session/ui-clear-widget-spec
                      {:session-id   session-id
                       :extension-id extension-id
                       :widget-id    widget-id}
                      {:origin :mutations})
  {:psi.ui/widget-spec-cleared? true})

(def all-mutations
  [set-widget-spec
   clear-widget-spec])
