(ns extensions.agent-chain
  "Agent Chain — Sequential pipeline orchestrator for Psi.

   Runs opinionated, repeatable agent workflows. Chains are defined in
   .psi/agents/agent-chain.edn — each chain is a sequence of agent steps
   with prompt templates. The user's original prompt flows into step 1,
   the output becomes $INPUT for step 2's prompt template, and so on.
   $ORIGINAL is always the user's original prompt.

   Sub-agents run in-process using isolated agent-core contexts. Each
   step gets its own system prompt, tools, and conversation history.
   Agents maintain session context within a Psi session — re-running
   the chain lets each agent resume where it left off.

   Chain runs are executed through the extension workflow runtime.

   Tool:
     agent-chain(action, ...)
       action=\"run\"    — run a named chain: requires chain, task args
       action=\"list\"   — list chains, agents, and recent runs
       action=\"reload\" — reload chain definitions and agent files

   Commands (human shortcuts, delegate to agent-chain tool):
     /chain        — list all available chains, agents, and chain runs
     /chain-reload — reload chain definitions and agent files

   Config:  .psi/agents/agent-chain.edn
   Agents:  .psi/agents/*.md (frontmatter + system prompt body)"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.statecharts.chart :as chart]
   [extensions.agent :as agent-ext]
   [extensions.workflow-display :as workflow-display]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.ai.models :as models]
   [psi.ui.widget-spec :as widget-spec]
   [taoensso.timbre :as timbre]))

;;; State — module-level atom, initialized in `init`

(def ^:private state
  "Extension state. Initialized by `init`."
  (atom nil))

(def ^:private chain-workflow-type :agent-chain-run)

(def ^:private workflow-eql
  [{:psi.extension/workflows
    [:psi.extension/path
     :psi.extension.workflow/id
     :psi.extension.workflow/type
     :psi.extension.workflow/phase
     :psi.extension.workflow/running?
     :psi.extension.workflow/done?
     :psi.extension.workflow/error?
     :psi.extension.workflow/error-message
     :psi.extension.workflow/input
     :psi.extension.workflow/meta
     :psi.extension.workflow/data
     :psi.extension.workflow/result
     :psi.extension.workflow/elapsed-ms
     :psi.extension.workflow/started-at]}])

(declare chain-workflows workflow-by-id mutate!)

(def ^:private widget-id "agent-chain")
(def ^:private max-widget-runs 4)
(def ^:private prompt-contribution-id "agent-chain-chains")

(defn- load-all-agent-defs
  "Discover agent definitions from standard directories.
  Delegates to agent-ext/load-agent-defs for each directory."
  [cwd]
  (let [dirs [(str cwd "/.psi/agents")
              (str cwd "/agents")]]
    (reduce (fn [agents dir]
              (merge agents (agent-ext/load-agent-defs dir)))
            {}
            dirs)))

;;; Chain config loader

(defn- load-chains
  "Load chain definitions from .psi/agents/agent-chain.edn.
   Expected format:
     [{:name        \"plan-build-review\"
       :description \"Plan, build, and review code changes\"
       :steps       [{:agent \"planner\" :prompt \"...\"}
                     {:agent \"builder\" :prompt \"$INPUT\"}]}]"
  [cwd]
  (let [path (str cwd "/.psi/agents/agent-chain.edn")]
    (if (.exists (io/file path))
      (try
        (let [data (edn/read-string (slurp path))]
          (if (vector? data) data []))
        (catch Exception e
          (println (str "  [agent-chain] Error loading " path ": " (ex-message e)))
          []))
      [])))

;;; General helpers

(defn- parse-int [x]
  (cond
    (number? x) (long x)
    (string? x) (try (Long/parseLong (str/trim x)) (catch Exception _ nil))
    :else nil))

(defn- display-name
  "Convert kebab-case to Title Case."
  [s]
  (->> (str/split (str s) #"-")
       (map str/capitalize)
       (str/join " ")))

(defn- status-icon [status]
  (case status
    :pending "○"
    :running "●"
    :done "✓"
    :error "✗"
    "?"))

(defn- task-preview [s n]
  (let [s (or s "")]
    (if (> (count s) n)
      (str (subs s 0 (max 0 (- n 3))) "...")
      s)))

(defn- chain-summary [chain success? elapsed-ms]
  (str "[chain:" (:name chain) "] "
       (if success? "done" "error")
       " in " (quot (or elapsed-ms 0) 1000) "s"))

(defn- emit-chain-result!
  [{:keys [run-id chain-name ok? elapsed-ms output]}]
  (let [seconds  (quot (or elapsed-ms 0) 1000)
        heading  (str "Chain run " run-id " (" chain-name ") "
                      (if ok? "finished" "failed")
                      " in " seconds "s")
        full     (str heading "\n\nResult:\n"
                      (if (> (count (or output "")) 8000)
                        (str (subs output 0 8000) "\n\n... [truncated]")
                        (or output "")))]
    (when-let [mf (:mutate-fn @state)]
      (try
        ;; Emit the final result directly as a normal assistant message so only
        ;; the final agent output reaches parent conversation/transcript.
        (mf 'psi.extension/send-message
            {:role    "assistant"
             :content full})
        (catch Exception _ nil)))))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- initial-progress
  [{:keys [run-id chain-name step-count]}]
  {:run-id     run-id
   :chain-name chain-name
   :phase      :running
   :step-count step-count
   :step-index nil
   :step-agent nil
   :last-work  ""
   :elapsed-ms 0
   :started-ms (now-ms)
   :updated-at (now-ms)})

(defn- progress-snapshot
  [progress*]
  (let [progress (when (instance? clojure.lang.IDeref progress*)
                   @progress*)]
    (when (map? progress)
      (assoc progress :updated-at (or (:updated-at progress) (now-ms))))))

(defn- workflow-display-model
  [wf]
  (let [progress   (or (get-in wf [:psi.extension.workflow/data :chain/progress]) {})
        run-id     (or (:psi.extension.workflow/id wf)
                       (:run-id progress))
        chain-name (or (:chain/name progress)
                       (:chain-name progress)
                       (get-in wf [:psi.extension.workflow/meta :chain-name])
                       (get-in wf [:psi.extension.workflow/input :chain :name]))
        phase      (or (:psi.extension.workflow/phase wf)
                       (:phase progress)
                       :pending)
        step-index (:step-index progress)
        step-count (:step-count progress)
        step-agent (:step-agent progress)
        last-work  (:last-work progress)
        elapsed-ms (or (:psi.extension.workflow/elapsed-ms wf)
                       (:elapsed-ms progress)
                       0)
        top-line   (str (status-icon phase) " " run-id
                        " [" (-> phase name str/upper-case) "] "
                        (or chain-name "unknown")
                        (when (and (number? step-index) (number? step-count) (pos? step-count))
                          (str " · step " (inc step-index) "/" step-count
                               (when (seq step-agent)
                                 (str " " step-agent))))
                        (when (pos? (long (or elapsed-ms 0)))
                          (str " · " (quot (long elapsed-ms) 1000) "s")))
        detail-line (when (seq last-work)
                      (str "  " (task-preview last-work 70)))]
    {:top-line top-line
     :detail-line detail-line}))

(defn- workflow-public-display
  [data]
  (let [progress    (or (:chain/progress data) {})
        run-id      (or (:chain/run-id data)
                        (:run-id progress))
        chain-name  (or (get-in data [:chain/config :name])
                        (:chain/name progress)
                        (:chain-name progress))
        phase       (or (:phase progress) :pending)
        step-index  (:step-index progress)
        step-count  (:step-count progress)
        step-agent  (:step-agent progress)
        elapsed-ms  (:elapsed-ms progress)
        last-work   (:last-work progress)
        top-line    (str (status-icon phase) " " run-id
                         " [" (-> phase name str/upper-case) "] "
                         (or chain-name "unknown")
                         (when (and (number? step-index) (number? step-count) (pos? step-count))
                           (str " · step " (inc step-index) "/" step-count
                                (when (seq step-agent)
                                  (str " " step-agent))))
                         (when (pos? (long (or elapsed-ms 0)))
                           (str " · " (quot (long elapsed-ms) 1000) "s")))
        detail-line (when (seq last-work)
                      (str "  " (task-preview last-work 70)))]
    {:top-line top-line
     :detail-line detail-line}))

(defn- workflow-lines
  [wf]
  (let [run-id (str/trim (str (or (:psi.extension.workflow/id wf) "")))]
    (-> (workflow-display/merged-display
         (workflow-display-model wf)
         (:chain/display (or (:psi.extension.workflow/data wf) {})))
        (workflow-display/display-lines
         :decorate-top-line
         (fn [top-line]
           (if (seq run-id)
             {:text   (str top-line " · ✕ remove")
              :action {:type :command
                       :command (str "/chain-rm " run-id)}}
             top-line))))))

(defn- widget-lines []
  (let [runs        (->> (chain-workflows)
                         (sort-by (fn [wf]
                                    (or (get-in wf [:psi.extension.workflow/data :chain/progress :updated-at])
                                        (some-> (:psi.extension.workflow/updated-at wf) .toEpochMilli)
                                        0))
                                  >)
                         (take max-widget-runs))
        chain-names (->> @(:chains @state)
                         (map :name)
                         (filter seq))
        header      (str "⛓ Agent Chain"
                         (when (seq chain-names)
                           (str " · " (str/join " · " chain-names))))]
    (vec (if (seq runs)
           (cons header (mapcat workflow-lines runs))
           [header]))))

(defn- prompt-contribution-content []
  (let [cs @(:chains @state)]
    (str "tool: agent-chain\n"
         "available chains:\n"
         (if (seq cs)
           (->> cs
                (map (fn [c]
                       (str "- " (:name c)
                            (when (seq (:description c))
                              (str ": " (:description c))))))
                (str/join "\n"))
           "- none"))))

(defn- sync-prompt-contribution! []
  (when-let [update! (some-> @state :api :update-prompt-contribution)]
    (update! prompt-contribution-id
             {:content (prompt-contribution-content)})))

(defn- register-prompt-contribution! [api]
  (when-let [register! (:register-prompt-contribution api)]
    (register! prompt-contribution-id
               {:section  "Extension Capabilities"
                :priority 200
                :enabled  true
                :content  (prompt-contribution-content)})))

(defn- widget-placement []
  (if (= :emacs (or (:ui-type @state) :console))
    :below-editor
    :above-editor))

(defn- workflow-run-node
  "Build a vstack node for a single workflow run."
  [wf]
  (let [data       (or (:psi.extension.workflow/data wf) {})
        progress   (or (:chain/progress data) {})
        run-id     (str (or (:psi.extension.workflow/id wf) ""))
        chain-name (or (get-in data [:chain/config :name])
                       (:chain/name progress)
                       (:chain-name progress)
                       "unknown")
        phase      (or (:phase progress) :pending)
        step-idx   (:step-index progress)
        step-cnt   (:step-count progress)
        step-agent (:step-agent progress)
        elapsed-ms (:elapsed-ms progress)
        last-work  (:last-work progress)
        top-text   (str (status-icon phase) " " run-id
                        " [" (-> phase name str/upper-case) "] "
                        chain-name
                        (when (and (number? step-idx) (number? step-cnt) (pos? step-cnt))
                          (str " · step " (inc step-idx) "/" step-cnt
                               (when (seq step-agent) (str " " step-agent))))
                        (when (pos? (long (or elapsed-ms 0)))
                          (str " · " (quot (long elapsed-ms) 1000) "s")))
        children   (cond-> [(widget-spec/text top-text)]
                     (seq last-work)
                     (conj (widget-spec/muted (str "  " (task-preview last-work 70)))))]
    (widget-spec/vstack children)))

(defn- build-widget-spec []
  (let [runs        (->> (chain-workflows)
                         (sort-by (fn [wf]
                                    (or (get-in wf [:psi.extension.workflow/data :chain/progress :updated-at])
                                        (some-> (:psi.extension.workflow/updated-at wf) .toEpochMilli)
                                        0))
                                  >)
                         (take max-widget-runs))
        chain-names (->> @(:chains @state)
                         (map :name)
                         (filter seq))
        header-text (str "⛓ Agent Chain"
                         (when (seq chain-names)
                           (str " · " (str/join " · " chain-names))))
        children    (cons (widget-spec/heading header-text)
                          (map workflow-run-node runs))]
    (widget-spec/widget-spec
     widget-id
     (widget-placement)
     (widget-spec/vstack (vec children)))))

(defn- refresh-widget! []
  (when-let [ui (:ui @state)]
    (when-let [set-widget (:set-widget ui)]
      (set-widget widget-id (widget-placement) (widget-lines)))
    (when-let [set-spec (:set-widget-spec ui)]
      (set-spec (build-widget-spec)))))



(defn- update-workflow-progress!
  [run-id f]
  (when-let [wf (workflow-by-id run-id)]
    (let [current  (or (get-in wf [:psi.extension.workflow/data :chain/progress])
                       {:run-id run-id})
          updated  (-> (f current)
                       (assoc :run-id run-id
                              :updated-at (now-ms)))]
      (mutate! 'psi.extension.workflow/send-event
               {:id    (str run-id)
                :event :chain/progress
                :data  {:progress updated}})
      (refresh-widget!))))

;;; Extension query/mutate helpers

(defn- query!
  [q]
  (when-let [qf (:query-fn @state)]
    (try
      (qf q)
      (catch Exception _ nil))))

(defn- mutate!
  [op params]
  (when-let [mf (:mutate-fn @state)]
    (try
      (mf op params)
      (catch Exception _ nil))))

;;; Workflow helpers

(defn- all-workflows []
  (or (:psi.extension/workflows (query! workflow-eql))
      []))

(defn- chain-workflows []
  (let [ext-path (:ext-path @state)]
    (->> (all-workflows)
         (filter (fn [wf]
                   (and (= ext-path (:psi.extension/path wf))
                        (= chain-workflow-type (:psi.extension.workflow/type wf)))))
         (sort-by (fn [wf]
                    (or (some->> (:psi.extension.workflow/id wf)
                                 (re-find #"(\\d+)$")
                                 second
                                 parse-int)
                        Long/MAX_VALUE))))))

(defn- workflow-by-id [id]
  (let [sid (str id)]
    (some #(when (= sid (:psi.extension.workflow/id %)) %) (chain-workflows))))

(defn- active-running-workflow []
  (first (filter :psi.extension.workflow/running? (chain-workflows))))

(defn- next-run-id! []
  (if-let [id-atom (:next-run-id @state)]
    (let [n @id-atom]
      (swap! id-atom inc)
      (str "run-" n))
    (str "run-" (java.util.UUID/randomUUID))))

(defn- clear-chain-workflows! []
  (let [runs (chain-workflows)]
    (doseq [wf runs]
      (mutate! 'psi.extension.workflow/remove
               {:id (:psi.extension.workflow/id wf)}))
    (count runs)))

(defn- remove-chain-run!
  [run-id]
  (let [sid (str/trim (str (or run-id "")))
        wf  (workflow-by-id sid)]
    (cond
      (str/blank? sid)
      {:ok false
       :error "run id is required"}

      (nil? wf)
      (do
        (refresh-widget!)
        {:ok false
         :error (str "No chain run \"" sid "\" found.")})

      :else
      (let [r (mutate! 'psi.extension.workflow/remove {:id sid})]
        (if (:psi.extension.workflow/removed? r)
          (do
            (refresh-widget!)
            {:ok true})
          {:ok false
           :error (or (:psi.extension.workflow/error r)
                      (str "Failed to remove chain run \"" sid "\"."))})))))

;;; Sub-agent execution — delegates to extensions.agent/run-agent

(defn- run-sub-agent!
  "Run a sub-agent step via agent-ext/run-agent.
  Manages session persistence through agent-sessions atom.
  Returns {:output string :success boolean :elapsed long}."
  [agent-name task agent-sessions ai-model get-api-key-fn agents-dir base-system-prompt]
  (let [agent-key  (str/lower-case (str/replace (or agent-name "") #"\s+" "-"))
        existing   (get @agent-sessions agent-key)
        config     (agent-ext/resolve-agent-config agent-name agents-dir base-system-prompt)
        result     (agent-ext/run-agent
                    {:config              config
                     :prompt              task
                     :model               ai-model
                     :get-api-key-fn      get-api-key-fn
                     :existing-session-id (:session-id existing)})]
    (swap! agent-sessions assoc agent-key
           {:session-id (:session-id result)})
    {:output  (if (:ok? result) (:text result) (or (:error-message result) (:text result)))
     :success (:ok? result)
     :elapsed (:elapsed-ms result)}))

;;; Chain execution

(defn- run-chain!
  "Execute a chain sequentially. Each step's output feeds into the next.
  `on-step-update` is called with (step-index status elapsed last-work).
  Returns {:output string :success boolean :elapsed long :step-results [...]}."
  [chain all-agents agent-sessions ai-model original-prompt on-step-update
   get-api-key-fn agents-dir base-system-prompt]
  (let [chain-start  (System/currentTimeMillis)
        steps        (:steps chain)
        step-results (atom [])
        notify-step  (fn [idx status elapsed last-work]
                       (when (fn? on-step-update)
                         (on-step-update idx status elapsed last-work)))]
    (loop [i     0
           input original-prompt]
      (if (>= i (count steps))
        {:output       (or input "")
         :success      true
         :elapsed      (- (System/currentTimeMillis) chain-start)
         :step-results @step-results}
        (let [step    (nth steps i)
              agent-k (str/lower-case (:agent step))]
          (notify-step i :running 0 "")
          (if (get all-agents agent-k)
            (let [prompt (-> (:prompt step)
                             (str/replace "$INPUT" (or input ""))
                             (str/replace "$ORIGINAL" (or original-prompt "")))
                  result (run-sub-agent! (:agent step) prompt agent-sessions
                                         ai-model get-api-key-fn
                                         agents-dir base-system-prompt)]
              (swap! step-results conj
                     {:agent   (:agent step)
                      :success (:success result)
                      :elapsed (:elapsed result)})
              (notify-step i
                           (if (:success result) :done :error)
                           (:elapsed result)
                           (let [lines (str/split-lines (:output result))]
                             (or (last (filter (complement str/blank?) lines)) "")))
              (if (:success result)
                (recur (inc i) (:output result))
                {:output       (str "Error at step " (inc i) " (" (:agent step) "): "
                                    (:output result))
                 :success      false
                 :elapsed      (- (System/currentTimeMillis) chain-start)
                 :step-results @step-results}))
            (do
              (notify-step i :error 0 (str "Agent \"" (:agent step) "\" not found"))
              {:output       (str "Error at step " (inc i) ": Agent \"" (:agent step)
                                  "\" not found. Available: "
                                  (str/join ", " (keys all-agents)))
               :success      false
               :elapsed      (- (System/currentTimeMillis) chain-start)
               :step-results @step-results})))))))

;;; Workflow execution chart

(defn- start-run-script
  [_ data]
  (let [input (:workflow/input data)
        chain (:chain/config data)]
    [{:op :assign
      :data {:workflow/error-message nil
             :workflow/result nil
             :chain/summary nil
             :chain/success? nil
             :chain/output nil
             :chain/elapsed-ms 0
             :chain/step-results []
             :chain/progress (initial-progress
                              {:run-id     (or (:chain/run-id data)
                                               (:run-id input))
                               :chain-name (or (:name chain)
                                               (get-in input [:chain :name]))
                               :step-count (count (:steps chain))})}}]))

(defn- run-invoke-params
  [_ data]
  {:run-id              (:chain/run-id data)
   :chain               (:chain/config data)
   :agents              (:chain/all-agents data)
   :agent-sessions      (:chain/agent-sessions data)
   :ai-model            (:chain/model data)
   :task                (:chain/task data)
   :agents-dir          (:chain/agents-dir data)
   :base-system-prompt  (:chain/base-system-prompt data)})

(defn- normalize-provider
  [provider]
  (cond
    (keyword? provider) provider
    (string? provider) (some-> provider str/trim not-empty str/lower-case keyword)
    :else nil))

(defn- resolve-runtime-model
  "Resolve to a canonical runtime model map from models/all-models.

   Accepts model maps where :provider may be keyword/string and id may be at
   :id or :model-id. Falls back to id-only resolution when provider is absent
   or non-canonical."
  [model]
  (let [provider (normalize-provider (:provider model))
        model-id (some-> (or (:id model) (:model-id model)) str/trim not-empty)
        by-provider-id
        (when (and provider model-id)
          (some (fn [m]
                  (when (and (= provider (:provider m))
                             (= model-id (:id m)))
                    m))
                (vals models/all-models)))]
    (or by-provider-id
        (when model-id
          (some (fn [m]
                  (when (= model-id (:id m))
                    m))
                (vals models/all-models))))))

(defn- run-chain-workflow-job
  [{:keys [run-id chain agents agent-sessions ai-model task
           agents-dir base-system-prompt]}]
  (let [run-id*         (str (or run-id (str "run-" (java.util.UUID/randomUUID))))
        started-ms      (now-ms)
        steps           (:steps chain)
        step-count      (count steps)
        get-api-key-fn  (:get-api-key-fn @state)
        ai-model*       (or (resolve-runtime-model ai-model)
                            (when-let [qf (:query-fn @state)]
                              (or (resolve-runtime-model
                                   (:psi.agent-session/model (qf [:psi.agent-session/model])))
                                  (let [mq (qf [:psi.agent-session/model-provider
                                                :psi.agent-session/model-id])]
                                    (resolve-runtime-model
                                     {:provider (:psi.agent-session/model-provider mq)
                                      :id (:psi.agent-session/model-id mq)}))))
                            (do
                              (timbre/warn "agent-chain: unable to resolve session model; defaulting to :sonnet-4.6")
                              (get models/all-models :sonnet-4.6)))
        on-step         (fn [idx status elapsed last-work]
                          (let [step-agent (:agent (nth steps idx nil))
                                elapsed*   (- (now-ms) started-ms)]
                            (update-workflow-progress!
                             run-id*
                             (fn [progress]
                               (cond-> (merge progress
                                              {:run-id     run-id*
                                               :chain-name (:name chain)
                                               :phase      (if (= status :error) :error :running)
                                               :step-count step-count
                                               :step-index idx
                                               :step-agent step-agent
                                               :last-work  (or last-work "")
                                               :elapsed-ms elapsed*})
                                 (pos? (long (or elapsed 0)))
                                 (assoc :step-elapsed-ms (long elapsed)))))))]
    (try
      (let [result  (run-chain! chain agents agent-sessions ai-model* task on-step
                                get-api-key-fn agents-dir base-system-prompt)
            success (:success result)
            summary (chain-summary chain success (:elapsed result))
            progress (-> (progress-snapshot (:chain/progress result))
                         (merge {:run-id     run-id*
                                 :chain-name (:name chain)
                                 :phase      (if success :done :error)
                                 :elapsed-ms (long (or (:elapsed result) 0))})
                         (cond-> (pos? step-count)
                           (assoc :step-index (dec step-count)
                                  :step-agent (:agent (nth steps (dec step-count) nil)))))]
        {:ok?           success
         :output        (:output result)
         :elapsed-ms    (:elapsed result)
         :step-results  (:step-results result)
         :summary       summary
         :progress      progress
         :error-message (when-not success (:output result))})
      (catch Exception e
        {:ok?           false
         :output        (str "Error: " (ex-message e))
         :elapsed-ms    (- (now-ms) started-ms)
         :step-results  []
         :summary       (chain-summary chain false (- (now-ms) started-ms))
         :progress      {:run-id     run-id*
                         :chain-name (:name chain)
                         :phase      :error
                         :last-work  (str "Error: " (ex-message e))
                         :elapsed-ms (- (now-ms) started-ms)
                         :updated-at (now-ms)}
         :error-message (ex-message e)}))))

(defn- invoke-ok?
  [_ data]
  (true? (get-in data [:_event :data :ok?])))

(defn- done-script
  [_ data]
  (let [ev          (get-in data [:_event :data])
        on-finished (:chain/on-finished data)]
    (when (fn? on-finished)
      (on-finished {:run-id     (:chain/run-id data)
                    :chain-name (get-in data [:chain/config :name])
                    :ok?        true
                    :elapsed-ms (long (or (:elapsed-ms ev) 0))
                    :output     (:output ev)}))
    [{:op :assign
      :data {:workflow/error-message nil
             :workflow/result (:output ev)
             :chain/summary (:summary ev)
             :chain/success? true
             :chain/output (:output ev)
             :chain/elapsed-ms (long (or (:elapsed-ms ev) 0))
             :chain/step-results (or (:step-results ev) [])
             :chain/progress (or (:progress ev)
                                 (:chain/progress data))}}]))

(defn- error-script
  [_ data]
  (let [ev          (get-in data [:_event :data])
        msg         (or (:error-message ev) (:output ev) "Unknown error")
        on-finished (:chain/on-finished data)]
    (when (fn? on-finished)
      (on-finished {:run-id     (:chain/run-id data)
                    :chain-name (get-in data [:chain/config :name])
                    :ok?        false
                    :elapsed-ms (long (or (:elapsed-ms ev) 0))
                    :output     (or (:output ev) msg)}))
    [{:op :assign
      :data {:workflow/error-message msg
             :workflow/result (:output ev)
             :chain/summary (:summary ev)
             :chain/success? false
             :chain/output (:output ev)
             :chain/elapsed-ms (long (or (:elapsed-ms ev) 0))
             :chain/step-results (or (:step-results ev) [])
             :chain/progress (or (:progress ev)
                                 (:chain/progress data))}}]))

(defn- progress-script
  [_ data]
  (let [progress (get-in data [:_event :data :progress])]
    [{:op :assign
      :data {:chain/progress (or progress (:chain/progress data))}}]))

(def ^:private chain-workflow-chart
  (chart/statechart {:id :agent-chain-workflow}
                    (ele/state {:id :idle}
                               (ele/transition {:event :chain/start :target :running}
                                               (ele/script {:expr start-run-script})))

                    (ele/state {:id :running}
                               (ele/invoke {:id     :runner
                                            :type   :future
                                            :params run-invoke-params
                                            :src    run-chain-workflow-job})
                               (ele/transition {:event  :chain/progress
                                                :type   :internal}
                                               (ele/script {:expr progress-script}))
                               (ele/transition {:event :done.invoke.runner
                                                :target :done
                                                :cond   invoke-ok?}
                                               (ele/script {:expr done-script}))
                               (ele/transition {:event :done.invoke.runner
                                                :target :error}
                                               (ele/script {:expr error-script})))

                    (ele/state {:id :done})
                    (ele/state {:id :error})))

(defn- register-chain-workflow-type! []
  (let [agent-sessions (:agent-sessions @state)
        on-finished    (fn [result]
                         (future
                           (Thread/sleep 30)
                           (emit-chain-result! result)))
        r (mutate! 'psi.extension.workflow/register-type
                   {:type            chain-workflow-type
                    :description     "Execute an agent chain run."
                    :chart           chain-workflow-chart
                    :start-event     :chain/start
                    :initial-data-fn (fn [input]
                                       (let [qf (:query-fn @state)]
                                         {:chain/run-id              (:run-id input)
                                          :chain/config              (:chain input)
                                          :chain/task                (:task input)
                                          :chain/model               (:model input)
                                          :chain/all-agents          (:agents input)
                                          :chain/agent-sessions      agent-sessions
                                          :chain/on-finished         on-finished
                                          :chain/agents-dir          [(str (System/getProperty "user.home") "/.psi/agents")
                                                                      (str (System/getProperty "user.dir") "/.psi/agents")]
                                          :chain/base-system-prompt  (when qf
                                                                       (:psi.agent-session/system-prompt
                                                                        (qf [:psi.agent-session/system-prompt])))}))
                    :public-data-fn  (fn [data]
                                       (let [base (select-keys data
                                                               [:chain/run-id
                                                                :chain/config
                                                                :chain/summary
                                                                :chain/success?
                                                                :chain/output
                                                                :chain/elapsed-ms
                                                                :chain/step-results
                                                                :chain/progress])]
                                         (assoc base
                                                :chain/display
                                                (workflow-public-display base))))})]
    (when-let [e (:psi.extension.workflow/error r)]
      (timbre/warn "agent-chain workflow type registration error:" e))))

;;; agent-chain tool actions

(defn- resolve-chain
  "Look up a chain by name (case-insensitive) from loaded chains.
   Returns the chain map or nil."
  [chain-name]
  (when (seq chain-name)
    (let [cs @(:chains @state)
          target (str/lower-case chain-name)]
      (some (fn [c]
              (when (= target (str/lower-case (or (:name c) "")))
                c))
            cs))))

(defn- action-run
  "Run a named chain. Requires :chain and :task in args-map."
  [args-map _opts]
  (let [{:keys [all-agents query-fn]} @state
        chain-name (str/trim (or (get args-map "chain") ""))
        agents     @all-agents
        model      (when query-fn
                     (:psi.agent-session/model
                      (query-fn [:psi.agent-session/model])))
        task       (str/trim (or (get args-map "task") ""))]
    (cond
      (str/blank? chain-name)
      {:content  "chain is required. Use action=\"list\" to see available chains."
       :is-error true}

      (str/blank? task)
      {:content  "task is required."
       :is-error true}

      (nil? model)
      {:content  "No AI model configured."
       :is-error true}

      :else
      (let [chain (resolve-chain chain-name)]
        (cond
          (nil? chain)
          {:content  (str "Chain \"" chain-name "\" not found. Use action=\"list\" to see available chains.")
           :is-error true}

          (active-running-workflow)
          (let [wf (active-running-workflow)]
            {:content  (str "A chain run is already in progress ("
                            (:psi.extension.workflow/id wf)
                            "). Wait for it to finish.")
             :is-error true})

          :else
          (let [run-id  (next-run-id!)
                _       (println (str "\n  ── Chain: " (:name chain) " (" run-id ") ──"))
                created (mutate! 'psi.extension.workflow/create
                                 {:type                  chain-workflow-type
                                  :id                    run-id
                                  :track-background-job? false
                                  :meta                  {:chain-name (:name chain)}
                                  :input                 {:run-id run-id
                                                          :task   task
                                                          :chain  chain
                                                          :agents agents
                                                          :model  model}})]
            (if-not (:psi.extension.workflow/created? created)
              {:content  (str "Failed to start chain run: "
                              (or (:psi.extension.workflow/error created)
                                  "unknown error"))
               :is-error true}
              (do
                (refresh-widget!)
                {:content  (str "Chain run started: " run-id
                                "\nMonitor with agent-chain action=\"list\" (or /chain).")
                 :is-error false}))))))))

(defn- action-list
  "List chains, agents, and recent runs. Returns content string."
  []
  (let [cs     @(:chains @state)
        agents @(:all-agents @state)
        runs   (chain-workflows)
        sb     (StringBuilder.)]
    (.append sb "\n  ── Chains ──\n")
    (if (empty? cs)
      (.append sb "  (none) Define chains in .psi/agents/agent-chain.edn\n")
      (doseq [[i c] (map-indexed vector cs)]
        (let [flow (->> (:steps c)
                        (map #(display-name (:agent %)))
                        (str/join " → "))]
          (.append sb (str "  " (inc i) ". " (:name c)
                           (when (seq (:description c))
                             (str " — " (:description c)))
                           "\n     " flow "\n")))))

    (.append sb "\n  ── Agents ──\n")
    (if (empty? agents)
      (.append sb "  (none) Define agents in .psi/agents/*.md\n")
      (doseq [[k v] (sort-by key agents)]
        (.append sb (str "  • " (display-name k)
                         (when (seq (:description v))
                           (str " — " (:description v)))
                         "\n    tools: " (:tools v) "\n"))))

    (.append sb "\n  ── Runs (workflow runtime) ──\n")
    (if (empty? runs)
      (.append sb "  (none)\n")
      (doseq [wf runs]
        (doseq [line (workflow-display/text-lines (workflow-lines wf))]
          (.append sb (str "  " line "\n")))))
    {:content  (str sb)
     :is-error false}))

(defn- action-reload
  "Reload chain definitions and agent files."
  []
  (let [cwd     (System/getProperty "user.dir")
        cleared (clear-chain-workflows!)]
    (reset! (:all-agents @state) (load-all-agent-defs cwd))
    (reset! (:chains @state) (load-chains cwd))
    (reset! (:agent-sessions @state) {})
    (reset! (:next-run-id @state) 1)
    (refresh-widget!)
    (sync-prompt-contribution!)
    {:content  (str "Reloaded: " (count @(:chains @state)) " chains, "
                    (count @(:all-agents @state)) " agents"
                    ", cleared " cleared " chain run"
                    (when (not= 1 cleared) "s"))
     :is-error false}))

(defn- execute-agent-chain
  "Execute the agent-chain tool. Dispatches on action."
  ([args-map]
   (execute-agent-chain args-map nil))
  ([args-map opts]
   (let [action (str/trim (or (get args-map "action") ""))]
     (case action
       "run"    (action-run args-map opts)
       "list"   (action-list)
       "reload" (action-reload)
       "remove" (let [result (remove-chain-run! (get args-map "id"))]
                  (if (:ok result)
                    {:content  (str "Removed chain run " (str/trim (str (get args-map "id"))) ".")
                     :is-error false}
                    {:content  (:error result)
                     :is-error true}))
       {:content  (str "Unknown action: \"" action "\". Valid actions: run, list, reload, remove.")
        :is-error true}))))

;;; Extension init

(defn init
  "Initialize the agent-chain extension."
  [api]
  (let [cwd              (System/getProperty "user.dir")
        ui-type          (or (:ui-type api) :console)
        all-agents-a     (atom (load-all-agent-defs cwd))
        chains-a         (atom (load-chains cwd))
        agent-sessions-a (atom {})
        next-run-id-a    (atom 1)
        query-fn         (:query api)
        mutate-fn        (:mutate api)
        get-api-key-fn   (:get-api-key api)
        ui               (:ui api)]

    (reset! state
            {:api            api
             :ui             ui
             :ext-path       (:path api)
             :all-agents     all-agents-a
             :chains         chains-a
             :agent-sessions agent-sessions-a
             :next-run-id    next-run-id-a
             :query-fn       query-fn
             :mutate-fn      mutate-fn
             :get-api-key-fn get-api-key-fn
             :ui-type        ui-type})

    (register-chain-workflow-type!)
    (register-prompt-contribution! api)
    (refresh-widget!)

    ((:register-tool api)
     {:name        "agent-chain"
      :label       "Agent Chain"
      :description (str "Run and manage agent chain pipelines. Do not poll unless explicitly asked to. "
                        "action=\"run\": start a named chain (requires chain, task). "
                        "action=\"list\": show available chains, agents, and recent runs. "
                        "action=\"reload\": reload chain definitions and agent files from disk. "
                        "action=\"remove\": remove a chain run by id.")
      :parameters  (pr-str {:type       "object"
                            :properties {"action" {:type        "string"
                                                   :enum        ["run" "list" "reload" "remove"]
                                                   :description "Action to perform: run, list, reload, or remove"}
                                         "chain"  {:type        "string"
                                                   :description "Chain name to run (required for action=\"run\")"}
                                         "task"   {:type        "string"
                                                   :description "Task/prompt for the chain (required for action=\"run\")"}
                                         "id"     {:type        "string"
                                                   :description "Run id to remove (required for action=\"remove\")"}}
                            :required   ["action"]})
      :execute     execute-agent-chain})

    ((:register-command api) "chain"
                             {:description "List chains, agents, and recent runs (alias for agent-chain action=list)"
                              :handler
                              (fn [_args]
                                (println (:content (action-list))))})

    ((:register-command api) "chain-reload"
                             {:description "Reload chain definitions and agent files (alias for agent-chain action=reload)"
                              :handler
                              (fn [_args]
                                (let [result (action-reload)]
                                  (println (str "  ✓ " (:content result)))))})

    ((:register-command api) "chain-rm"
                             {:description "Remove a chain run: /chain-rm <run-id>"
                              :handler
                              (fn [args]
                                (let [run-id (str/trim (or args ""))
                                      result (remove-chain-run! run-id)]
                                  (if (:ok result)
                                    (println (str "Removed chain run " run-id "."))
                                    (println (:error result)))))})

    ((:on api) "session_switch"
               (fn [_ev]
                 (clear-chain-workflows!)
                 (reset! agent-sessions-a {})
                 (reset! all-agents-a (load-all-agent-defs cwd))
                 (reset! chains-a (load-chains cwd))
                 (reset! next-run-id-a 1)
                 (refresh-widget!)
                 (sync-prompt-contribution!)))

    (refresh-widget!)
    (when-let [ui (:ui api)]
      ((:notify ui) (str "agent-chain loaded (ui=" (name ui-type) ")") :info))))
