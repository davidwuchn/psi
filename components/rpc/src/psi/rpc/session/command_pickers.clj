(ns psi.rpc.session.command-pickers
  (:require
   [psi.ai.model-registry :as model-registry]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.rpc.session.emit :as emit]))

(defn handle-picker-command!
  [request-id emit! trimmed]
  (case trimmed
    "/model"
    (emit/emit-frontend-action-request!
     emit!
     request-id
     (ui-actions/model-picker-action
      (->> (model-registry/all-models-seq)
           (sort-by (juxt :provider :id))
           (mapv (fn [m]
                   {:provider  (name (:provider m))
                    :id        (:id m)
                    :reasoning (boolean (:supports-reasoning m))})))))

    "/thinking"
    (emit/emit-frontend-action-request!
     emit!
     request-id
     (ui-actions/thinking-picker-action))))
