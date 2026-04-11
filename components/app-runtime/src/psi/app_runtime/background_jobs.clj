(ns psi.app-runtime.background-jobs
  "Compatibility façade over the canonical background-job view model."
  (:require
   [psi.app-runtime.background-job-view :as view]))

(def default-list-statuses view/default-list-statuses)

(defn status-order
  [status]
  (view/status-order status))

(defn sort-jobs
  [jobs]
  (view/sort-jobs jobs))

(defn job-summary
  [job]
  (view/job-summary job))

(defn jobs-summary
  ([jobs]
   (view/jobs-summary jobs))
  ([jobs opts]
   (view/jobs-summary jobs opts)))

(defn job-detail
  [job]
  (view/job-detail job))

(defn cancel-job-summary
  [job-id job]
  (view/cancel-job-summary job-id job))
