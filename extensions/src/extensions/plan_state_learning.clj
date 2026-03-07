(ns extensions.plan-state-learning
  "Automate PLAN/STATE/LEARNING maintenance after non-PSL commits.

   Trigger:
   - extension event: git_head_changed

   Behavior:
   - skip when latest commit subject contains [psi:psl-auto]
   - create a PSL workflow that runs after the current agent turn completes
   - workflow job: sends one user-style prompt turn to the agent with source commit context
   - agent updates PLAN.md, STATE.md, LEARNING.md and commits changes
   - emit visible transcript messages via psi.extension/send-message

   Ordering:
   - git_head_changed fires during/after the triggering agent turn
   - the workflow job runs after the session goes idle (deferred send-prompt path)
   - this ensures PSL output appears after the commit turn, not before it"
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]))

(def ^:private marker "[psi:psl-auto]")
(def ^:private custom-type "plan-state-learning")
(def ^:private workflow-type :psl)

;; ---------------------------------------------------------------------------
;; Module state (set during init)
;; ---------------------------------------------------------------------------

(defonce ^:private state
  (atom {:mutate-fn nil
         :query-fn  nil}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

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
  [{:keys [source-sha]}]
  (let [source7 (subs source-sha 0 (min 7 (count source-sha)))]
    (str
     "PSL follow-up for commit " source7 "\n"
     "\n"
     "Task:\n"
     "1) Update PLAN.md and STATE.md\n"
     "2) Commit those changes\n"
     "3) Update LEARNING.md\n"
     "4) Commit LEARNING.md\n"
     "\n"
     "Constraints:\n"
     "- Commit message title must summarise the learning or plan/state change and finish with [psi:psl-auto].\n"
     "- Do not use git add -A or git add .\n"
     "- Only stage explicit target files for each commit.\n"
     "- If a phase has no meaningful changes, skip that commit and explain why in your final response.\n")))

;; ---------------------------------------------------------------------------
;; Workflow job (runs in background future after session goes idle)
;; ---------------------------------------------------------------------------

(defn- psl-job
  [{:keys [source-sha subject]}]
  (let [mutate-fn (:mutate-fn @state)]
    (try
      (if (str/includes? subject marker)
        {:status :done :skipped? true}
        (let [prompt     (psl-prompt {:source-sha source-sha})
              prompt-res (send-prompt! mutate-fn prompt)
              accepted?  (true? (:psi.extension/prompt-accepted? prompt-res))
              delivery   (:psi.extension/prompt-delivery prompt-res)]
          (send-message! mutate-fn
                         (if accepted?
                           "Updating PLAN.md, STATE.md and LEARNING.md …"
                           "Failed to update PLAN.md, STATE.md and LEARNING.md"))
          {:status :done :accepted? accepted? :delivery delivery}))
      (catch Exception e
        (send-message! mutate-fn (str "PSL error: " (ex-message e)))
        {:status :error :error (ex-message e)}))))

;; ---------------------------------------------------------------------------
;; Statechart
;; ---------------------------------------------------------------------------

(defn- psl-start-script
  [_ _data]
  [{:op :assign
    :data {:psl/phase :running}}])

(defn- psl-invoke-params
  [_ data]
  {:source-sha (:psl/source-sha data)
   :subject    (:psl/subject data)
   :cwd        (:psl/cwd data)})

(defn- psl-job-fn
  [params]
  (let [result (psl-job params)]
    {:status (or (:status result) :done)
     :result result}))

(defn- ev-status
  [data]
  (keyword (or (some-> data (get-in [:_event :data :status]) name) :error)))

(defn- result-done?    [_ data] (= :done  (ev-status data)))

(defn- psl-done-script
  [_ data]
  (let [result (get-in data [:_event :data :result])]
    [{:op :assign
      :data {:psl/phase  :done
             :psl/result result}}]))

(defn- psl-error-script
  [_ data]
  (let [result (get-in data [:_event :data :result])]
    [{:op :assign
      :data {:psl/phase  :error
             :psl/result result}}]))

(def ^:private psl-chart
  (chart/statechart
   {:id :psl-chart}
   (ele/state {:id :idle}
              (ele/transition {:event :psl/start :target :running}
                              (ele/script {:expr psl-start-script})))

   (ele/state {:id :running}
              (ele/invoke {:id   :psl-runner
                           :type :future
                           :params psl-invoke-params
                           :src  psl-job-fn})
              (ele/transition {:event :done.invoke.psl-runner
                               :target :done
                               :cond   result-done?}
                              (ele/script {:expr psl-done-script}))
              (ele/transition {:event :done.invoke.psl-runner
                               :target :error}
                              (ele/script {:expr psl-error-script})))

   (ele/final {:id :done})
   (ele/final {:id :error})))

;; ---------------------------------------------------------------------------
;; Workflow registration
;; ---------------------------------------------------------------------------

(defn- register-workflow-type!
  []
  (let [mutate-fn (:mutate-fn @state)]
    (mutate-fn 'psi.extension.workflow/register-type
               {:type            workflow-type
                :description     "PSL: update PLAN/STATE/LEARNING after a commit"
                :chart           psl-chart
                :start-event     :psl/start
                :initial-data-fn (fn [input]
                                   {:psl/source-sha (:source-sha input)
                                    :psl/subject    (:subject input)
                                    :psl/cwd        (:cwd input)
                                    :psl/phase      :idle
                                    :psl/result     nil})
                :public-data-fn  (fn [data]
                                   (select-keys data
                                                [:psl/source-sha
                                                 :psl/subject
                                                 :psl/phase
                                                 :psl/result]))})))

;; ---------------------------------------------------------------------------
;; Event handler
;; ---------------------------------------------------------------------------

(defn- handle-git-head-changed
  [mutate-fn {:keys [head cwd]}]
  (let [{:keys [sha subject]} (latest-commit mutate-fn)
        source-sha (or (not-empty sha) head "unknown")
        source7    (subs source-sha 0 (min 7 (count source-sha)))]
    ;; Fast skip check — avoid creating a workflow for self-commits
    (if (str/includes? subject marker)
      (do
        (send-message! mutate-fn
                       (str "PSL skipped for " source7 " (self commit marker " marker ")."))
        {:skip? true :reason :self-commit :source-sha source-sha :subject subject})
      ;; Create workflow — the job runs after this agent turn completes (deferred)
      (let [created (mutate-fn 'psi.extension.workflow/create
                               {:type  workflow-type
                                :input {:source-sha    source-sha
                                        :subject       subject
                                        :cwd           (or cwd (System/getProperty "user.dir"))}})]
        {:skip? false
         :source-sha source-sha
         :subject subject
         :workflow-created? (true? (:psi.extension.workflow/created? created))}))))

;; ---------------------------------------------------------------------------
;; Extension entry point
;; ---------------------------------------------------------------------------

(defn init
  [api]
  (let [mutate-fn (:mutate api)
        query-fn  (:query api)]
    (swap! state assoc :mutate-fn mutate-fn :query-fn query-fn)
    (register-workflow-type!)
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
