(ns psi.agent-session.workflow-execution
  "Impure execution helpers for canonical deterministic workflow runs.

   This slice bridges canonical workflow definitions/runs to actual bounded
   session execution for workflow attempts. It now provides:
   - materialize step inputs from canonical bindings
   - render legacy-compatible prompt templates
   - create one attempt child session for the current step
   - prompt that session
   - record a canonical structured result envelope back onto the workflow run
   - loop execution across sequential steps until terminal or blocked state
   - resume a blocked run and continue execution with a fresh attempt"
  (:require
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.agent-session.workflow-attempts :as workflow-attempts]
   [psi.agent-session.workflow-progression :as workflow-progression]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- get-path*
  [m path]
  (reduce (fn [acc k]
            (when (some? acc)
              (get acc k)))
          m
          path))

(defn binding-source-value
  [workflow-run {:keys [source path]}]
  (case source
    :workflow-input
    (get-path* (:workflow-input workflow-run) path)

    :step-output
    (let [[step-id & more] path
          accepted-result  (get-in workflow-run [:step-runs step-id :accepted-result])]
      (get-path* accepted-result more))

    :workflow-runtime
    (get-path* {:run-id (:run-id workflow-run)
                :current-step-id (:current-step-id workflow-run)
                :status (:status workflow-run)}
               path)

    nil))

(defn materialize-step-inputs
  [workflow-run step-id]
  (let [bindings (get-in workflow-run [:effective-definition :steps step-id :input-bindings])]
    (reduce-kv (fn [acc k ref]
                 (assoc acc k (binding-source-value workflow-run ref)))
               {}
               (or bindings {}))))

(defn render-prompt-template
  [prompt-template step-inputs]
  (let [input-text    (or (:input step-inputs) "")
        original-text (or (:original step-inputs) "")]
    (-> (or prompt-template "$INPUT")
        (str/replace "$INPUT" (str input-text))
        (str/replace "$ORIGINAL" (str original-text)))))

(defn step-prompt
  [workflow-run step-id]
  (let [step-def     (get-in workflow-run [:effective-definition :steps step-id])
        step-inputs  (materialize-step-inputs workflow-run step-id)]
    {:step-inputs step-inputs
     :prompt     (render-prompt-template (:prompt-template step-def) step-inputs)}))

(defn- assistant-message-text
  [assistant-message]
  (or (:content assistant-message)
      (some->> (:content assistant-message)
               (filter map?)
               (some (fn [block]
                       (when (= :text (:type block))
                         (:text block)))))
      ""))

(defn execute-current-step!
  "Execute the current workflow step as one bounded child-session attempt.

   Returns {:run-id ... :step-id ... :attempt-id ... :execution-session-id ... :status ...}
   after creating the attempt, prompting the child session, and recording the
   resulting canonical text envelope.

   Current slice treats the last assistant message text as canonical step output
   under `{:outcome :ok :outputs {:text ...}}`."
  [ctx parent-session-id run-id]
  (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
        step-id      (:current-step-id workflow-run)
        {:keys [prompt]} (step-prompt workflow-run step-id)
        {:keys [attempt execution-session]}
        (workflow-attempts/create-step-attempt-session!
         ctx
         parent-session-id
         {:workflow-run-id run-id
          :workflow-step-id step-id
          :session-name (str "workflow " step-id " attempt")
          :tool-defs []
          :thinking-level :off})]
    (swap! (:state* ctx)
           (fn [state]
             (-> state
                 (update-in [:workflows :runs run-id]
                            #(workflow-attempts/append-attempt-to-run % step-id attempt))
                 (workflow-progression/start-latest-attempt run-id step-id))))
    (try
      (session/prompt-in! ctx (:session-id execution-session) prompt)
      (let [assistant-message (session/last-assistant-message-in ctx (:session-id execution-session))
            envelope          {:outcome :ok
                               :outputs {:text (assistant-message-text assistant-message)}}]
        (swap! (:state* ctx) workflow-progression/submit-result-envelope run-id step-id envelope)
        {:run-id run-id
         :step-id step-id
         :attempt-id (:attempt-id attempt)
         :execution-session-id (:session-id execution-session)
         :status (get-in @(:state* ctx) [:workflows :runs run-id :status])})
      (catch Exception e
        (swap! (:state* ctx) workflow-progression/record-execution-failure run-id step-id {:message (ex-message e)})
        {:run-id run-id
         :step-id step-id
         :attempt-id (:attempt-id attempt)
         :execution-session-id (:session-id execution-session)
         :status (get-in @(:state* ctx) [:workflows :runs run-id :status])
         :error (ex-message e)}))))

(defn execute-run!
  "Execute a sequential workflow run until it reaches a terminal or blocked status.

   Returns {:run-id ... :status ... :steps-executed [...] :terminal? bool :blocked? bool}."
  [ctx parent-session-id run-id]
  (loop [steps-executed []]
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
          status (:status run)]
      (cond
        (contains? #{:completed :failed :cancelled} status)
        {:run-id run-id
         :status status
         :steps-executed steps-executed
         :terminal? true
         :blocked? false}

        (= :blocked status)
        {:run-id run-id
         :status status
         :steps-executed steps-executed
         :terminal? false
         :blocked? true}

        :else
        (let [step-result (execute-current-step! ctx parent-session-id run-id)]
          (recur (conj steps-executed (select-keys step-result [:step-id :attempt-id :execution-session-id :status :error]))))))))

(defn resume-and-execute-run!
  "Resume a blocked run and continue sequential execution.

   Resuming clears blocked state via pure progression; the next loop iteration
   creates a fresh attempt for the current step before executing it."
  [ctx parent-session-id run-id]
  (swap! (:state* ctx) workflow-progression/resume-blocked-run run-id)
  (execute-run! ctx parent-session-id run-id))
