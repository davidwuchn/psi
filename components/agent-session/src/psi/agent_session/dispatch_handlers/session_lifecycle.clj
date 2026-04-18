(ns psi.agent-session.dispatch-handlers.session-lifecycle
  "Handlers for session creation, resumption, forking, and child creation:
   new-initialize, resume-loaded, fork-initialize, create-child,
   resume-missing-initialize."
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-handlers.session-state :as ss]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.session-state :as session]))

(defn register!
  "Register session lifecycle handlers. Called once during context creation."
  [_ctx]
  (dispatch/register-handler!
   :session/new-initialize
   (fn [ctx {:keys [session-id new-session-id worktree-path session-name spawn-mode session-file]}]
     (let [current-sd (or (session/get-session-data-in ctx session-id)
                          (assoc (session-data/initial-session (:session-defaults ctx))
                                 :provider-error-replies []))
           payload    {:new-session-id new-session-id
                       :worktree-path  worktree-path
                       :session-name   session-name
                       :spawn-mode     spawn-mode
                       :session-file   session-file}]
       {:root-state-update #(ss/initialize-new-session-state % current-sd payload)
        :return-key        (ss/session-data-path new-session-id)
        :effects [{:effect/type :runtime/agent-reset}]})))

  (dispatch/register-handler!
   :session/resume-loaded
   (fn [ctx {:keys [session-id source-session-id session-path header entries model thinking-level messages]}]
     (let [source-sid (or source-session-id session-id)
           current-sd (session/get-session-data-in ctx source-sid)
           payload    {:session-id        session-id
                       :source-session-id source-sid
                       :session-path      session-path
                       :header            header
                       :entries           entries
                       :model             model
                       :thinking-level    thinking-level}]
       {:root-state-update #(ss/initialize-resumed-session-state % current-sd payload)
        :return-key        (ss/session-data-path session-id)
        :effects (cond-> [{:effect/type :runtime/agent-reset}
                          {:effect/type :runtime/agent-set-thinking-level
                           :level       thinking-level}]
                   model    (conj {:effect/type :runtime/agent-set-model :model model})
                   messages (conj {:effect/type :runtime/agent-replace-messages :messages messages}))})))

  (dispatch/register-handler!
   :session/fork-initialize
   (fn [ctx {:keys [session-id new-session-id branch-entries session-file messages]}]
     (let [parent-sd (session/get-session-data-in ctx session-id)
           payload   {:new-session-id new-session-id
                      :branch-entries branch-entries
                      :session-file   session-file}]
       {:root-state-update #(ss/initialize-forked-session-state % parent-sd payload)
        :return-key        (ss/session-data-path new-session-id)
        :effects (when messages
                   [{:effect/type :runtime/agent-replace-messages :messages messages}])})))

  (dispatch/register-handler!
   :session/create-child
   (fn [ctx {:keys [session-id child-session-id session-name system-prompt tool-defs thinking-level developer-prompt developer-prompt-source preloaded-messages cache-breakpoints]}]
     (let [parent-sd (session/get-session-data-in ctx session-id)]
       {:root-state-update #(ss/initialize-child-session-state % parent-sd
                                                               {:child-session-id       child-session-id
                                                                :session-name           session-name
                                                                :system-prompt          system-prompt
                                                                :tool-defs              tool-defs
                                                                :thinking-level         thinking-level
                                                                :developer-prompt       developer-prompt
                                                                :developer-prompt-source developer-prompt-source
                                                                :preloaded-messages     preloaded-messages
                                                                :cache-breakpoints      cache-breakpoints})
        :effects [{:effect/type :projection/context-changed
                   :session-id child-session-id
                   :reason :session/create-child}]
        :return child-session-id})))

  (dispatch/register-handler!
   :session/resume-missing-initialize
   (fn [ctx {:keys [session-id session-path]}]
     (let [current-sd (session/get-session-data-in ctx session-id)]
       {:root-state-update #(ss/initialize-resume-missing-state % current-sd session-path)
        :return-key        (ss/session-data-path session-id)}))))
