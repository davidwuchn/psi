(ns extensions.plan-state-learning
  "Automate PLAN/STATE/LEARNING maintenance after non-PSL commits.

   Trigger:
   - extension event: git_head_changed

   Behavior:
   - skip when latest commit subject contains [psi:psl-auto]
   - send one user-style prompt turn to the agent with source commit context
   - agent updates PLAN.md, STATE.md, LEARNING.md and commits changes
   - emit visible transcript messages via psi.extension/send-message"
  (:require
   [clojure.string :as str]))

(def ^:private marker "[psi:psl-auto]")
(def ^:private custom-type "plan-state-learning")

(defn- squish
  [s]
  (-> (str (or s ""))
      (str/replace #"\s+" " ")
      (str/trim)))

(defn- tool-content
  [tool-result]
  (str (or (:psi.extension.tool/content tool-result) "")))

(defn- bash!
  [mutate-fn command]
  (mutate-fn 'psi.extension.tool/bash {:command command :timeout 30}))

(defn- send-message!
  [mutate-fn text]
  (mutate-fn 'psi.extension/send-message
             {:role "assistant"
              :content text
              :custom-type custom-type}))

(defn- send-prompt!
  [mutate-fn text]
  (mutate-fn 'psi.extension/send-prompt
             {:content text
              :source custom-type}))

(defn- latest-commit
  [mutate-fn]
  (let [result (bash! mutate-fn "git log -1 --pretty=format:%H%n%s")
        out    (tool-content result)
        [sha subject] (str/split-lines out)]
    {:sha (squish sha)
     :subject (squish subject)}))

(defn- psl-prompt
  [{:keys [source-sha subject previous-head cwd]}]
  (let [source7 (subs source-sha 0 (min 7 (count source-sha)))]
    (str
     "PSL follow-up for commit " source7 "\n"
     "\n"
     "Context:\n"
     "- source-sha: " source-sha "\n"
     "- previous-head: " (or previous-head "unknown") "\n"
     "- cwd: " (or cwd "") "\n"
     "- subject: " (squish subject) "\n"
     "\n"
     "Task:\n"
     "1) Update PLAN.md and STATE.md\n"
     "2) Commit those changes\n"
     "3) Update LEARNING.md\n"
     "4) Commit LEARNING.md\n"
     "\n"
     "Constraints:\n"
     "- commit messages should summarise the changes"
     "- Do not use git add -A or git add .\n"
     "- Only stage explicit target files for each commit.\n"
     "- If a phase has no meaningful changes, skip that commit and explain why in your final response.\n")))

(defn- handle-git-head-changed
  [mutate-fn {:keys [head previous-head cwd]}]
  (let [{:keys [sha subject]} (latest-commit mutate-fn)
        source-sha (or (not-empty sha) head "unknown")
        source7    (subs source-sha 0 (min 7 (count source-sha)))]
    (if (str/includes? subject marker)
      (do
        (send-message! mutate-fn
                       (str "PSL skipped for " source7 " (self commit marker " marker ")."))
        {:skip? true :reason :self-commit :source-sha source-sha :subject subject})
      (let [_            (send-message! mutate-fn (str "PSL sync start for " source7 "."))
            prompt       (psl-prompt {:source-sha source-sha
                                      :subject subject
                                      :previous-head previous-head
                                      :cwd cwd})
            prompt-res   (send-prompt! mutate-fn prompt)
            accepted?    (true? (:psi.extension/prompt-accepted? prompt-res))
            delivery     (:psi.extension/prompt-delivery prompt-res)
            _            (send-message! mutate-fn
                                        (if accepted?
                                          (str "PSL prompt queued via " (name delivery) ".")
                                          "PSL prompt was not accepted."))]
        {:skip? false
         :source-sha source-sha
         :subject subject
         :prompt-accepted? accepted?
         :prompt-delivery delivery}))))

(defn init
  [api]
  (let [mutate-fn (:mutate api)]
    (mutate-fn 'psi.extension/register-handler
               {:event-name "git_head_changed"
                :handler-fn (fn [ev]
                              (try
                                (handle-git-head-changed mutate-fn ev)
                                (catch Exception e
                                  (send-message! mutate-fn
                                                 (str "PSL error: " (ex-message e)))
                                  {:error (ex-message e)})))})
    (send-message! mutate-fn "PSL extension loaded.")))
