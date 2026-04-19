(ns psi.agent-session.project-nrepl-commands
  "Formatting + command dispatch helpers for managed project nREPL operations."
  (:require
   [clojure.string :as str]
   [psi.agent-session.project-nrepl-attach :as project-nrepl-attach]
   [psi.agent-session.project-nrepl-config :as project-nrepl-config]
   [psi.agent-session.project-nrepl-eval :as project-nrepl-eval]
   [psi.agent-session.project-nrepl-started :as project-nrepl-started]
   [psi.agent-session.project-nrepl-runtime :as project-nrepl-runtime]
   [psi.agent-session.session-state :as ss]))

(defn format-project-nrepl-status
  [ctx session-id]
  (let [worktree-path (ss/session-worktree-path-in ctx session-id)
        instance      (project-nrepl-runtime/instance-in ctx worktree-path)]
    (if-not instance
      (str "── Project nREPL ─────────────────────\n"
           "  worktree : " worktree-path "\n"
           "  state    : absent\n"
           "───────────────────────────────────────")
      (str "── Project nREPL ─────────────────────\n"
           "  worktree : " (:worktree-path instance) "\n"
           "  mode     : " (name (:acquisition-mode instance)) "\n"
           "  state    : " (name (:lifecycle-state instance)) "\n"
           "  ready    : " (boolean (:readiness instance)) "\n"
           (when-let [{:keys [host port port-source]} (:endpoint instance)]
             (str "  endpoint : " host ":" port " [" (name port-source) "]\n"))
           (when-let [session-id* (:active-session-id instance)]
             (str "  session  : " session-id* "\n"))
           (when-let [last-eval (:last-eval instance)]
             (str "  last-eval: " (name (:status last-eval))
                  (when-let [value (:value last-eval)]
                    (str " value=" (pr-str value)))
                  "\n"))
           (when-let [last-error (:last-error instance)]
             (str "  error    : " (:message last-error) "\n"))
           "───────────────────────────────────────"))))

(defn- parse-tail
  [trimmed prefix]
  (some-> trimmed
          (subs (count prefix))
          str/trim
          not-empty))

(defn dispatch-project-nrepl-command
  [ctx session-id trimmed]
  (let [worktree-path (ss/session-worktree-path-in ctx session-id)]
    (cond
      (= trimmed "/project-repl")
      {:type :text :message (format-project-nrepl-status ctx session-id)}

      (= trimmed "/project-repl start")
      (let [cfg            (project-nrepl-config/resolve-config worktree-path)
            command-vector (project-nrepl-config/resolved-started-command-vector cfg)
            instance       (project-nrepl-started/start-instance-in! ctx worktree-path command-vector)]
        {:type :text
         :message (str "Started project nREPL for " (:worktree-path instance)
                       " at " (get-in instance [:endpoint :host]) ":" (get-in instance [:endpoint :port]))})

      (= trimmed "/project-repl attach")
      (let [cfg      (project-nrepl-config/resolve-config worktree-path)
            attach   (project-nrepl-config/resolved-attach-endpoint cfg)
            instance (project-nrepl-attach/attach-instance-in! ctx worktree-path attach)]
        {:type :text
         :message (str "Attached project nREPL for " (:worktree-path instance)
                       " at " (get-in instance [:endpoint :host]) ":" (get-in instance [:endpoint :port]))})

      (= trimmed "/project-repl interrupt")
      (let [result (project-nrepl-eval/interrupt-instance-in! ctx worktree-path)]
        {:type :text
         :message (str "Project nREPL interrupt: " (name (:status result))
                       (when-let [reason (:reason result)]
                         (str " (" (name reason) ")")))})

      (= trimmed "/project-repl stop")
      (do
        (if (= :started (:acquisition-mode (project-nrepl-runtime/instance-in ctx worktree-path)))
          (project-nrepl-started/stop-started-instance-in! ctx worktree-path)
          (project-nrepl-attach/detach-instance-in! ctx worktree-path))
        {:type :text
         :message (str "Stopped project nREPL for " worktree-path)})

      (str/starts-with? trimmed "/project-repl eval ")
      (let [code   (parse-tail trimmed "/project-repl eval ")
            result (project-nrepl-eval/eval-instance-in! ctx worktree-path code)]
        {:type :text
         :message (str "Project nREPL eval " (name (:status result))
                       (when-let [value (:value result)]
                         (str "\n" value))
                       (when-let [out (:out result)]
                         (str "\nout: " out))
                       (when-let [err (:err result)]
                         (str "\nerr: " err)))})

      :else nil)))
