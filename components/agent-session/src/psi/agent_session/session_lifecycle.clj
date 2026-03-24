(ns psi.agent-session.session-lifecycle
  "Session lifecycle operations — create, resume, fork, switch."
  (:require
   [clojure.java.io :as io]
   [psi.agent-session.compaction :as compaction]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.workflows :as wf]))

(defn new-session-in!
  "Start a fresh session (resets agent and session data).
  Dispatches session_before_switch / session_switch extension events.

   opts:
   - :spawn-mode (keyword) default :new-root"
  ([ctx] (new-session-in! ctx {}))
  ([ctx opts]
   (let [reg                  (:extension-registry ctx)
         {:keys [cancelled?]} (ext/dispatch-in reg "session_before_switch" {:reason :new})]
     (when-not cancelled?
       (wf/clear-all-in! (:workflow-registry ctx))
       (let [new-session-id (str (java.util.UUID/randomUUID))
             current-sd     (session/get-session-data-in ctx)
             worktree-path  (or (:worktree-path opts)
                                (:worktree-path current-sd))
             session-name   (:session-name opts)
             session-file   (when (:persist? ctx)
                              (let [session-dir (persist/session-dir-for (session/effective-cwd-in ctx))
                                    file        (persist/new-session-file-path session-dir new-session-id)]
                                (str file)))]
         (dispatch/dispatch! ctx
                             :session/new-initialize
                             {:new-session-id new-session-id
                              :worktree-path worktree-path
                              :session-name session-name
                              :spawn-mode (or (:spawn-mode opts) :new-root)
                              :session-file session-file}
                             {:origin :core})
         (dispatch/dispatch! ctx :session/retarget-runtime-prompt-metadata nil {:origin :core})
         (when session-name
           (session/journal-append-in! ctx (persist/session-info-entry session-name)))
         (session/journal-append-in! ctx
                                     (persist/thinking-level-entry
                                      (:thinking-level (session/get-session-data-in ctx))))
         (when-let [model (:model (session/get-session-data-in ctx))]
           (session/journal-append-in! ctx (persist/model-entry (:provider model) (:id model)))))
       (ext/dispatch-in reg "session_switch" {:reason :new})
       (session/get-session-data-in ctx)))))

(defn resume-session-in!
  "Load and resume a session from `session-path`.
  Parses the NDEDN file, migrates if needed, rebuilds the agent message list,
  and restores model/thinking-level from the journal."
  [ctx session-path]
  (let [reg                  (:extension-registry ctx)
        {:keys [cancelled?]} (ext/dispatch-in reg "session_before_switch" {:reason :resume})]
    (when-not cancelled?
      (wf/clear-all-in! (:workflow-registry ctx))
      (let [loaded (persist/load-session-file session-path)]
        (if-not loaded
          ;; File missing or invalid — treat as new session at that path
          (dispatch/dispatch! ctx
                              :session/resume-missing-initialize
                              {:session-path session-path}
                              {:origin :core})
          (let [{:keys [header entries]} loaded
                session-id             (:id header)
                current-sd             (session/get-session-data-in ctx)
                current-model          (:model current-sd)
                current-thinking-level (:thinking-level current-sd)
                ;; Restore model/thinking from last model/thinking entries.
                model-entry            (last (filter #(= :model (:kind %)) entries))
                thinking-entry         (last (filter #(= :thinking-level (:kind %)) entries))
                model-from-entry       (let [provider (get-in model-entry [:data :provider])
                                             model-id (get-in model-entry [:data :model-id])]
                                         (when (and provider model-id)
                                           {:provider provider
                                            :id       model-id}))
                ;; Some sessions (especially older ones) may have no model entry.
                ;; Fall back to the currently configured model in that case.
                model                  (or model-from-entry current-model)
                thinking-level         (or (get-in thinking-entry [:data :thinking-level])
                                           current-thinking-level
                                           :off)
                ;; Rebuild agent messages from journal (handles compaction)
                messages               (compaction/rebuild-messages-from-journal-entries (vec entries))]
            (dispatch/dispatch! ctx
                                :session/resume-loaded
                                {:session-id session-id
                                 :session-path session-path
                                 :header header
                                 :entries entries
                                 :worktree-path (or (:worktree-path header) (:cwd header))
                                 :entry-count (count entries)
                                 :thinking-level thinking-level
                                 :model model
                                 :messages (vec messages)}
                                {:origin :core})
            ;; Legacy sessions may not persist base prompt fields.
            (dispatch/dispatch! ctx :session/ensure-base-system-prompt nil {:origin :core})
            (dispatch/dispatch! ctx :session/retarget-runtime-prompt-metadata nil {:origin :core})))
        (ext/dispatch-in reg "session_switch" {:reason :resume})
        (session/get-session-data-in ctx)))))

(defn fork-session-in!
  "Fork the session from `entry-id`, creating a new session branch.

  Persistence semantics:
  - child session gets a new session id and its own session file
  - child journal contains entries/messages up to `entry-id`
  - child session file is written immediately with `:parent-session`
    pointing at the parent session file (when available)"
  [ctx entry-id]
  (let [reg (:extension-registry ctx)]
    (ext/dispatch-in reg "session_before_fork" {:entry-id entry-id})
    (let [parent-sd           (session/get-session-data-in ctx)
          parent-session-id   (:session-id parent-sd)
          parent-session-file (:session-file parent-sd)
          new-session-id      (str (java.util.UUID/randomUUID))
          messages            (vec (persist/messages-up-to-in ctx entry-id))
          branch-entries      (vec (persist/entries-up-to-in ctx entry-id))
          session-file        (when (:persist? ctx)
                                (let [session-dir (persist/session-dir-for (session/effective-cwd-in ctx))
                                      file        (persist/new-session-file-path session-dir new-session-id)]
                                  (str file)))]
      (dispatch/dispatch! ctx
                          :session/fork-initialize
                          {:new-session-id new-session-id
                           :branch-entries branch-entries
                           :entry-id entry-id
                           :entry-count (count branch-entries)
                           :parent-session-id parent-session-id
                           :parent-session-path parent-session-file
                           :session-file session-file
                           :messages messages}
                          {:origin :core})

      ;; Fork persistence: create/write child file immediately with lineage header.
      (when session-file
        (let [file (io/file session-file)]
          (persist/flush-journal! file
                                  new-session-id
                                  (session/effective-cwd-in ctx)
                                  parent-session-id
                                  parent-session-file
                                  branch-entries)))

      (ext/dispatch-in reg "session_fork" {})
      (session/get-session-data-in ctx))))
