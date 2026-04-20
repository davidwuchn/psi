(ns extensions.agent
  "Agent extension backed by extension workflows.

   Commands/tools:
   - /agent [--fork|-f] [@agent] <task>
   - /agent-cont <id> <prompt>
   - /agent-rm <id>
   - /agent-clear
   - /agent-list

   Workflow model:
   - one workflow per agent id
   - explicit states: :idle -> :running -> :done | :error
   - continue transitions: :done/:error -> :running

   No extension-managed runner futures or ticker loops."
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [com.fulcrologic.statecharts.chart :as chart]
   [extensions.workflow-display :as workflow-display]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.tools :as tools]
   [psi.ai.models :as models]
   [psi.ui.widget-spec :as widget-spec]))

(defonce state
  (atom {:api        nil
         :query-fn   nil
         :ui         nil
         :ext-path   nil
         :next-id    1
         :widget-ids #{}}))

(def ^:private agent-type :agent)
(def ^:private agent-tool-names #{"read" "bash" "edit" "write"})
(def ^:private prompt-contribution-id "agent-capabilities")

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
     :psi.extension.workflow/data
     :psi.extension.workflow/result
     :psi.extension.workflow/elapsed-ms
     :psi.extension.workflow/started-at]}])

(defn- now-ms [] (System/currentTimeMillis))

(def ^:private default-sync-timeout-ms 300000)

(defn- parse-int [x]
  (cond
    (number? x) (long x)
    (string? x) (try (Long/parseLong (str/trim x)) (catch Exception _ nil))
    :else nil))

(defn- parse-timeout-ms [x]
  (if (nil? x)
    default-sync-timeout-ms
    (let [n (parse-int x)]
      (if (and (number? n) (pos? n))
        (long n)
        ::invalid))))

(defn- parse-create-mode [x]
  (let [m (some-> x str str/trim str/lower-case not-empty)]
    (case m
      nil :async
      "async" :async
      "sync" :sync
      ::invalid)))

(defn- parse-bool [x]
  (cond
    (true? x) true
    (false? x) false
    (string? x) (case (str/lower-case (str/trim x))
                  "true" true
                  "false" false
                  ::invalid)
    (nil? x) false
    :else ::invalid))

(defn- parse-optional-bool [x]
  (cond
    (nil? x) nil
    (true? x) true
    (false? x) false
    (string? x) (case (str/lower-case (str/trim x))
                  "true" true
                  "false" false
                  ::invalid)
    :else ::invalid))

(defn- tool-call-id-from-opts [opts]
  (or (:tool-call-id opts)
      (get opts "tool-call-id")
      (str "agent-" (java.util.UUID/randomUUID))))

(defn- task-preview [s n]
  (let [s (or s "")]
    (if (> (count s) n)
      (str (subs s 0 (max 0 (- n 3))) "...")
      s)))

(defn- provider-name [p]
  (cond
    (keyword? p) (name p)
    (string? p) p
    :else nil))

(defn- instant->ms [x]
  (when (instance? java.time.Instant x)
    (.toEpochMilli ^java.time.Instant x)))

(defn- query!
  [q]
  (when-let [qf (:query-fn @state)]
    (try
      (qf q)
      (catch Exception _ nil))))

(defn- mutate!
  [op params]
  (when-let [mf (some-> @state :api :mutate)]
    (try
      (mf op params)
      (catch Exception _ nil))))

(defn- all-workflows []
  (or (:psi.extension/workflows (query! workflow-eql))
      []))

(defn- agent-workflows []
  (let [ext-path (:ext-path @state)]
    (->> (all-workflows)
         (filter (fn [wf]
                   (and (= ext-path (:psi.extension/path wf))
                        (= agent-type (:psi.extension.workflow/type wf)))))
         (sort-by (fn [wf]
                    (or (parse-int (:psi.extension.workflow/id wf))
                        Long/MAX_VALUE))))))

