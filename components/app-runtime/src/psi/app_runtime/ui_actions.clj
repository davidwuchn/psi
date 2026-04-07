(ns psi.app-runtime.ui-actions
  "Adapter-neutral interactive action models shared across UIs."
  (:require
   [psi.app-runtime.selectors :as selectors]))

(defn frontend-action-key
  [ui-action action-name]
  (let [k (keyword (or (some-> ui-action :ui/action-name name)
                       action-name
                       ""))]
    (case k
      :resume-selector :select-resume-session
      :context-session-selector :select-session
      :model-picker :select-model
      :thinking-picker :select-thinking-level
      k)))

(defn normalize-result-status
  [status]
  (case status
    :submitted :submitted
    "submitted" :submitted
    :cancelled :cancelled
    "cancelled" :cancelled
    :failed :failed
    "failed" :failed
    nil))

(defn- session-selector-value->action
  [value]
  (cond
    (string? value)
    {:action/kind :switch-session
     :action/session-id value}

    (map? value)
    (let [action-kind (or (:action/kind value)
                          (get value "action/kind")
                          (:type value)
                          (get value "type"))
          session-id  (or (:action/session-id value)
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
             (string? session-id))
        {:action/kind :switch-session
         :action/session-id session-id}

        (and (or (= action-kind :fork-session)
                 (= action-kind "fork-session")
                 (= action-kind "fork_session")
                 (= action-kind "fork-point"))
             (string? entry-id))
        {:action/kind :fork-session
         :action/session-id session-id
         :action/entry-id entry-id}

        :else nil))

    :else
    nil))

(defn action-result
  [{:keys [request-id action-name ui-action status value error-message]}]
  (let [action-key (frontend-action-key ui-action action-name)
        value*     (case action-key
                     :select-session (session-selector-value->action value)
                     value)]
    {:ui.result/request-id    request-id
     :ui.result/action-name   action-name
     :ui.result/action-key    action-key
     :ui.result/ui-action     ui-action
     :ui.result/status        (normalize-result-status status)
     :ui.result/value         value*
     :ui.result/error-message error-message}))

(def thinking-levels
  ["off" "minimal" "low" "medium" "high" "xhigh"])

(defn select-action
  [{:keys [action-id action-name prompt order items on-submit legacy]}]
  {:ui/action-id   action-id
   :ui/action-kind :select
   :ui/action-name action-name
   :ui/prompt      prompt
   :ui/order       (or order :default)
   :ui/items       (vec items)
   :ui/on-submit   on-submit
   :ui/legacy      legacy})

(defn context-session-action
  [selector]
  (select-action
   {:action-id :context-session-selector
    :action-name :select-session
    :prompt (:selector/prompt selector)
    :order :preserve
    :items (mapv (fn [item]
                   {:ui.item/id    (:item/id item)
                    :ui.item/label (or (:item/default-label item)
                                       (selectors/selector-item->default-label item))
                    :ui.item/value (:item/action item)
                    :ui.item/meta  item})
                 (:selector/items selector))
    :on-submit {:submit/kind :selector-action}
    :legacy {:action-name "context-session-selector"
             :payload selector}}))

(defn resume-session-action
  [query-result]
  (let [sessions (vec (or (:psi.session/list query-result) []))]
    (select-action
     {:action-id :resume-selector
      :action-name :select-resume-session
      :prompt "Select a session to resume"
      :order :default
      :items (mapv (fn [session]
                     {:ui.item/id    (:psi.session-info/path session)
                      :ui.item/label (or (:psi.session-info/name session)
                                         (:psi.session-info/path session))
                      :ui.item/value (:psi.session-info/path session)
                      :ui.item/meta  session})
                   sessions)
      :on-submit {:submit/kind :resume-session-path}
      :legacy {:action-name "resume-selector"
               :payload {:query query-result}}})))

(defn model-picker-action
  [models]
  (select-action
   {:action-id :model-picker
    :action-name :select-model
    :prompt "Select a model"
    :order :default
    :items (mapv (fn [model]
                   {:ui.item/id    [(:provider model) (:id model)]
                    :ui.item/label (str (:provider model) " " (:id model)
                                        (when (:reasoning model) " [reasoning]"))
                    :ui.item/value {:provider (:provider model)
                                    :id (:id model)}
                    :ui.item/meta  model})
                 models)
    :on-submit {:submit/kind :set-model}
    :legacy {:action-name "model-picker"
             :payload {:models (vec models)}}}))

(defn thinking-picker-action
  ([]
   (thinking-picker-action thinking-levels))
  ([levels]
   (select-action
    {:action-id :thinking-picker
     :action-name :select-thinking-level
     :prompt "Select a thinking level"
     :order :default
     :items (mapv (fn [level]
                    {:ui.item/id level
                     :ui.item/label level
                     :ui.item/value level})
                  levels)
     :on-submit {:submit/kind :set-thinking-level}
     :legacy {:action-name "thinking-picker"
              :payload {:levels (vec levels)}}})))
