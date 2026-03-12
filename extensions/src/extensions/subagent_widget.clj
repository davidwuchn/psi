(ns extensions.subagent-widget
  "Subagent widget backed by extension workflows.

   Commands/tools:
   - /sub [--fork|-f] [@agent] <task>
   - /subcont <id> <prompt>
   - /subrm <id>
   - /subclear
   - /sublist

   Workflow model:
   - one workflow per subagent id
   - explicit states: :idle -> :running -> :done | :error
   - continue transitions: :done/:error -> :running

   No extension-managed runner futures or ticker loops."
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.agent-core.core :as agent]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.tools :as tools]
   [psi.ai.models :as models]))

(defonce state
  (atom {:api        nil
         :query-fn   nil
         :ui         nil
         :ext-path   nil
         :next-id    1
         :widget-ids #{}}))

(def ^:private subagent-type :subagent)
(def ^:private subagent-tool-names #{"read" "bash" "edit" "write"})
(def ^:private prompt-contribution-id "subagent-widget-capabilities")

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
      (str "subagent-" (java.util.UUID/randomUUID))))

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

(defn- subagent-workflows []
  (let [ext-path (:ext-path @state)]
    (->> (all-workflows)
         (filter (fn [wf]
                   (and (= ext-path (:psi.extension/path wf))
                        (= subagent-type (:psi.extension.workflow/type wf)))))
         (sort-by (fn [wf]
                    (or (parse-int (:psi.extension.workflow/id wf))
                        Long/MAX_VALUE))))))

(defn- workflow-by-id [id]
  (let [sid (str id)]
    (some #(when (= sid (:psi.extension.workflow/id %)) %) (subagent-workflows))))

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
  "Parse markdown frontmatter and return {:name :description :system-prompt} when present."
  [raw]
  (let [m (re-find #"(?s)^---\n(.*?)\n---\n(.*)" (or raw ""))]
    (when m
      (let [fm-lines (str/split-lines (nth m 1))
            fm       (into {}
                           (keep (fn [line]
                                   (let [idx (str/index-of line ":")]
                                     (when (and idx (pos? idx))
                                       [(str/trim (subs line 0 idx))
                                        (str/trim (subs line (inc idx)))]))))
                           fm-lines)]
        (when-let [name (not-empty (get fm "name"))]
          {:name          name
           :description   (some-> (get fm "description") str/trim not-empty)
           :system-prompt (str/trim (nth m 2))})))))

(declare current-session-cwd)
(declare widget-placement)

(defn- normalize-agent-name [x]
  (some-> x str str/trim (str/replace #"^@" "") str/lower-case not-empty))

(defn- agents-dir [query-fn]
  (when-let [cwd (current-session-cwd query-fn)]
    (str cwd "/.psi/agents")))

(defn- load-agent-defs [query-fn]
  (let [dir (some-> (agents-dir query-fn) io/file)]
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
                           [k {:name k
                               :description (:description parsed)
                               :system-prompt (:system-prompt parsed)}]))
                       (catch Exception _ nil)))))
           (into {}))
      {})))

(defn- selected-agent-def [query-fn agent-name]
  (when-let [name (normalize-agent-name agent-name)]
    (get (load-agent-defs query-fn) name)))

(defn- selected-agent-prompt [query-fn agent-name]
  (:system-prompt (selected-agent-def query-fn agent-name)))

(defn- compose-system-prompt [base-prompt query-fn agent-name]
  (if-let [agent-prompt (selected-agent-prompt query-fn agent-name)]
    (let [base (str/trim (or base-prompt ""))
          profile (str "[Subagent Profile: " (or (normalize-agent-name agent-name) "custom") "]\n"
                       agent-prompt)]
      (if (seq base)
        (str base "\n\n" profile)
        profile))
    base-prompt))

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

(defn- create-sub-agent-ctx [query-fn agent-name {:keys [fork-session?]}]
  (let [agent-ctx      (agent/create-context)
        tools          (->> tools/all-tools
                            (filter #(contains? subagent-tool-names (:name %)))
                            vec)
        base-prompt    (current-system-prompt query-fn)
        final-prompt   (compose-system-prompt base-prompt query-fn agent-name)
        fork-messages  (when fork-session?
                         (current-session-messages query-fn))]
    (agent/create-agent-in! agent-ctx)
    (agent/set-tools-in! agent-ctx tools)
    (agent/set-thinking-level-in! agent-ctx :off)
    (when (seq final-prompt)
      (agent/set-system-prompt-in! agent-ctx final-prompt))
    (when (seq fork-messages)
      (agent/replace-messages-in! agent-ctx fork-messages))
    agent-ctx))

(defn- current-session-cwd [query-fn]
  (when query-fn
    (:psi.agent-session/cwd
     (query-fn [:psi.agent-session/cwd]))))

(defn- create-sub-session-ctx [agent-ctx query-fn]
  {:agent-ctx agent-ctx
   :cwd       (current-session-cwd query-fn)
   :session-data-atom
   (atom {:tool-output-overrides {}})
   :tool-output-stats-atom
   (atom {:calls []
          :aggregates {:total-context-bytes 0
                       :by-tool {}
                       :limit-hits-by-tool {}}})})

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
  (or (:subagent/turn-count (wf-data wf)) 1))

(defn- wf-task [wf]
  (or (:subagent/current-prompt (wf-data wf))
      (get-in wf [:psi.extension.workflow/input :task])
      ""))

(defn- wf-last-text [wf]
  (or (:subagent/last-text (wf-data wf))
      (:psi.extension.workflow/result wf)
      (:psi.extension.workflow/error-message wf)
      ""))

(defn- wf-agent-name [wf]
  (or (:subagent/agent-name (wf-data wf))
      (get-in wf [:psi.extension.workflow/input :agent])))

(defn- wf-forked? [wf]
  (or (= true (:subagent/fork-session? (wf-data wf)))
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

(defn- wf-error-line [wf]
  (or (clean-error-line (:psi.extension.workflow/error-message wf))
      (some-> (wf-last-text wf) last-non-blank-line clean-error-line)))

(defn- widget-detail-line [wf]
  (cond
    (:psi.extension.workflow/error? wf)
    (when-let [err (wf-error-line wf)]
      (str "  ! " (task-preview err 100)))

    :else
    (when-let [last-line (some-> (wf-last-text wf) last-non-blank-line str/trim not-empty)]
      (str "  "
           (if (:psi.extension.workflow/done? wf) "↳ " "… ")
           (task-preview last-line 100)))))

(defn- widget-action-line [wf]
  (when (and (not (:psi.extension.workflow/running? wf))
             (or (:psi.extension.workflow/done? wf)
                 (:psi.extension.workflow/error? wf)))
    (str "  /subcont " (:psi.extension.workflow/id wf) " <prompt> · /subrm "
         (:psi.extension.workflow/id wf))))

(defn- widget-lines [wf]
  (let [agent-tag   (when-let [agent-name (some-> (wf-agent-name wf) str str/trim not-empty)]
                      (str " · @" agent-name))
        fork-tag    (when (wf-forked? wf) " · fork")
        line-1      (str (status-icon wf)
                         " Subagent #" (:psi.extension.workflow/id wf)
                         " " (phase-badge wf)
                         " · T" (wf-turn-count wf)
                         " · " (elapsed-seconds wf) "s"
                         agent-tag
                         fork-tag
                         " · " (task-preview (wf-task wf) 52))
        detail-line (widget-detail-line wf)
        action-line (widget-action-line wf)]
    (cond-> [line-1]
      (seq detail-line) (conj detail-line)
      (seq action-line) (conj action-line))))

(defn- available-agent-defs []
  (load-agent-defs (:query-fn @state)))

(defn- prompt-agent-line [[name {:keys [description]}]]
  (if (seq description)
    (str "- " name ": " description)
    (str "- " name)))

(defn- prompt-contribution-content []
  (let [agents (available-agent-defs)]
    (str "tool: subagent\n"
         "available agents:\n"
         (if (seq agents)
           (->> agents
                (sort-by key)
                (map prompt-agent-line)
                (str/join "\n"))
           "- none"))))

(defn- sync-prompt-contribution! []
  (when-let [update! (some-> @state :api :update-prompt-contribution)]
    (update! prompt-contribution-id
             {:content (prompt-contribution-content)})))

(defn- clear-widget! [id]
  (when-let [ui (:ui @state)]
    ((:clear-widget ui) (str "sub-" id))))

(defn- refresh-widgets! []
  (when-let [ui (:ui @state)]
    (let [wfs         (subagent-workflows)
          current-ids (into #{} (map #(str "sub-" (:psi.extension.workflow/id %)) wfs))
          old-ids     (:widget-ids @state)
          removed     (set/difference old-ids current-ids)]
      (doseq [wid removed]
        ((:clear-widget ui) wid))
      (doseq [wf wfs]
        ((:set-widget ui)
         (str "sub-" (:psi.extension.workflow/id wf))
         (widget-placement)
         (widget-lines wf)))
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

(defn- notify! [text level]
  (if-let [ui (:ui @state)]
    ((:notify ui) text level)
    (println text)))

(defn- context-last-role [query-fn]
  (->> (current-session-messages query-fn)
       (remove :custom-type)
       (map :role)
       (filter #{{"user" "assistant"}})
       last))

(defn- inject-result-into-context!
  [{:keys [id turn-count result-text]}]
  (when-let [mutate-fn (some-> @state :api :mutate)]
    (let [query-fn     (:query-fn @state)
          last-role    (context-last-role query-fn)
          turn*        (max 1 (long (or turn-count 1)))
          job-ref      (str "subagent-" id "-turn-" turn*)
          user-content (str "Subagent job id: " job-ref)
          asst-content (or result-text "")]
      ;; Keep strict user/assistant alternation for provider-facing context.
      ;; If the last role is user, emit an assistant bridge first.
      (when (= "user" last-role)
        (try
          (mutate-fn 'psi.extension/send-message
                     {:role "assistant"
                      :content "(subagent context bridge)"})
          (catch Exception _ nil)))
      (try
        (mutate-fn 'psi.extension/send-message
                   {:role "user"
                    :content user-content})
        (catch Exception _ nil))
      (try
        (mutate-fn 'psi.extension/send-message
                   {:role "assistant"
                    :content asst-content})
        (catch Exception _ nil)))))

(defn- emit-result-message!
  [{:keys [id prompt turn-count ok? elapsed-ms result-text include-result-in-context?]}]
  (let [seconds  (quot (or elapsed-ms 0) 1000)
        heading  (str "Subagent #" id
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
        (do
          (try
            (mutate-fn 'psi.extension/append-entry
                       {:custom-type "subagent-result"
                        :data        full})
            (catch Exception _ nil))
          (try
            (mutate-fn 'psi.extension/send-message
                       {:role        "assistant"
                        :content     full
                        :custom-type "subagent-result"})
            (catch Exception _ nil)))))
    (println "\n[subagent-result]" heading "\n")
    (when (seq result-text)
      (println (task-preview result-text 800)))
    (notify! (str "Subagent #" id " "
                  (if ok? "done" "error")
                  " in " seconds "s")
             (if ok? :info :error))))

(defn- result->text [result]
  (->> (:content result)
       (keep (fn [c]
               (case (:type c)
                 :text (:text c)
                 :error (:text c)
                 nil)))
       (str/join "\n")))

(defn- run-subagent-job
  [{:keys [agent-ctx session-ctx prompt query-fn get-api-key-fn]}]
  (let [started (now-ms)]
    (try
      (let [model        (resolve-active-model query-fn)
            _            (when-not model
                           (throw (ex-info "No active model available" {})))
            api-key      (when (fn? get-api-key-fn)
                           (get-api-key-fn (:provider model)))
            session-ctx* (or session-ctx
                             (create-sub-session-ctx agent-ctx query-fn))
            user-msg     {:role      "user"
                          :content   [{:type :text :text (or prompt "")}]
                          :timestamp (java.time.Instant/now)}
            result       (executor/run-agent-loop!
                          nil
                          session-ctx*
                          agent-ctx
                          model
                          [user-msg]
                          (cond-> {}
                            api-key (assoc :api-key api-key)))
            text         (result->text result)
            ok?          (not= :error (:stop-reason result))
            elapsed      (- (now-ms) started)]
        {:ok?           ok?
         :text          text
         :elapsed-ms    elapsed
         :error-message (:error-message result)})
      (catch Exception e
        {:ok?           false
         :text          (str "Error: " (ex-message e))
         :elapsed-ms    (- (now-ms) started)
         :error-message (ex-message e)}))))

(defn- start-script
  [_ data]
  (let [prompt   (str/trim (or (get-in data [:_event :data :prompt])
                               (get-in data [:workflow/input :task])
                               ""))
        turn     (max 1 (long (or (:subagent/turn-count data) 1)))
        on-start (:subagent/on-start data)]
    (when (fn? on-start)
      (on-start {:id (:workflow/id data)
                 :prompt prompt
                 :turn-count turn}))
    [{:op :assign
      :data {:subagent/current-prompt prompt
             :subagent/turn-count turn
             :subagent/last-text ""
             :subagent/elapsed-ms 0
             :workflow/error-message nil
             :workflow/result nil}}]))

(defn- continue-script
  [_ data]
  (let [prompt    (str/trim (or (get-in data [:_event :data :prompt])
                                (:subagent/current-prompt data)
                                ""))
        include?  (get-in data [:_event :data :include-result-in-context])
        include?* (if (nil? include?)
                    (:subagent/include-result-in-context? data)
                    (true? include?))
        turn      (inc (long (or (:subagent/turn-count data) 1)))
        on-start  (:subagent/on-start data)]
    (when (fn? on-start)
      (on-start {:id (:workflow/id data)
                 :prompt prompt
                 :turn-count turn}))
    [{:op :assign
      :data {:subagent/current-prompt prompt
             :subagent/include-result-in-context? include?*
             :subagent/turn-count turn
             :subagent/last-text ""
             :subagent/elapsed-ms 0
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
        prompt      (or (:subagent/current-prompt data) "")
        turn-count  (long (or (:subagent/turn-count data) 1))
        include?    (true? (:subagent/include-result-in-context? data))
        on-finished (:subagent/on-finished data)]
    (when (fn? on-finished)
      (on-finished {:id                          (:workflow/id data)
                    :prompt                      prompt
                    :turn-count                  turn-count
                    :ok?                         true
                    :elapsed-ms                  elapsed-ms
                    :result-text                 text
                    :include-result-in-context?  include?}))
    [{:op :assign
      :data {:subagent/last-text text
             :subagent/elapsed-ms elapsed-ms
             :workflow/error-message nil
             :workflow/result text}}]))

(defn- error-script
  [_ data]
  (let [ev          (get-in data [:_event :data])
        msg         (or (:error-message ev) "Unknown error")
        text        (or (:text ev) (str "Error: " msg))
        elapsed-ms  (long (or (:elapsed-ms ev) 0))
        prompt      (or (:subagent/current-prompt data) "")
        turn-count  (long (or (:subagent/turn-count data) 1))
        include?    (true? (:subagent/include-result-in-context? data))
        on-finished (:subagent/on-finished data)]
    (when (fn? on-finished)
      (on-finished {:id                          (:workflow/id data)
                    :prompt                      prompt
                    :turn-count                  turn-count
                    :ok?                         false
                    :elapsed-ms                  elapsed-ms
                    :result-text                 text
                    :include-result-in-context?  include?}))
    [{:op :assign
      :data {:subagent/last-text text
             :subagent/elapsed-ms elapsed-ms
             :workflow/error-message msg
             :workflow/result text}}]))

(def ^:private subagent-chart
  (chart/statechart {:id :subagent-workflow}
                    (ele/state {:id :idle}
                               (ele/transition {:event :subagent/start :target :running}
                                               (ele/script {:expr start-script})))

                    (ele/state {:id :running}
                               (ele/invoke {:id     :runner
                                            :type   :future
                                            :params (fn [_ data]
                                                      {:agent-ctx       (:subagent/agent-ctx data)
                                                       :session-ctx     (:subagent/session-ctx data)
                                                       :prompt          (:subagent/current-prompt data)
                                                       :query-fn        (:subagent/query-fn data)
                                                       :get-api-key-fn  (:subagent/get-api-key-fn data)})
                                            :src    run-subagent-job})
                               (ele/transition {:event :done.invoke.runner
                                                :target :done
                                                :cond   invoke-ok?}
                                               (ele/script {:expr done-script}))
                               (ele/transition {:event :done.invoke.runner
                                                :target :error}
                                               (ele/script {:expr error-script})))

                    (ele/state {:id :done}
                               (ele/transition {:event :subagent/continue :target :running}
                                               (ele/script {:expr continue-script})))

                    (ele/state {:id :error}
                               (ele/transition {:event :subagent/continue :target :running}
                                               (ele/script {:expr continue-script})))))

(defn- register-subagent-workflow-type! []
  (let [qf          (:query-fn @state)
        get-api-key (some-> @state :api :get-api-key)
        on-start    (fn [_]
                      (refresh-widgets-later!))
        on-finished (fn [{:keys [id prompt turn-count ok? elapsed-ms result-text include-result-in-context?]}]
                      (refresh-widgets-later!)
                      (emit-result-message! {:id                          (or (parse-int id) id)
                                             :prompt                      prompt
                                             :turn-count                  turn-count
                                             :ok?                         ok?
                                             :elapsed-ms                  elapsed-ms
                                             :result-text                 result-text
                                             :include-result-in-context?  include-result-in-context?}))
        r           (mutate! 'psi.extension.workflow/register-type
                             {:type            subagent-type
                              :description     "Run a background subagent workflow."
                              :chart           subagent-chart
                              :start-event     :subagent/start
                              :initial-data-fn (fn [input]
                                                 (let [agent-name    (normalize-agent-name (get input :agent))
                                                       fork-session? (true? (get input :fork-session))
                                                       include?      (true? (get input :include-result-in-context))
                                                       agent-ctx      (create-sub-agent-ctx qf agent-name {:fork-session? fork-session?})]
                                                   {:subagent/agent-name                  agent-name
                                                    :subagent/fork-session?               fork-session?
                                                    :subagent/include-result-in-context?  include?
                                                    :subagent/agent-ctx                   agent-ctx
                                                    :subagent/session-ctx                 (create-sub-session-ctx agent-ctx qf)
                                                    :subagent/query-fn                    qf
                                                    :subagent/get-api-key-fn              get-api-key
                                                    :subagent/on-start                    on-start
                                                    :subagent/on-finished                 on-finished
                                                    :subagent/turn-count                  0
                                                    :subagent/current-prompt              nil
                                                    :subagent/last-text                   ""
                                                    :subagent/elapsed-ms                  0}))
                              :public-data-fn  (fn [data]
                                                 (select-keys data
                                                              [:subagent/agent-name
                                                               :subagent/fork-session?
                                                               :subagent/include-result-in-context?
                                                               :subagent/turn-count
                                                               :subagent/current-prompt
                                                               :subagent/last-text
                                                               :subagent/elapsed-ms]))})]
    (when-let [e (:psi.extension.workflow/error r)]
      (notify! (str "Failed to register subagent workflow type: " e) :error))))

(defn- await-terminal-workflow
  [id timeout-ms]
  (let [deadline (+ (now-ms) (long timeout-ms))]
    (loop []
      (let [wf (workflow-by-id id)]
        (cond
          (nil? wf)
          {:error (str "No subagent #" id " found.")}

          (or (:psi.extension.workflow/done? wf)
              (:psi.extension.workflow/error? wf))
          {:workflow wf}

          (>= (now-ms) deadline)
          {:timeout true :workflow wf}

          :else
          (do
            (Thread/sleep 25)
            (recur)))))))

(defn- spawn-subagent!
  [task agent-name {:keys [mode tool-call-id timeout-ms fork-session? include-result-in-context?]
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
            tool-call-id* (or tool-call-id (str "subagent-create-" id "-" (java.util.UUID/randomUUID)))
            r             (mutate! 'psi.extension.workflow/create
                                   {:type                   subagent-type
                                    :id                     (str id)
                                    :track-background-job?  (not= :sync mode*)
                                    :input                  (cond-> {:task task
                                                                     :tool-call-id tool-call-id*
                                                                     :include-result-in-context include-result-in-context?}
                                                              agent-name (assoc :agent agent-name)
                                                              fork-session? (assoc :fork-session true))})]
        (if-not (:psi.extension.workflow/created? r)
          {:error (or (:psi.extension.workflow/error r)
                      "Failed to create workflow")}
          (do
            (swap! state update :next-id inc)
            (if (= :sync mode*)
              (let [{:keys [timeout workflow error]} (await-terminal-workflow id timeout-ms)
                    wf       (or workflow (workflow-by-id id))
                    text     (or (get-in wf [:psi.extension.workflow/data :subagent/last-text])
                                 (:psi.extension.workflow/result wf)
                                 (:psi.extension.workflow/error-message wf)
                                 "")
                    elapsed  (or (get-in wf [:psi.extension.workflow/data :subagent/elapsed-ms])
                                 (:psi.extension.workflow/elapsed-ms wf)
                                 0)
                    ok?      (and wf
                                  (not timeout)
                                  (not error)
                                  (not (true? (:psi.extension.workflow/error? wf))))
                    text*    (cond
                               timeout
                               (str "Error: Timed out waiting for Subagent #" id " to finish.")

                               error
                               (str "Error: " error)

                               :else
                               text)]
                (refresh-widgets!)
                (emit-result-message! {:id                          id
                                       :prompt                      task
                                       :turn-count                  1
                                       :ok?                         ok?
                                       :elapsed-ms                  elapsed
                                       :result-text                 text*
                                       :include-result-in-context?  include-result-in-context?})
                {:ok id
                 :mode :sync
                 :is-error (not ok?)
                 :content text*})
              (do
                (refresh-widgets!)
                {:ok id
                 :mode :async
                 :job-id (:psi.extension.background-job/id r)}))))))))

(defn- continue-subagent!
  ([id prompt]
   (continue-subagent! id prompt nil))
  ([id prompt {:keys [tool-call-id include-result-in-context?]}]
   (let [wf            (workflow-by-id id)
         prompt        (str/trim (or prompt ""))
         tool-call-id* (or tool-call-id (str "subagent-continue-" id "-" (java.util.UUID/randomUUID)))
         include?      (when (some? include-result-in-context?)
                         (true? include-result-in-context?))]
     (cond
       (nil? wf)
       {:error (str "No subagent #" id " found.")}

       (:psi.extension.workflow/running? wf)
       {:error (str "Subagent #" id " is still running.")}

       (str/blank? prompt)
       {:error "prompt is required"}

       :else
       (let [r (mutate! 'psi.extension.workflow/send-event
                        {:id                    (str id)
                         :event                 :subagent/continue
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
                       (str "Failed to continue subagent #" id))}))))))

(defn- remove-subagent!
  [id]
  (let [r (mutate! 'psi.extension.workflow/remove {:id (str id)})]
    (if (:psi.extension.workflow/removed? r)
      (do
        (clear-widget! id)
        (refresh-widgets!)
        {:ok true})
      {:error (or (:psi.extension.workflow/error r)
                  (str "No subagent #" id " found."))})))

(defn- clear-all-subagents! []
  (let [ids (map :psi.extension.workflow/id (subagent-workflows))]
    (doseq [sid ids]
      (remove-subagent! sid))
    (swap! state assoc :next-id 1)
    (refresh-widgets!)
    (count ids)))

(defn- list-subagents-text []
  (let [subs (subagent-workflows)]
    (if (empty? subs)
      "No active subagents."
      (let [running (count (filter :psi.extension.workflow/running? subs))
            done    (count (filter :psi.extension.workflow/done? subs))
            errors  (count (filter :psi.extension.workflow/error? subs))
            lines   (mapcat
                     (fn [s]
                       (let [agent-tag (when-let [agent-name (some-> (wf-agent-name s) str str/trim not-empty)]
                                         (str " · @" agent-name))
                             fork-tag  (when (wf-forked? s) " · fork")
                             base (str "#" (:psi.extension.workflow/id s)
                                       " " (phase-badge s)
                                       " · T" (wf-turn-count s)
                                       " · " (elapsed-seconds s) "s"
                                       agent-tag
                                       fork-tag
                                       " · " (task-preview (wf-task s) 70))
                             err  (when (:psi.extension.workflow/error? s)
                                    (when-let [e (wf-error-line s)]
                                      (str "   ! " (task-preview e 100))))]
                         (cond-> [base]
                           (seq err) (conj err))))
                     subs)]
        (str "Subagents (" (count subs)
             " total · " running " running · " done " done · " errors " error):\n"
             (str/join "\n" lines))))))

(defn- parse-sub-args [args]
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

(defn- parse-subcont-args [args]
  (let [trimmed (str/trim (or args ""))
        idx     (str/index-of trimmed " ")]
    (when (and idx (pos? idx))
      (let [n      (parse-int (subs trimmed 0 idx))
            prompt (str/trim (subs trimmed (inc idx)))]
        (when (and n (seq prompt))
          {:id n :prompt prompt})))))

(def ^:private subagent-actions
  ["create" "continue" "remove" "list"])

(defn- register-prompt-contribution! [api]
  (when-let [register! (:register-prompt-contribution api)]
    (register! prompt-contribution-id
               {:section  "Extension Capabilities"
                :priority 250
                :enabled  true
                :content  (prompt-contribution-content)})))

(defn- execute-subagent-tool
  ([args]
   (execute-subagent-tool args nil))
  ([args opts]
   (let [action                    (some-> (get args "action") str str/trim str/lower-case)
         mode                      (parse-create-mode (get args "mode"))
         timeout-ms                (parse-timeout-ms (get args "timeout_ms"))
         fork-session              (parse-bool (get args "fork_session"))
         include-result-in-context (parse-optional-bool (get args "include_result_in_context"))]
     (case action
       "create"
       (let [task         (str/trim (or (get args "task") ""))
             agent-name   (normalize-agent-name (get args "agent"))
             tool-call-id (tool-call-id-from-opts opts)]
         (cond
           (= timeout-ms ::invalid)
           {:content "Error: timeout_ms must be a positive integer." :is-error true}

           (= fork-session ::invalid)
           {:content "Error: fork_session must be true or false." :is-error true}

           (= include-result-in-context ::invalid)
           {:content "Error: include_result_in_context must be true or false." :is-error true}

           (str/blank? task)
           {:content "Error: task is required." :is-error true}

           :else
           (let [fork?    (true? fork-session)
                 include? (true? include-result-in-context)
                 r        (spawn-subagent! task agent-name {:mode mode
                                                            :tool-call-id tool-call-id
                                                            :timeout-ms timeout-ms
                                                            :fork-session? fork?
                                                            :include-result-in-context? include?})]
             (if-let [e (:error r)]
               {:content (str "Error: " e) :is-error true}
               (if (= :sync (:mode r))
                 {:content (str "Subagent #" (:ok r)
                                (when agent-name (str " (@" agent-name ")"))
                                (when fork? " [fork]")
                                " finished.\n\n"
                                (:content r))
                  :is-error (boolean (:is-error r))}
                 {:content (str "Subagent #" (:ok r)
                                " spawned in background"
                                (when agent-name (str " (@" agent-name ")"))
                                (when fork? " [fork]")
                                (when-let [jid (:job-id r)]
                                  (str " (job " jid ")"))
                                ".")
                  :is-error false})))))

       "continue"
       (let [id           (parse-int (get args "id"))
             prompt       (str/trim (or (get args "prompt") ""))
             tool-call-id (tool-call-id-from-opts opts)]
         (cond
           (not (nil? (get args "mode")))
           {:content "Error: mode is only supported for action=create" :is-error true}

           (not (nil? (get args "fork_session")))
           {:content "Error: fork_session is only supported for action=create" :is-error true}

           (= include-result-in-context ::invalid)
           {:content "Error: include_result_in_context must be true or false." :is-error true}

           (nil? id)
           {:content "Error: id is required." :is-error true}

           (str/blank? prompt)
           {:content "Error: prompt is required." :is-error true}

           :else
           (let [result (continue-subagent! id prompt {:tool-call-id tool-call-id
                                                       :include-result-in-context? include-result-in-context})]
             (if-let [e (:error result)]
               {:content e :is-error true}
               {:content (str "Subagent #" id " continuing in background"
                              (when-let [jid (:job-id result)]
                                (str " (job " jid ")"))
                              ".")
                :is-error false}))))

       "remove"
       (let [id (parse-int (get args "id"))]
         (if (nil? id)
           {:content "Error: id is required." :is-error true}
           (let [result (remove-subagent! id)]
             (if-let [e (:error result)]
               {:content e :is-error true}
               {:content (str "Subagent #" id " removed.")
                :is-error false}))))

       "list"
       {:content  (list-subagents-text)
        :is-error false}

       {:content (str "Error: action must be one of "
                      (str/join ", " subagent-actions)
                      ".")
        :is-error true}))))

(defn init [api]
  (swap! state assoc
         :api        api
         :query-fn   (:query api)
         :ui         (:ui api)
         :ext-path   (:path api)
         :next-id    1
         :widget-ids #{})

  (register-subagent-workflow-type!)
  (register-prompt-contribution! api)

  ;; Tool (for main agent orchestration)
  ((:register-tool api)
   {:name        "subagent"
    :label       "Subagent"
    :description "Unified subagent tool. action=create|continue|remove|list"
    :parameters  (pr-str {:type       "object"
                          :properties {"action" {:type "string"
                                                 :enum subagent-actions
                                                 :description "Operation to run: create, continue, remove, or list"}
                                       "task"   {:type "string"
                                                 :description "Task text for action=create"}
                                       "agent"  {:type "string"
                                                 :description "Optional agent profile name from .psi/agents/*.md for action=create"}
                                       "mode"   {:type "string"
                                                 :enum ["sync" "async"]
                                                 :description "Optional execution mode for action=create (default async)"}
                                       "fork_session" {:type "boolean"
                                                       :description "Optional for action=create. When true, subagent starts from a fork of the invoking session conversation."}
                                       "include_result_in_context" {:type "boolean"
                                                                    :description "Optional for action=create|continue. When true, inject subagent result into parent LLM context as user+assistant messages while preserving alternation."}
                                       "timeout_ms" {:type "integer"
                                                     :description "Optional sync timeout in milliseconds for action=create, mode=sync (default 300000)"}
                                       "id"     {:type "integer"
                                                 :description "Subagent id for action=continue/remove"}
                                       "prompt" {:type "string"
                                                 :description "Prompt text for action=continue"}}
                          :required   ["action"]})
    :execute     (fn
                   ([args]
                    (execute-subagent-tool args nil))
                   ([args opts]
                    (execute-subagent-tool args opts)))})

  ;; Slash commands
  ((:register-command api) "sub"
                           {:description "Spawn a subagent: /sub [--fork|-f] [@agent] <task>"
                            :handler     (fn [args]
                                           (if-let [{:keys [agent task fork-session?]} (parse-sub-args args)]
                                             (let [r (spawn-subagent! task agent {:mode :async
                                                                                  :fork-session? fork-session?})]
                                               (if-let [e (:error r)]
                                                 (println (str "Error: " e))
                                                 (println (str "Spawned Subagent #" (:ok r)
                                                               (when (seq agent)
                                                                 (str " (@" (normalize-agent-name agent) ")"))
                                                               (when fork-session? " [fork]")
                                                               (when-let [jid (:job-id r)]
                                                                 (str " (job " jid ")"))))))
                                             (println "Usage: /sub [--fork|-f] [@agent] <task>")))})

  ((:register-command api) "subcont"
                           {:description "Continue a subagent: /subcont <id> <prompt>"
                            :handler     (fn [args]
                                           (if-let [{:keys [id prompt]} (parse-subcont-args args)]
                                             (let [result (continue-subagent! id prompt)]
                                               (if-let [e (:error result)]
                                                 (println e)
                                                 (println (str "Continuing Subagent #" id "..."
                                                               (when-let [jid (:job-id result)]
                                                                 (str " (job " jid ")"))))))
                                             (println "Usage: /subcont <id> <prompt>")))})

  ((:register-command api) "subrm"
                           {:description "Remove a subagent: /subrm <id>"
                            :handler     (fn [args]
                                           (let [id (parse-int (str/trim (or args "")))]
                                             (if (nil? id)
                                               (println "Usage: /subrm <id>")
                                               (let [result (remove-subagent! id)]
                                                 (if-let [e (:error result)]
                                                   (println e)
                                                   (println (str "Removed Subagent #" id)))))))})

  ((:register-command api) "subclear"
                           {:description "Clear all subagents"
                            :handler     (fn [_args]
                                           (let [n (clear-all-subagents!)]
                                             (println (if (zero? n)
                                                        "No subagents to clear."
                                                        (str "Cleared " n " subagent"
                                                             (when (not= 1 n) "s") ".")))))})

  ((:register-command api) "sublist"
                           {:description "List all subagents"
                            :handler     (fn [_args]
                                           (println (list-subagents-text)))})

  ;; Session lifecycle cleanup
  ((:on api) "session_switch"
             (fn [_event]
               (clear-all-subagents!)
               (swap! state assoc :next-id 1 :widget-ids #{})
               (refresh-widgets!)))

  (refresh-widgets!)
  (notify! (str "subagent-widget loaded (workflow runtime, ui=" (name (ui-type)) ")") :info))
