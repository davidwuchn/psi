(ns psi.agent-session.session-settings
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-state :as ss]
   [psi.ai.model-registry :as model-registry]))

(defn set-model-in!
  "Set the session model for `session-id`."
  [ctx session-id model]
  (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model model} {:origin :core}))

(defn set-thinking-level-in!
  "Set the thinking level for `session-id`."
  [ctx session-id level]
  (dispatch/dispatch! ctx :session/set-thinking-level {:session-id session-id :level level} {:origin :core}))

(defn cycle-model-in!
  "Cycle to the next available scoped model for `session-id`."
  [ctx session-id direction]
  (let [sd         (ss/get-session-data-in ctx session-id)
        candidates (seq (:scoped-models sd))
        next-m     (when candidates
                     (session/next-model candidates (:model sd) direction))]
    (when next-m
      (set-model-in! ctx session-id next-m))
    (ss/get-session-data-in ctx session-id)))

(defn cycle-thinking-level-in!
  "Cycle to the next thinking level for `session-id`."
  [ctx session-id]
  (let [sd    (ss/get-session-data-in ctx session-id)
        model (:model sd)]
    (when (:reasoning model)
      (let [next-l (session/next-thinking-level (:thinking-level sd) model)]
        (set-thinking-level-in! ctx session-id next-l)))
    (ss/get-session-data-in ctx session-id)))

(defn set-session-name-in!
  "Set the session name for `session-id`."
  [ctx session-id session-name]
  (dispatch/dispatch! ctx :session/set-session-name {:session-id session-id :name session-name} {:origin :core}))

(defn set-auto-compaction-in!
  "Enable or disable auto-compaction for `session-id`."
  [ctx session-id enabled?]
  (dispatch/dispatch! ctx :session/set-auto-compaction {:session-id session-id :enabled? enabled?} {:origin :core}))

(defn set-auto-retry-in!
  "Enable or disable auto-retry for `session-id`."
  [ctx session-id enabled?]
  (dispatch/dispatch! ctx :session/set-auto-retry {:session-id session-id :enabled? enabled?} {:origin :core}))

(defn reload-models-in!
  "Reload user + project custom models from disk for `session-id`'s effective cwd.
   Returns {:error string-or-nil :count int}."
  [ctx session-id]
  (let [cwd (ss/effective-cwd-in ctx session-id)]
    (model-registry/load-project-models!
     (str cwd "/.psi/models.edn")
     (model-registry/default-user-models-path))
    {:error (model-registry/get-load-error)
     :count (count (model-registry/all-models-seq))}))