(defn- workflow-by-id [id]
  (let [sid (str id)]
    (some #(when (= sid (:psi.extension.workflow/id %)) %) (agent-workflows))))

(defn- resolve-active-model [query-fn]
  (let [m        (when query-fn
                   (:psi.agent-session/model (query-fn [:psi.agent-session/model])))
        provider (provider-name (:provider m))
        id       (:id m)]
    (or (some (fn [model]
                (when (and (= provider (provider-name (:provider model)))
                           (= id (:id model)))
                  model))
              (vals models/all-models))
        (get models/all-models :sonnet-4.6))))

(defn- current-system-prompt [query-fn]
  (when query-fn
    (:psi.agent-session/system-prompt
     (query-fn [:psi.agent-session/system-prompt]))))

(defn- parse-frontmatter
  "Parse markdown frontmatter and return {:name :description :lambda-description :tools :system-prompt} when present."
  [raw]
  (let [{:keys [frontmatter body]} (pt/extract-frontmatter (or raw ""))
        name                       (some-> (:name frontmatter) str/trim not-empty)]
    (when name
      {:name               name
       :description        (some-> (:description frontmatter) str/trim not-empty)
       :lambda-description (some-> (:lambda frontmatter) str/trim not-empty)
       :tools              (some-> (:tools frontmatter) str/trim not-empty)
       :system-prompt      (str/trim body)})))

(declare current-session-worktree-path)
(declare widget-placement)

(defn- normalize-agent-name [x]
  (some-> x str str/trim (str/replace #"^@" "") str/lower-case not-empty))

(defn- current-session-worktree-path [query-fn]
  (when query-fn
    (:psi.agent-session/worktree-path
     (query-fn [:psi.agent-session/worktree-path]))))

(defn global-agents-dirs
  "Return supported global agent definition directories in precedence order.
   Later merges win, so callers should load these in listed order.
   Preferred path is ~/.psi/agent/agents, with ~/.psi/agents kept as legacy fallback."
  []
  [(str (System/getProperty "user.home") "/.psi/agent/agents")
   (str (System/getProperty "user.home") "/.psi/agents")])

(defn- project-agents-dir [query-fn]
  (when-let [worktree-path (current-session-worktree-path query-fn)]
    (str worktree-path "/.psi/agents")))

(defn load-agent-defs
  "Load agent definitions from a directory of markdown files.
  Returns {lowercase-name {:name :description :lambda-description :tools :system-prompt}}."
  [agents-dir]
  (let [dir (some-> agents-dir io/file)]
    (if (and dir (.exists dir) (.isDirectory dir))
      (->> (.listFiles dir)
           (keep (fn [f]
                   (when (and (.isFile f)
                              (str/ends-with? (.getName f) ".md"))
                     (try
                       (let [raw    (slurp f)
                             parsed (parse-frontmatter raw)
                             k      (normalize-agent-name (:name parsed))]
                         (when (and k
                                    (seq (:system-prompt parsed)))
                           [k {:name               k
                               :description        (:description parsed)
                               :lambda-description (:lambda-description parsed)
                               :tools              (:tools parsed)
                               :system-prompt      (:system-prompt parsed)}]))
                       (catch Exception _ nil)))))
           (into {}))
      {})))

(defn- load-all-agent-defs
  "Load agent defs from supported global dirs and the project dir, merged.
  Precedence: legacy global < preferred global < project."
  [query-fn]
  (reduce (fn [acc dir]
            (merge acc (load-agent-defs dir)))
          {}
          (concat (global-agents-dirs)
                  [(project-agents-dir query-fn)])))

(defn- selected-agent-def [query-fn agent-name]
  (when-let [name (normalize-agent-name agent-name)]
    (get (load-all-agent-defs query-fn) name)))

(defn- tool-schemas-for
  "Resolve a comma-separated tools string to tool schema maps.
  Falls back to default agent tool set when tools-str is nil."
  [tools-str]
  (let [tool-map (into {} (map (juxt :name identity)) tools/all-tool-schemas)
        names    (if tools-str
                   (into [] (comp (map str/trim) (remove str/blank?))
                         (str/split tools-str #","))
                   (vec agent-tool-names))]
    (vec (keep tool-map names))))

;;; Public boundary — RunAgent primitive and config resolution

(defn- agent-profile-prompt
  [agent-def]
  (when agent-def
    (str "# Agent Profile: " (:name agent-def) "\n\n"
         (:system-prompt agent-def))))

(defn- skill-prelude-messages
  [skill-name skills prompt]
  (when-let [skill (some->> skill-name str/trim not-empty (skills/find-skill skills))]
    (when-let [raw (try (slurp (:file-path skill)) (catch Exception _ nil))]
      [{:role "user"
        :content [{:type :text :text (str "Use the skill '" (:name skill) "'.")}]}
       {:role "assistant"
        :content [{:type :text :text raw}]}
       {:role "user"
        :content [{:type :text :text (or prompt "")}]}
       {:role "assistant"
        :content [{:type :text :text "Understood. I will use that skill for the next request."}]}])))

(defn resolve-agent-config
  "Resolve agent configuration from name, agents directories, and base prompt.
  `agents-dirs` may be a single directory string or a seq of directories.
  Returns {:session-name :system-prompt :developer-prompt :developer-prompt-source :tools :model :thinking-level :fork-messages}."
  [agent-name agents-dirs base-system-prompt]
  (let [norm-name  (normalize-agent-name agent-name)
        dirs       (if (string? agents-dirs) [agents-dirs] (vec agents-dirs))
        all-defs   (reduce (fn [acc d] (merge acc (load-agent-defs d))) {} dirs)
        agent-def  (when norm-name (get all-defs norm-name))
        tools      (tool-schemas-for (:tools agent-def))
        profile    (agent-profile-prompt agent-def)]
    {:session-name            norm-name
     :system-prompt           base-system-prompt
     :developer-prompt        profile
     :developer-prompt-source (when profile :explicit)
     :tools                   tools
     :model                   nil
     :thinking-level          :off
     :fork-messages           nil}))

(defn- run-agent*
  [{:keys [api config prompt model get-api-key-fn existing-session-id]}]
  (let [started    (now-ms)
        mutate-fn  (or (:mutate api)
                       (some-> @state :api :mutate))
        session-id (or existing-session-id
                       (when mutate-fn
                         (:psi.agent-session/session-id
                          (mutate-fn 'psi.extension/create-child-session
                                     (cond-> {:session-name   (:session-name config)
                                              :system-prompt  (:system-prompt config)
                                              :tool-defs      (:tools config)
                                              :thinking-level (:thinking-level config)}
                                       (some? (:developer-prompt config))
                                       (assoc :developer-prompt (:developer-prompt config))

                                       (some? (:developer-prompt-source config))
                                       (assoc :developer-prompt-source (:developer-prompt-source config))

                                       (some? (:preloaded-messages config))
                                       (assoc :preloaded-messages (:preloaded-messages config))

                                       (some? (:cache-breakpoints config))
                                       (assoc :cache-breakpoints (:cache-breakpoints config)))))))]
    (if-not session-id
      {:ok?           false
       :text          "Error: could not create child session"
       :elapsed-ms    (- (now-ms) started)
       :error-message "No mutate-fn or session creation failed"
       :session-id    nil}
      (let [model*  (or model (get models/all-models :sonnet-4.6))
            api-key (when (fn? get-api-key-fn)
                      (get-api-key-fn (:provider model*)))
            result  (try
                      (mutate-fn 'psi.extension/run-agent-loop-in-session
                                 {:session-id session-id
                                  :prompt     prompt
                                  :model      model*
                                  :api-key    api-key})
                      (catch Exception e
                        {:psi.agent-session/agent-run-ok?           false
                         :psi.agent-session/agent-run-text          (str "Error: " (ex-message e))
                         :psi.agent-session/agent-run-elapsed-ms    (- (now-ms) started)
                         :psi.agent-session/agent-run-error-message (ex-message e)}))]
        {:ok?           (:psi.agent-session/agent-run-ok? result)
         :text          (:psi.agent-session/agent-run-text result)
         :elapsed-ms    (or (:psi.agent-session/agent-run-elapsed-ms result)
                            (- (now-ms) started))
         :error-message (:psi.agent-session/agent-run-error-message result)
         :session-id    session-id}))))

(defn run-agent
  "Run an agent to completion via core child session.
  Creates a child session via mutation, runs the agent loop, returns result.
  Returns {:ok? :text :elapsed-ms :error-message :session-id}."
  [{:keys [config prompt model get-api-key-fn existing-session-id] :as opts}]
  (run-agent* (assoc opts :api nil)))

(defn run-agent-with-api
  "Run an agent using an explicit extension API instead of the extension-global state."
  [{:keys [api] :as opts}]
  (run-agent* opts))

(defn- current-session-messages [query-fn]
  (when query-fn
    (let [result (query-fn [{:psi.agent-session/session-entries
                             [:psi.session-entry/kind
                              :psi.session-entry/data]}])]
      (->> (:psi.agent-session/session-entries result)
           (keep (fn [entry]
                   (when (= :message (:psi.session-entry/kind entry))
                     (let [msg (get-in entry [:psi.session-entry/data :message])]
                       (when (and (map? msg)
                                  (contains? #{"user" "assistant" "toolResult"}
                                             (:role msg)))
                         msg)))))
           vec))))

(defn- wf-phase [wf]
  (or (:psi.extension.workflow/phase wf)
      (cond
        (:psi.extension.workflow/running? wf) :running
        (:psi.extension.workflow/error? wf) :error
        (:psi.extension.workflow/done? wf) :done
        :else :unknown)))

(defn- phase-label [wf]
  (case (wf-phase wf)
    :running "RUN"
    :done "DONE"
    :error "ERR"
    :idle "IDLE"
    (-> wf wf-phase name str/upper-case)))

(defn- phase-badge [wf]
  (str "[" (phase-label wf) "]"))

(defn- status-icon [wf]
  (case (wf-phase wf)
    :running "●"
    :error "✗"
    :done "✓"
    :idle "○"
    "?"))

(defn- wf-data [wf]
  (or (:psi.extension.workflow/data wf) {}))

(defn- wf-turn-count [wf]
  (or (:agent/turn-count (wf-data wf)) 1))

(defn- wf-task [wf]
  (or (:agent/current-prompt (wf-data wf))
      (get-in wf [:psi.extension.workflow/input :task])
      ""))

(defn- wf-last-text [wf]
  (or (:agent/last-text (wf-data wf))
      (:psi.extension.workflow/result wf)
      (:psi.extension.workflow/error-message wf)
      ""))

(defn- wf-agent-name [wf]
  (or (:agent/agent-name (wf-data wf))
      (get-in wf [:psi.extension.workflow/input :agent])))

(defn- wf-forked? [wf]
  (or (= true (:agent/fork-session? (wf-data wf)))
      (= true (get-in wf [:psi.extension.workflow/input :fork-session]))))

(defn- elapsed-seconds [wf]
  (let [running?   (:psi.extension.workflow/running? wf)
        elapsed-ms (or (:psi.extension.workflow/elapsed-ms wf) 0)
        started-ms (instant->ms (:psi.extension.workflow/started-at wf))]
    (if (and running? started-ms)
      (quot (- (now-ms) started-ms) 1000)
      (quot elapsed-ms 1000))))

(defn- last-non-blank-line [s]
  (->> (str/split-lines (or s ""))
       (remove str/blank?)
       last))

(defn- clean-error-line [s]
  (some-> s
          str/trim
          (str/replace #"(?i)^error:\s*" "")
          not-empty))

(defn- error-line-from-data
  [data]
  (or (clean-error-line (:workflow/error-message data))
      (some-> (:agent/last-text data) last-non-blank-line clean-error-line)))

(defn- last-line-from-data
  [data]
  (some-> (:agent/last-text data) last-non-blank-line str/trim not-empty))

(defn- wf-error-line [wf]
  (or (:agent/error-line (wf-data wf))
      (error-line-from-data (wf-data wf))
      (clean-error-line (:psi.extension.workflow/error-message wf))
      (some-> (wf-last-text wf) last-non-blank-line clean-error-line)))

(defn- widget-detail-line [wf]
  (let [data (wf-data wf)]
    (cond
      (:psi.extension.workflow/error? wf)
      (when-let [err (wf-error-line wf)]
        (str "  ! " (task-preview err 100)))

      :else
      (when-let [last-line (or (:agent/last-line data)
                               (last-line-from-data data)
                               (some-> (wf-last-text wf) last-non-blank-line str/trim not-empty))]
        (str "  "
             (if (:psi.extension.workflow/done? wf) "↳ " "… ")
             (task-preview last-line 100))))))

(defn- widget-action-line [wf]
  (when (and (not (:psi.extension.workflow/running? wf))
             (or (:psi.extension.workflow/done? wf)
                 (:psi.extension.workflow/error? wf)))
    (let [id (:psi.extension.workflow/id wf)]
      {:text   (str "  /agent-cont " id " [your prompt] · ✕ remove")
       :action {:type :command
                :command (str "/agent-rm " id)}})))

(defn- workflow-display-model
  [wf]
  (let [agent-tag   (when-let [agent-name (some-> (wf-agent-name wf) str str/trim not-empty)]
                      (str " · @" agent-name))
        fork-tag    (when (wf-forked? wf) " · fork")
        top-line    (str (status-icon wf)
                         " Agent #" (:psi.extension.workflow/id wf)
                         " " (phase-badge wf)
                         " · T" (wf-turn-count wf)
                         " · " (elapsed-seconds wf) "s"
                         agent-tag
                         fork-tag
                         " · " (task-preview (wf-task wf) 52))
        detail-line (widget-detail-line wf)
        action-line (widget-action-line wf)]
    {:top-line top-line
     :detail-line detail-line
     :action-line action-line}))

(defn- workflow-public-display
  [data]
  (let [agent-tag   (when-let [agent-name (some-> (:agent/agent-name data) str str/trim not-empty)]
                      (str " · @" agent-name))
        fork-tag    (when (:agent/fork-session? data) " · fork")
        top-line    (str (status-icon {:psi.extension.workflow/phase (if (:workflow/error-message data)
                                                                       :error
                                                                       :done)})
                         " Agent #" (:workflow/id data)
                         " [" (if (:workflow/error-message data) "ERR" "DONE") "]"
                         " · T" (or (:agent/turn-count data) 1)
                         " · " (quot (long (or (:agent/elapsed-ms data) 0)) 1000) "s"
                         agent-tag
                         fork-tag
                         " · " (task-preview (or (:agent/current-prompt data) "") 52))
        detail-line (cond
                      (seq (:agent/error-line data))
                      (str "  ! " (task-preview (:agent/error-line data) 100))

                      (seq (:agent/last-line data))
                      (str "  ↳ " (task-preview (:agent/last-line data) 100))

                      :else nil)]
    {:top-line top-line
     :detail-line detail-line}))

(defn- widget-lines [wf]
  (let [display (workflow-display/merged-display
                 (workflow-display-model wf)
                 (:agent/display (or (:psi.extension.workflow/data wf) {})))]
    (workflow-display/display-lines display)))

(defn- available-agent-defs []
  (load-all-agent-defs (:query-fn @state)))

(defn- lambda-mode? []
  (when-let [query (:query-fn @state)]
    (let [result (query [:psi.agent-session/prompt-mode])]
      (= :lambda (:psi.agent-session/prompt-mode result)))))

(defn- prompt-agent-line [lambda? [name {:keys [description lambda-description]}]]
  (let [display-description (if lambda?
                              (or lambda-description description)
                              description)]
    (if (seq display-description)
      (str "- " name ": " display-description)
      (str "- " name))))

(defn- prompt-contribution-content []
  (let [agents   (available-agent-defs)
        lambda? (lambda-mode?)]
    (str "tool: agent\n"
         "available agents:\n"
         (if (seq agents)
           (->> agents
                (sort-by key)
                (map (partial prompt-agent-line lambda?))
                (str/join "\n"))
           "- none"))))

(defn- sync-prompt-contribution! []
  (when-let [update! (some-> @state :api :update-prompt-contribution)]
    (update! prompt-contribution-id
             {:content (prompt-contribution-content)})))

(defn- agent-widget-query []
  ;; Join query — resolves the single workflow identified by the entity context
  [{:psi.extension.workflow/detail
    [:psi.extension.workflow/id
     :psi.extension.workflow/phase
     :psi.extension.workflow/running?
     :psi.extension.workflow/done?
     :psi.extension.workflow/error?
     :psi.extension.workflow/error-message
     :psi.extension.workflow/elapsed-ms
     :psi.extension.workflow/data]}])

(defn- agent-widget-spec-node [_wf-id]
  ;; Static structure — content resolved client-side from query data
  ;; Data shape: {:psi.extension.workflow/detail {... :psi.extension.workflow/data {...}}}
  (let [detail-path [:psi.extension.workflow/detail]]
    (widget-spec/vstack
     [(widget-spec/text "" :content-path (conj detail-path :psi.extension.workflow/id))
      ;; detail line: last-line or error-line from :data
      (widget-spec/muted "" :content-path (into detail-path
                                                [:psi.extension.workflow/data
                                                 :agent/last-line]))])))

(defn- build-agent-widget-spec [wf]
  (let [wf-id    (:psi.extension.workflow/id wf)
        wid      (str "agent-" wf-id)
        ext-path (:ext-path @state)]
    (widget-spec/widget-spec
     wid
     (widget-placement)
     (agent-widget-spec-node wf-id)
     :query         (agent-widget-query)
     :entity        {:psi.extension/path              ext-path
                     :psi.extension.workflow/id        wf-id}
     :subscriptions [(widget-spec/event-subscription "tool/result")
                     (widget-spec/event-subscription "tool/update")])))

(defn- clear-widget! [id]
  (when-let [ui (:ui @state)]
    ((:clear-widget ui) (str "agent-" id))
    (when-let [clear-spec (:clear-widget-spec ui)]
      (clear-spec (str "agent-" id)))))

(defn- refresh-widgets! []
  (when-let [ui (:ui @state)]
    (let [wfs         (agent-workflows)
          current-ids (into #{} (map #(str "agent-" (:psi.extension.workflow/id %)) wfs))
          old-ids     (:widget-ids @state)
          removed     (set/difference old-ids current-ids)]
      (doseq [wid removed]
        ((:clear-widget ui) wid)
        (when-let [clear-spec (:clear-widget-spec ui)]
          (clear-spec wid)))
      (doseq [wf wfs]
        ((:set-widget ui)
         (str "agent-" (:psi.extension.workflow/id wf))
         (widget-placement)
         (widget-lines wf))
        (when-let [set-spec (:set-widget-spec ui)]
          (set-spec (build-agent-widget-spec wf))))
      (swap! state assoc :widget-ids current-ids)
      (sync-prompt-contribution!))))

(defn- refresh-widgets-later! []
  ;; Workflow completion callbacks run during statechart event processing,
  ;; before the workflow runtime commits the new snapshot. Defer a refresh
  ;; so widgets render the committed phase (e.g. DONE/ERR instead of stale RUN).
  (future
    (Thread/sleep 30)
    (refresh-widgets!)))

(defn- ui-type []
  (or (:ui-type (:api @state)) :console))

(defn- widget-placement []
  (if (= :emacs (ui-type))
    :below-editor
    :above-editor))

(defn- log! [text]
  (if-let [f (:log (:api @state))]
    (f text)
    (binding [*out* *err*]
      (println text))))

(defn- notify! [text level]
  (if-let [ui (:ui @state)]
    ((:notify ui) text level)
    (log! text)))

(defn- context-last-role [query-fn]
  (->> (current-session-messages query-fn)
       (remove :custom-type)
       (map :role)
       (filter #{"user" "assistant"})
       last))

(defn- inject-result-into-context!
  [{:keys [id turn-count result-text]}]
  (when-let [mutate-fn (some-> @state :api :mutate)]
    (let [query-fn     (:query-fn @state)
          last-role    (context-last-role query-fn)
          turn*        (max 1 (long (or turn-count 1)))
          job-ref      (str "agent-" id "-turn-" turn*)
          user-content (str "Agent job id: " job-ref)
          asst-content (or result-text "")]
      ;; Keep strict user/assistant alternation for provider-facing context.
      ;; If the last role is user, emit an assistant bridge first.
      (when (= "user" last-role)
        (try
          (mutate-fn 'psi.extension/append-message
                     {:role "assistant"
                      :content "(agent context bridge)"})
          (catch Exception _ nil)))
      (try
        (mutate-fn 'psi.extension/append-message
                   {:role "user"
                    :content user-content})
        (catch Exception _ nil))
      (try
        (mutate-fn 'psi.extension/append-message
                   {:role "assistant"
                    :content asst-content})
        (catch Exception _ nil)))))

(defn- emit-result-message!
  [{:keys [id prompt turn-count ok? elapsed-ms result-text include-result-in-context?]}]
  (let [seconds  (quot (or elapsed-ms 0) 1000)
        heading  (str "Agent #" id
                      (when (> (or turn-count 1) 1)
                        (str " (Turn " turn-count ")"))
                      " finished \"" prompt "\" in " seconds "s")
        full     (str heading "\n\nResult:\n"
                      (if (> (count (or result-text "")) 8000)
                        (str (subs result-text 0 8000) "\n\n... [truncated]")
                        (or result-text "")))]
    (when-let [mutate-fn (some-> @state :api :mutate)]
      (if include-result-in-context?
        (inject-result-into-context! {:id id
                                      :turn-count turn-count
                                      :result-text result-text})
        (try
          (mutate-fn 'psi.extension/append-entry
                     {:custom-type "agent-result"
                      :data        full})
          (catch Exception _ nil))))
    (log! (str "[agent-result] " heading))
    (when (seq result-text)
      (log! (task-preview result-text 800)))
    (notify! (str "Agent #" id " "
                  (if ok? "done" "error")
                  " in " seconds "s")
             (if ok? :info :error))))

(defn- run-agent-job
  "Workflow invocation wrapper. Delegates to `run-agent` via mutations."
  [{:keys [session-id prompt query-fn get-api-key-fn]}]
  (let [model (resolve-active-model query-fn)]
    (when-not model
      (throw (ex-info "No active model available" {})))
    (run-agent {:config              {}
                :prompt              prompt
                :model               model
                :get-api-key-fn      get-api-key-fn
                :existing-session-id session-id})))

(defn- start-script
  [_ data]
  (let [prompt   (str/trim (or (get-in data [:_event :data :prompt])
                               (get-in data [:workflow/input :task])
                               ""))
        turn     (max 1 (long (or (:agent/turn-count data) 1)))
        on-start (:agent/on-start data)]
    (when (fn? on-start)
      (on-start {:id (:workflow/id data)
                 :prompt prompt
                 :turn-count turn}))
    [{:op :assign
      :data {:agent/current-prompt prompt
             :agent/turn-count turn
             :agent/last-text ""
             :agent/elapsed-ms 0
             :workflow/error-message nil
             :workflow/result nil}}]))

(defn- continue-script
  [_ data]
  (let [prompt    (str/trim (or (get-in data [:_event :data :prompt])
                                (:agent/current-prompt data)
                                ""))
        include?  (get-in data [:_event :data :include-result-in-context])
        include?* (if (nil? include?)
                    (:agent/include-result-in-context? data)
                    (true? include?))
        turn      (inc (long (or (:agent/turn-count data) 1)))
        on-start  (:agent/on-start data)]
    (when (fn? on-start)
      (on-start {:id (:workflow/id data)
                 :prompt prompt
                 :turn-count turn}))
    [{:op :assign
      :data {:agent/current-prompt prompt
             :agent/include-result-in-context? include?*
             :agent/turn-count turn
             :agent/last-text ""
             :agent/elapsed-ms 0
             :workflow/error-message nil
             :workflow/result nil}}]))

(defn- invoke-ok?
  [_ data]
  (true? (get-in data [:_event :data :ok?])))

(defn- done-script
  [_ data]
  (let [ev          (get-in data [:_event :data])
        text        (or (:text ev) "")
        elapsed-ms  (long (or (:elapsed-ms ev) 0))
        prompt      (or (:agent/current-prompt data) "")
        turn-count  (long (or (:agent/turn-count data) 1))
        include?    (true? (:agent/include-result-in-context? data))
        on-finished (:agent/on-finished data)
        last-line   (some-> text last-non-blank-line str/trim not-empty)]
    (when (fn? on-finished)
      (on-finished {:id                          (:workflow/id data)
                    :prompt                      prompt
                    :turn-count                  turn-count
                    :ok?                         true
                    :elapsed-ms                  elapsed-ms
                    :result-text                 text
                    :include-result-in-context?  include?}))
    [{:op :assign
      :data {:agent/last-text text
             :agent/last-line last-line
             :agent/error-line nil
             :agent/elapsed-ms elapsed-ms
             :workflow/error-message nil
             :workflow/result text}}]))

(defn- error-script
  [_ data]
  (let [ev          (get-in data [:_event :data])
        msg         (or (:error-message ev) "Unknown error")
        text        (or (:text ev) (str "Error: " msg))
        elapsed-ms  (long (or (:elapsed-ms ev) 0))
        prompt      (or (:agent/current-prompt data) "")
        turn-count  (long (or (:agent/turn-count data) 1))
        include?    (true? (:agent/include-result-in-context? data))
        on-finished (:agent/on-finished data)
        error-line  (or (clean-error-line msg)
                        (some-> text last-non-blank-line clean-error-line))]
    (when (fn? on-finished)
      (on-finished {:id                          (:workflow/id data)
                    :prompt                      prompt
                    :turn-count                  turn-count
                    :ok?                         false
                    :elapsed-ms                  elapsed-ms
                    :result-text                 text
                    :include-result-in-context?  include?}))
    [{:op :assign
      :data {:agent/last-text text
             :agent/last-line nil
             :agent/error-line error-line
             :agent/elapsed-ms elapsed-ms
             :workflow/error-message msg
             :workflow/result text}}]))

(def ^:private agent-chart
  (chart/statechart {:id :agent-workflow}
                    (ele/state {:id :idle}
                               (ele/transition {:event :agent/start :target :running}
                                               (ele/script {:expr start-script})))

                    (ele/state {:id :running}
                               (ele/invoke {:id     :runner
                                            :type   :future
                                            :params (fn [_ data]
                                                      {:session-id      (:agent/session-id data)
                                                       :prompt          (:agent/current-prompt data)
                                                       :query-fn        (:agent/query-fn data)
                                                       :get-api-key-fn  (:agent/get-api-key-fn data)})
                                            :src    run-agent-job})
                               (ele/transition {:event :done.invoke.runner
                                                :target :done
                                                :cond   invoke-ok?}
                                               (ele/script {:expr done-script}))
                               (ele/transition {:event :done.invoke.runner
                                                :target :error}
                                               (ele/script {:expr error-script})))

                    (ele/state {:id :done}
                               (ele/transition {:event :agent/continue :target :running}
                                               (ele/script {:expr continue-script})))

                    (ele/state {:id :error}
                               (ele/transition {:event :agent/continue :target :running}
                                               (ele/script {:expr continue-script})))))

(defn- make-on-finished-callback []
  (fn [{:keys [id prompt turn-count ok? elapsed-ms result-text include-result-in-context?]}]
    (refresh-widgets-later!)
    (emit-result-message! {:id                         (or (parse-int id) id)
                           :prompt                     prompt
                           :turn-count                 turn-count
                           :ok?                        ok?
                           :elapsed-ms                 elapsed-ms
                           :result-text                result-text
                           :include-result-in-context? include-result-in-context?})))

(defn- initial-agent-workflow-data [qf get-api-key on-start on-finished input]
  (let [agent-name    (normalize-agent-name (get input :agent))
        fork-session? (true? (get input :fork-session))
        include?      (true? (get input :include-result-in-context))
        config        (or (:config-override input)
                          (resolve-agent-config
                           agent-name
                           (concat (global-agents-dirs)
                                   [(project-agents-dir qf)])
                           (current-system-prompt qf)))
        session-id    (when-let [mf (some-> @state :api :mutate)]
                        (:psi.agent-session/session-id
                         (mf 'psi.extension/create-child-session
                             (cond-> {:session-name   agent-name
                                      :system-prompt  (:system-prompt config)
                                      :tool-defs      (:tools config)
                                      :thinking-level (:thinking-level config)}
                               (some? (:developer-prompt config))
                               (assoc :developer-prompt (:developer-prompt config)
                                      :developer-prompt-source (:developer-prompt-source config))

                               (some? (:preloaded-messages config))
                               (assoc :preloaded-messages (:preloaded-messages config))

                               (some? (:cache-breakpoints config))
                               (assoc :cache-breakpoints (:cache-breakpoints config))))))]
    {:agent/agent-name                 agent-name
     :agent/fork-session?              fork-session?
     :agent/include-result-in-context? include?
     :agent/session-id                 session-id
     :agent/query-fn                   qf
     :agent/get-api-key-fn             get-api-key
     :agent/on-start                   on-start
     :agent/on-finished                on-finished
     :agent/turn-count                 0
     :agent/current-prompt             nil
     :agent/last-text                  ""
     :agent/last-line                  nil
     :agent/error-line                 nil
     :agent/elapsed-ms                 0}))

(defn- public-workflow-data [data]
  (let [base (select-keys data
                          [:workflow/id
                           :workflow/error-message
                           :agent/agent-name
                           :agent/fork-session?
                           :agent/include-result-in-context?
                           :agent/turn-count
                           :agent/current-prompt
                           :agent/last-text
                           :agent/last-line
                           :agent/error-line
                           :agent/elapsed-ms])]
    (assoc base :agent/display (workflow-public-display base))))

(defn- register-agent-workflow-type! []
  (let [qf          (:query-fn @state)
        get-api-key (some-> @state :api :get-api-key)
        on-start    (fn [_] (refresh-widgets-later!))
        on-finished (make-on-finished-callback)
        r           (mutate! 'psi.extension.workflow/register-type
                             {:type            agent-type
                              :description     "Run a background agent workflow."
                              :chart           agent-chart
                              :start-event     :agent/start
                              :initial-data-fn (partial initial-agent-workflow-data qf get-api-key on-start on-finished)
                              :public-data-fn  public-workflow-data})]
    (when-let [e (:psi.extension.workflow/error r)]
      (notify! (str "Failed to register agent workflow type: " e) :error))))

(defn- await-terminal-workflow
  [id timeout-ms]
  (let [deadline (+ (now-ms) (long timeout-ms))]
    (loop []
      (let [wf (workflow-by-id id)]
        (cond
          (nil? wf)
          {:error (str "No agent #" id " found.")}

          (or (:psi.extension.workflow/done? wf)
              (:psi.extension.workflow/error? wf))
          {:workflow wf}

          (>= (now-ms) deadline)
          {:timeout true :workflow wf}

          :else
          (do
            (Thread/sleep 25)
            (recur)))))))

(defn- create-agent-workflow-input
  [task tool-call-id include-result-in-context? agent-name fork-session? config-override]
  (cond-> {:task task
           :tool-call-id tool-call-id
           :include-result-in-context include-result-in-context?}
    agent-name (assoc :agent agent-name)
    fork-session? (assoc :fork-session true)
    config-override (assoc :config-override config-override)))

(defn- sync-spawn-result [id timeout-ms]
  (let [{:keys [timeout workflow error]} (await-terminal-workflow id timeout-ms)
        wf    (or workflow (workflow-by-id id))
        text  (or (get-in wf [:psi.extension.workflow/data :agent/last-text])
                  (:psi.extension.workflow/result wf)
                  (:psi.extension.workflow/error-message wf)
                  "")
        ok?   (and wf
                   (not timeout)
                   (not error)
                   (not (true? (:psi.extension.workflow/error? wf))))
        text* (cond
                timeout (str "Error: Timed out waiting for Agent #" id " to finish.")
                error   (str "Error: " error)
                :else   text)]
    (refresh-widgets!)
    {:ok id
     :mode :sync
     :is-error (not ok?)
     :content text*}))

(defn- async-spawn-result [id r]
  (refresh-widgets!)
  {:ok id
   :mode :async
   :job-id (:psi.extension.background-job/id r)})

(defn- spawn-agent!
  [task agent-name {:keys [mode tool-call-id timeout-ms fork-session? include-result-in-context? config-override]
                    :or   {timeout-ms 300000}}]
  (let [task                       (str/trim (or task ""))
        agent-name                 (normalize-agent-name agent-name)
        mode*                      (or mode :async)
        fork-session?              (true? fork-session?)
        include-result-in-context? (true? include-result-in-context?)]
    (cond
      (str/blank? task)
      {:error "task is required"}

      (and agent-name (nil? (selected-agent-def (:query-fn @state) agent-name)))
      {:error (str "Unknown agent '" agent-name "'.")}

      (= mode* ::invalid)
      {:error "mode must be one of sync, async"}

      :else
      (let [id            (:next-id @state)
            tool-call-id* (or tool-call-id (str "agent-create-" id "-" (java.util.UUID/randomUUID)))
            r             (mutate! 'psi.extension.workflow/create
                                   {:type                  agent-type
                                    :id                    (str id)
                                    :track-background-job? (not= :sync mode*)
                                    :input                 (create-agent-workflow-input task tool-call-id* include-result-in-context? agent-name fork-session? config-override)})]
        (if-not (:psi.extension.workflow/created? r)
          {:error (or (:psi.extension.workflow/error r)
                      "Failed to create workflow")}
          (do
            (swap! state update :next-id inc)
            (if (= :sync mode*)
              (sync-spawn-result id timeout-ms)
              (async-spawn-result id r))))))))

(defn- continue-agent!
  ([id prompt]
   (continue-agent! id prompt nil))
  ([id prompt {:keys [tool-call-id include-result-in-context?]}]
   (let [wf            (workflow-by-id id)
         prompt        (str/trim (or prompt ""))
         tool-call-id* (or tool-call-id (str "agent-continue-" id "-" (java.util.UUID/randomUUID)))
         include?      (when (some? include-result-in-context?)
                         (true? include-result-in-context?))]
     (cond
       (nil? wf)
       {:error (str "No agent #" id " found.")}

       (:psi.extension.workflow/running? wf)
       {:error (str "Agent #" id " is still running.")}

       (str/blank? prompt)
       {:error "prompt is required"}

       :else
       (let [r (mutate! 'psi.extension.workflow/send-event
                        {:id                    (str id)
                         :event                 :agent/continue
                         :track-background-job? true
                         :data                  (cond-> {:prompt prompt
                                                         :tool-call-id tool-call-id*}
                                                  (some? include?)
                                                  (assoc :include-result-in-context include?))})]
         (if (:psi.extension.workflow/event-accepted? r)
           (do (refresh-widgets!)
               {:ok true
                :job-id (:psi.extension.background-job/id r)})
           {:error (or (:psi.extension.workflow/error r)
                       (str "Failed to continue agent #" id))}))))))

(defn- remove-agent!
  [id]
  (let [r (mutate! 'psi.extension.workflow/remove {:id (str id)})]
    (if (:psi.extension.workflow/removed? r)
      (do
        (clear-widget! id)
        (refresh-widgets!)
        {:ok true})
      {:error (or (:psi.extension.workflow/error r)
                  (str "No agent #" id " found."))})))

(defn- clear-all-agents! []
  (let [ids (map :psi.extension.workflow/id (agent-workflows))]
    (doseq [sid ids]
      (remove-agent! sid))
    (swap! state assoc :next-id 1)
    (refresh-widgets!)
    (count ids)))

(defn- list-agents-text []
  (let [subs (agent-workflows)]
    (if (empty? subs)
      "No active agents."
      (let [running (count (filter :psi.extension.workflow/running? subs))
            done    (count (filter :psi.extension.workflow/done? subs))
            errors  (count (filter :psi.extension.workflow/error? subs))
            lines   (mapcat (comp workflow-display/text-lines widget-lines) subs)]
        (str "Agents (" (count subs)
             " total · " running " running · " done " done · " errors " error):\n"
             (str/join "\n" lines))))))

(defn- parse-agent-args [args]
  (let [trimmed (str/trim (or args ""))]
    (cond
      (str/blank? trimmed)
      nil

      :else
      (let [tokens       (->> (str/split trimmed #"\s+")
                              (remove str/blank?)
                              vec)
            [fork? rest] (if (and (seq tokens)
                                  (contains? #{"--fork" "-f"} (first tokens)))
                           [true (subvec tokens 1)]
                           [false tokens])]
        (when (seq rest)
          (if (str/starts-with? (first rest) "@")
            (when (> (count rest) 1)
              {:fork-session? fork?
               :agent         (subs (first rest) 1)
               :task          (str/join " " (subvec rest 1))})
            {:fork-session? fork?
             :task          (str/join " " rest)}))))))

(defn- parse-agent-cont-args [args]
  (let [trimmed (str/trim (or args ""))
        idx     (str/index-of trimmed " ")]
    (when (and idx (pos? idx))
      (let [n      (parse-int (subs trimmed 0 idx))
            prompt (str/trim (subs trimmed (inc idx)))]
        (when (and n (seq prompt))
          {:id n :prompt prompt})))))

(def ^:private agent-actions
  ["create" "continue" "remove" "list"])

(defn- register-prompt-contribution! [api]
  (when-let [register! (:register-prompt-contribution api)]
    (register! prompt-contribution-id
               {:section  "Extension Capabilities"
                :priority 250
                :enabled  true
                :content  (prompt-contribution-content)})))

(defn- tool-error [message]
  {:content message :is-error true})

(defn- create-agent-tool-result [agent-name fork? r]
  (if-let [e (:error r)]
    (tool-error (str "Error: " e))
    (if (= :sync (:mode r))
      {:content (str "Agent #" (:ok r)
                     (when agent-name (str " (@" agent-name ")"))
                     (when fork? " [fork]")
                     " finished.\n\n"
                     (:content r))
       :is-error (boolean (:is-error r))}
      {:content (str "Agent #" (:ok r)
                     " spawned in background"
                     (when agent-name (str " (@" agent-name ")"))
                     (when fork? " [fork]")
                     (when-let [jid (:job-id r)]
                       (str " (job " jid ")"))
                     ".")
       :is-error false})))

(defn- continue-agent-tool-result [id result]
  (if-let [e (:error result)]
    (tool-error e)
    {:content (str "Agent #" id " continuing in background"
                   (when-let [jid (:job-id result)]
                     (str " (job " jid ")"))
                   ".")
     :is-error false}))

(defn- remove-agent-tool-result [id result]
  (if-let [e (:error result)]
    (tool-error e)
    {:content (str "Agent #" id " removed.")
     :is-error false}))

(defn- invalid-bool-error [field]
  (tool-error (str "Error: " field " must be true or false.")))

(defn- execute-create-agent-tool [args opts parsed]
  (let [{:keys [mode timeout-ms fork-session include-result-in-context skill]} parsed
        task         (str/trim (or (get args "task") ""))
        agent-name   (normalize-agent-name (get args "agent"))
        tool-call-id (tool-call-id-from-opts opts)]
    (cond
      (= timeout-ms ::invalid)
      (tool-error "Error: timeout_ms must be a positive integer.")

      (= fork-session ::invalid)
      (invalid-bool-error "fork_session")

      (= include-result-in-context ::invalid)
      (invalid-bool-error "include_result_in_context")

      (str/blank? task)
      (tool-error "Error: task is required.")

      :else
      (let [fork?      (true? fork-session)
            include?   (true? include-result-in-context)
            query-fn   (:query-fn @state)
            config0    (resolve-agent-config agent-name
                                             (concat (global-agents-dirs)
                                                     [(project-agents-dir query-fn)])
                                             (current-system-prompt query-fn))
            skill-msgs (when-not fork?
                         (skill-prelude-messages skill
                                                 (or (:psi.agent-session/skills (query! [:psi.agent-session/skills]))
                                                     [])
                                                 task))
            config     (cond-> config0
                         (seq skill-msgs)
                         (assoc :preloaded-messages skill-msgs
                                :cache-breakpoints #{:system :tools}))
            result     (spawn-agent! task agent-name {:mode mode
                                                      :tool-call-id tool-call-id
                                                      :timeout-ms timeout-ms
                                                      :fork-session? fork?
                                                      :include-result-in-context? include?
                                                      :config-override config})]
        (create-agent-tool-result agent-name fork? result)))))

(defn- execute-continue-agent-tool [args opts parsed]
  (let [{:keys [include-result-in-context]} parsed
        id           (parse-int (get args "id"))
        prompt       (str/trim (or (get args "prompt") ""))
        tool-call-id (tool-call-id-from-opts opts)]
    (cond
      (some? (get args "mode"))
      (tool-error "Error: mode is only supported for action=create")

      (some? (get args "fork_session"))
      (tool-error "Error: fork_session is only supported for action=create")

      (= include-result-in-context ::invalid)
      (invalid-bool-error "include_result_in_context")

      (nil? id)
      (tool-error "Error: id is required.")

      (str/blank? prompt)
      (tool-error "Error: prompt is required.")

      :else
      (continue-agent-tool-result id
                                  (continue-agent! id prompt {:tool-call-id tool-call-id
                                                              :include-result-in-context? include-result-in-context})))))

(defn- execute-remove-agent-tool [args]
  (let [id (parse-int (get args "id"))]
    (if (nil? id)
      (tool-error "Error: id is required.")
      (remove-agent-tool-result id (remove-agent! id)))))

(defn- parse-agent-tool-args [args]
  {:action                    (some-> (get args "action") str str/trim str/lower-case)
   :mode                      (parse-create-mode (get args "mode"))
   :timeout-ms                (parse-timeout-ms (get args "timeout_ms"))
   :fork-session              (parse-bool (get args "fork_session"))
   :skill                     (some-> (get args "skill") str str/trim not-empty)
   :include-result-in-context (parse-optional-bool (get args "include_result_in_context"))})

(defn- execute-agent-tool
  ([args]
   (execute-agent-tool args nil))
  ([args opts]
   (let [{:keys [action] :as parsed} (parse-agent-tool-args args)]
     (case action
       "create" (execute-create-agent-tool args opts parsed)
       "continue" (execute-continue-agent-tool args opts parsed)
       "remove" (execute-remove-agent-tool args)
       "list" {:content  (list-agents-text)
                :is-error false}
       (tool-error (str "Error: action must be one of "
                        (str/join ", " agent-actions)
                        "."))))))

(defn init [api]
  (swap! state assoc
         :api        api
         :query-fn   (:query api)
         :ui         (:ui api)
         :ext-path   (:path api)
         :next-id    1
         :widget-ids #{})

  (register-agent-workflow-type!)
  (register-prompt-contribution! api)

  ;; Tool (for main agent orchestration)
  ((:register-tool api)
   {:name        "agent"
    :label       "Agent"
    :description "Unified agent tool. action=create|continue|remove|list"
    :parameters  (pr-str {:type       "object"
                          :properties {"action" {:type "string"
                                                 :enum agent-actions
                                                 :description "Operation to run: create, continue, remove, or list"}
                                       "task"   {:type "string"
                                                 :description "Task text for action=create"}
                                       "agent"  {:type "string"
                                                 :description "Optional agent profile name from .psi/agents/*.md for action=create"}
                                       "mode"   {:type "string"
                                                 :enum ["sync" "async"]
                                                 :description "Optional execution mode for action=create (default async)"}
                                       "fork_session" {:type "boolean"
                                                       :description "Optional for action=create. When true, agent starts from a fork of the invoking session conversation."}
                                       "include_result_in_context" {:type "boolean"
                                                                    :description "Optional for action=create|continue. When true, inject agent result into parent LLM context as user+assistant messages while preserving alternation."}
                                       "timeout_ms" {:type "integer"
                                                     :description "Optional sync timeout in milliseconds for action=create, mode=sync (default 300000)"}
                                       "id"     {:type "integer"
                                                 :description "Agent id for action=continue/remove"}
                                       "prompt" {:type "string"
                                                 :description "Prompt text for action=continue"}}
                          :required   ["action"]})
    :execute     (fn
                   ([args]
                    (execute-agent-tool args nil))
                   ([args opts]
                    (execute-agent-tool args opts)))})

  ;; Slash commands
  ((:register-command api) "agent"
                           {:description "Spawn an agent: /agent [--fork|-f] [@agent] <task>"
                            :handler     (fn [args]
                                           (if-let [{:keys [agent task fork-session?]} (parse-agent-args args)]
                                             (let [r (spawn-agent! task agent {:mode :async
                                                                               :fork-session? fork-session?})]
                                               (if-let [e (:error r)]
                                                 (log! (str "Error: " e))
                                                 (log! (str "Spawned Agent #" (:ok r)
                                                            (when (seq agent)
                                                              (str " (@" (normalize-agent-name agent) ")"))
                                                            (when fork-session? " [fork]")
                                                            (when-let [jid (:job-id r)]
                                                              (str " (job " jid ")"))))))
                                             (log! "Usage: /agent [--fork|-f] [@agent] <task>")))})

  ((:register-command api) "agent-cont"
                           {:description "Continue an agent: /agent-cont <id> <prompt>"
                            :handler     (fn [args]
                                           (if-let [{:keys [id prompt]} (parse-agent-cont-args args)]
                                             (let [result (continue-agent! id prompt)]
                                               (if-let [e (:error result)]
                                                 (log! (str e))
                                                 (log! (str "Continuing Agent #" id "..."
                                                            (when-let [jid (:job-id result)]
                                                              (str " (job " jid ")"))))))
                                             (log! "Usage: /agent-cont <id> <prompt>")))})

  ((:register-command api) "agent-rm"
                           {:description "Remove an agent: /agent-rm <id>"
                            :handler     (fn [args]
                                           (let [id (parse-int (str/trim (or args "")))]
                                             (if (nil? id)
                                               (log! "Usage: /agent-rm <id>")
                                               (let [result (remove-agent! id)]
                                                 (if-let [e (:error result)]
                                                   (log! (str e))
                                                   (log! (str "Removed Agent #" id)))))))})

  ((:register-command api) "agent-clear"
                           {:description "Clear all agents"
                            :handler     (fn [_args]
                                           (let [n (clear-all-agents!)]
                                             (log! (if (zero? n)
                                                     "No agents to clear."
                                                     (str "Cleared " n " agent"
                                                          (when (not= 1 n) "s") ".")))))})

  ((:register-command api) "agent-list"
                           {:description "List all agents"
                            :handler     (fn [_args]
                                           (log! (list-agents-text)))})

  ;; Session lifecycle cleanup
  ((:on api) "session_switch"
             (fn [_event]
               (clear-all-agents!)
               (swap! state assoc :next-id 1 :widget-ids #{})
               (refresh-widgets!)))

  (refresh-widgets!)
  (notify! (str "agent loaded (workflow runtime, ui=" (name (ui-type)) ")") :info))
