(ns psi.app-runtime.context
  "Adapter-neutral context snapshot projection shared across interactive UIs."
  (:require
   [psi.agent-session.message-text :as message-text]
   [psi.agent-session.session-state :as ss]))

(defn context-snapshot
  "Build a canonical context snapshot for `session-id`.

   Includes a synthetic single-session snapshot before the runtime context index
   has any real entries, so handshake/bootstrap surfaces still reflect the
   current live session.

   Deliberately excludes per-prompt fork rows so lightweight context snapshots
   stay cheap even for large session journals. Fork-point expansion is provided
   by explicit selector payloads instead."
  [ctx active-session-id session-id]
  (let [active-session-id (or active-session-id session-id)
        sd               (ss/get-session-data-in ctx session-id)
        current-id       (:session-id sd)
        indexed-sessions (or (seq (ss/list-context-sessions-in ctx)) [])
        sessions*        (if (seq indexed-sessions)
                           (->> indexed-sessions
                                (sort-by (juxt :updated-at :session-id))
                                vec)
                           [(select-keys sd [:session-id :session-name :worktree-path :parent-session-id :created-at :updated-at])])
        active-id*       (or active-session-id current-id)
        slots            (mapv (fn [m]
                                 {:id                (:session-id m)
                                  :item-kind         "session"
                                  :name              (:session-name m)
                                  :display-name      (or (:display-name m)
                                                         (message-text/short-display-text (:session-name m)))
                                  :worktree-path     (:worktree-path m)
                                  :is-streaming      (boolean (and (= (:session-id m) current-id)
                                                                   (:is-streaming sd)))
                                  :is-active         (= (:session-id m) active-id*)
                                  :parent-session-id (:parent-session-id m)
                                  :created-at        (some-> (:created-at m) str)
                                  :updated-at        (some-> (:updated-at m) str)})
                               sessions*)]
    {:active-session-id active-id*
     :sessions          slots}))
