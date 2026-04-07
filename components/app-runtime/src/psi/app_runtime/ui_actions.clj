(ns psi.app-runtime.ui-actions
  "Adapter-neutral interactive action models shared across UIs."
  (:require
   [psi.app-runtime.selectors :as selectors]))

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
