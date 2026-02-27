(ns extensions.subagent-widget
  "Subagent Widget — /sub, /subcont, /subrm, /subclear plus stacked live widgets.

   This is a Clojure equivalent of pi-vs-claude-code's subagent-widget.
   Each subagent runs in-process with its own persistent agent-core context,
   so /subcont continues the same conversation state."
  (:require
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.tools :as tools]
   [psi.ai.models :as models]))

(defonce state
  (atom {:api           nil
         :query-fn      nil
         :ui            nil
         :agents        {}
         :next-id       1
         :ticker-future nil}))

(def ^:private subagent-tool-names #{"read" "bash" "edit" "write"})

(defn- now-ms [] (System/currentTimeMillis))

(defn- parse-int [x]
  (cond
    (number? x) (long x)
    (string? x) (try (Long/parseLong (str/trim x)) (catch Exception _ nil))
    :else nil))

(defn- task-preview [s n]
  (let [s (or s "")]
    (if (> (count s) n)
      (str (subs s 0 (max 0 (- n 3))) "...")
      s)))

(defn- status-icon [status]
  (case status
    :running "●"
    :done "✓"
    :error "✗"
    "?"))

(defn- elapsed-seconds [agent-state]
  (let [running? (= :running (:status agent-state))
        elapsed  (or (:elapsed-ms agent-state) 0)
        started  (:started-at-ms agent-state)]
    (if (and running? started)
      (quot (- (now-ms) started) 1000)
      (quot elapsed 1000))))

(defn- last-non-blank-line [s]
  (->> (str/split-lines (or s ""))
       (remove str/blank?)
       last))

(defn- widget-lines [agent-state]
  (let [turn-text  (when (> (:turn-count agent-state) 1)
                     (str " · Turn " (:turn-count agent-state)))
        line-1     (str (status-icon (:status agent-state))
                        " Subagent #" (:id agent-state)
                        turn-text
                        "  " (task-preview (:task agent-state) 48)
                        "  (" (elapsed-seconds agent-state) "s)"
                        " | Tools: " (:tool-count agent-state))
        last-line  (last-non-blank-line (:last-text agent-state))]
    (cond-> [line-1]
      (seq last-line) (conj (str "  " (task-preview last-line 100))))))

(defn- clear-widget! [id]
  (when-let [ui (:ui @state)]
    ((:clear-widget ui) (str "sub-" id))))

(defn- refresh-widgets! []
  (when-let [ui (:ui @state)]
    (doseq [[id sub] (sort-by key (:agents @state))]
      ((:set-widget ui) (str "sub-" id) :above-editor (widget-lines sub)))))

(defn- notify! [text level]
  (if-let [ui (:ui @state)]
    ((:notify ui) text level)
    (println text)))

(defn- emit-result-message!
  [{:keys [id prompt turn-count ok? elapsed-ms result-text]}]
  (let [seconds  (quot (or elapsed-ms 0) 1000)
        heading  (str "Subagent #" id
                      (when (> (or turn-count 1) 1)
                        (str " (Turn " turn-count ")"))
                      " finished \"" prompt "\" in " seconds "s")
        full     (str heading "\n\nResult:\n"
                      (if (> (count (or result-text "")) 8000)
                        (str (subs result-text 0 8000) "\n\n... [truncated]")
                        (or result-text "")))]
    ;; Persist as a custom session entry so it survives resumes.
    (when-let [mutate-fn (some-> @state :api :mutate)]
      (try
        (mutate-fn 'psi.extension/append-entry
                   {:custom-type "subagent-result"
                    :data        full})
        (catch Exception _ nil))
      ;; Also inject into the live transcript (and TUI queue when active).
      (try
        (mutate-fn 'psi.extension/send-message
                   {:role        "assistant"
                    :content     full
                    :custom-type "subagent-result"})
        (catch Exception _ nil)))
    ;; Plain-mode visibility (useful when not in TUI).
    (println "\n[subagent-result]" heading "\n")
    (when (seq result-text)
      (println (task-preview result-text 800)))))

(defn- resolve-active-model []
  (let [qf    (:query-fn @state)
        model (when qf (:psi.agent-session/model (qf [:psi.agent-session/model])))
        provider (:provider model)
        id       (:id model)]
    (or (some (fn [m]
                (when (and (= provider (name (:provider m)))
                           (= id (:id m)))
                  m))
              (vals models/all-models))
        (get models/all-models :sonnet-4.6))))

(defn- current-system-prompt []
  (let [qf (:query-fn @state)]
    (when qf
      (:psi.agent-session/system-prompt
       (qf [:psi.agent-session/system-prompt])))))

(defn- create-sub-agent-ctx []
  (let [agent-ctx (agent/create-context)
        tools     (->> tools/all-tools
                       (filter #(contains? subagent-tool-names (:name %)))
                       vec)]
    (agent/create-agent-in! agent-ctx)
    (agent/set-tools-in! agent-ctx tools)
    (agent/set-thinking-level-in! agent-ctx :off)
    (when-let [sp (current-system-prompt)]
      (agent/set-system-prompt-in! agent-ctx sp))
    agent-ctx))

(defn- update-sub! [id f & args]
  (apply swap! state update-in [:agents id] f args)
  (refresh-widgets!))

(defn- handle-progress-event! [id ev]
  (when (= :agent-event (:type ev))
    (case (:event-kind ev)
      :text-delta
      (update-sub! id assoc :last-text (:text ev))

      :tool-executing
      (update-sub! id (fn [sub]
                        (-> sub
                            (update :tool-count (fnil inc 0))
                            (assoc :last-text (str "Running " (:tool-name ev) "…")))))

      :tool-result
      (update-sub! id assoc :last-text (:result-text ev))

      nil)))

(defn- run-subagent! [id prompt]
  (let [sub (get-in @state [:agents id])]
    (when-not sub
      (throw (ex-info "No such subagent" {:id id})))
    (let [agent-ctx (:agent-ctx sub)
          model    (resolve-active-model)]
      (when-not model
        (throw (ex-info "No active model available" {})))
      (update-sub! id
                   (fn [s]
                     (-> s
                         (assoc :status :running
                                :task prompt
                                :last-text ""
                                :tool-count 0
                                :started-at-ms (now-ms))
                         (dissoc :elapsed-ms :error-message))))
      (let [q      (java.util.concurrent.LinkedBlockingQueue.)
            done?  (atom false)
            pump-f (future
                     (while (not @done?)
                       (when-let [ev (.poll q 200 java.util.concurrent.TimeUnit/MILLISECONDS)]
                         (handle-progress-event! id ev))))
            result (try
                     (executor/run-agent-loop!
                      nil
                      agent-ctx
                      model
                      nil
                      {:progress-queue q})
                     (catch Exception e
                       {:role          "assistant"
                        :stop-reason   :error
                        :error-message (ex-message e)
                        :content       [{:type :error :text (ex-message e)}]}))
            text   (->> (:content result)
                        (keep (fn [c]
                                (case (:type c)
                                  :text (:text c)
                                  :error (:text c)
                                  nil)))
                        (str/join "\n"))
            ok?    (not= :error (:stop-reason result))
            total  (- (now-ms) (or (:started-at-ms (get-in @state [:agents id])) (now-ms)))]
        (reset! done? true)
        @pump-f
        (update-sub! id assoc
                     :status (if ok? :done :error)
                     :elapsed-ms total
                     :last-text text
                     :started-at-ms nil
                     :error-message (:error-message result))
        (notify! (str "Subagent #" id " "
                      (if ok? "done" "error")
                      " in " (quot total 1000) "s")
                 (if ok? :info :error))
        (emit-result-message! {:id id
                               :prompt prompt
                               :turn-count (:turn-count (get-in @state [:agents id]))
                               :ok? ok?
                               :elapsed-ms total
                               :result-text text})))))

(defn- spawn-subagent! [task]
  (let [id   (:next-id @state)
        sub  {:id            id
              :status        :running
              :task          task
              :last-text     ""
              :tool-count    0
              :turn-count    1
              :started-at-ms (now-ms)
              :agent-ctx      (create-sub-agent-ctx)}]
    (swap! state (fn [s]
                   (-> s
                       (assoc-in [:agents id] sub)
                       (update :next-id inc))))
    (refresh-widgets!)
    (let [f (future
              (try
                (run-subagent! id task)
                (catch Exception e
                  (update-sub! id assoc
                               :status :error
                               :error-message (ex-message e)
                               :last-text (str "Error: " (ex-message e))
                               :started-at-ms nil)
                  (notify! (str "Subagent #" id " failed: " (ex-message e)) :error))))]
      (swap! state assoc-in [:agents id :runner-future] f))
    id))

(defn- continue-subagent! [id prompt]
  (let [sub (get-in @state [:agents id])]
    (cond
      (nil? sub)
      {:error (str "No subagent #" id " found.")}

      (= :running (:status sub))
      {:error (str "Subagent #" id " is still running.")}

      :else
      (do
        (swap! state update-in [:agents id :turn-count] (fnil inc 1))
        (swap! state assoc-in [:agents id :task] prompt)
        (refresh-widgets!)
        (let [f (future
                  (try
                    (run-subagent! id prompt)
                    (catch Exception e
                      (update-sub! id assoc
                                   :status :error
                                   :error-message (ex-message e)
                                   :last-text (str "Error: " (ex-message e))
                                   :started-at-ms nil)
                      (notify! (str "Subagent #" id " failed: " (ex-message e)) :error))))]
          (swap! state assoc-in [:agents id :runner-future] f))
        {:ok true}))))

(defn- remove-subagent! [id]
  (if-let [sub (get-in @state [:agents id])]
    (do
      (when-let [f (:runner-future sub)]
        (agent/abort-in! (:agent-ctx sub))
        (future-cancel f))
      (clear-widget! id)
      (swap! state update :agents dissoc id)
      {:ok true})
    {:error (str "No subagent #" id " found.")}))

(defn- clear-all-subagents! []
  (let [ids (keys (:agents @state))]
    (doseq [id ids]
      (remove-subagent! id))
    (swap! state assoc :next-id 1)
    (count ids)))

(defn- list-subagents-text []
  (let [subs (vals (:agents @state))]
    (if (empty? subs)
      "No active subagents."
      (str "Subagents:\n"
           (str/join
            "\n"
            (for [s (sort-by :id subs)]
              (str "#" (:id s)
                   " [" (str/upper-case (name (:status s))) "]"
                   " (Turn " (:turn-count s) ") - "
                   (task-preview (:task s) 70))))))))

(defn- parse-subcont-args [args]
  (let [trimmed (str/trim (or args ""))
        idx     (str/index-of trimmed " ")]
    (when (and idx (pos? idx))
      (let [n      (parse-int (subs trimmed 0 idx))
            prompt (str/trim (subs trimmed (inc idx)))]
        (when (and n (seq prompt))
          {:id n :prompt prompt})))))

(defn- ticker-loop! []
  (while true
    (Thread/sleep 1000)
    (let [agents (:agents @state)]
      (when (seq agents)
        (swap! state
               (fn [s]
                 (update s :agents
                         (fn [m]
                           (into {}
                                 (for [[id sub] m]
                                   [id (if (and (= :running (:status sub))
                                                (:started-at-ms sub))
                                         (assoc sub :elapsed-ms (- (now-ms) (:started-at-ms sub)))
                                         sub)]))))))
        (refresh-widgets!)))))

(defn- ensure-ticker! []
  (when-not (:ticker-future @state)
    (swap! state assoc :ticker-future (future (ticker-loop!)))))

(defn init [api]
  (swap! state assoc
         :api      api
         :query-fn (:query api)
         :ui       (:ui api))

  (ensure-ticker!)

  ;; Tools (for main agent orchestration)
  ((:register-tool api)
   {:name        "subagent_create"
    :label       "Subagent Create"
    :description "Spawn a background subagent for a task."
    :parameters  (pr-str {:type       "object"
                          :properties {"task" {:type "string"
                                               :description "Task for the subagent"}}
                          :required   ["task"]})
    :execute     (fn [args]
                   (let [task (str/trim (or (get args "task") ""))]
                     (if (str/blank? task)
                       {:content "Error: task is required." :is-error true}
                       (let [id (spawn-subagent! task)]
                         {:content (str "Subagent #" id " spawned in background.")
                          :is-error false}))))})

  ((:register-tool api)
   {:name        "subagent_continue"
    :label       "Subagent Continue"
    :description "Continue an existing subagent conversation."
    :parameters  (pr-str {:type       "object"
                          :properties {"id" {:type "integer"}
                                       "prompt" {:type "string"}}
                          :required   ["id" "prompt"]})
    :execute     (fn [args]
                   (let [id     (parse-int (get args "id"))
                         prompt (str/trim (or (get args "prompt") ""))]
                     (cond
                       (nil? id)
                       {:content "Error: id is required." :is-error true}

                       (str/blank? prompt)
                       {:content "Error: prompt is required." :is-error true}

                       :else
                       (let [result (continue-subagent! id prompt)]
                         (if-let [e (:error result)]
                           {:content e :is-error true}
                           {:content (str "Subagent #" id " continuing in background.")
                            :is-error false})))))})

  ((:register-tool api)
   {:name        "subagent_remove"
    :label       "Subagent Remove"
    :description "Remove a subagent (aborts it if running)."
    :parameters  (pr-str {:type       "object"
                          :properties {"id" {:type "integer"}}
                          :required   ["id"]})
    :execute     (fn [args]
                   (let [id (parse-int (get args "id"))]
                     (if (nil? id)
                       {:content "Error: id is required." :is-error true}
                       (let [result (remove-subagent! id)]
                         (if-let [e (:error result)]
                           {:content e :is-error true}
                           {:content (str "Subagent #" id " removed.") :is-error false})))))})

  ((:register-tool api)
   {:name        "subagent_list"
    :label       "Subagent List"
    :description "List all subagents and their status."
    :parameters  (pr-str {:type "object" :properties {}})
    :execute     (fn [_args]
                   {:content  (list-subagents-text)
                    :is-error false})})

  ;; Slash commands
  ((:register-command api) "sub"
                           {:description "Spawn a subagent: /sub <task>"
                            :handler     (fn [args]
                                           (let [task (str/trim (or args ""))]
                                             (if (str/blank? task)
                                               (println "Usage: /sub <task>")
                                               (let [id (spawn-subagent! task)]
                                                 (println (str "Spawned Subagent #" id))))))})

  ((:register-command api) "subcont"
                           {:description "Continue a subagent: /subcont <id> <prompt>"
                            :handler     (fn [args]
                                           (if-let [{:keys [id prompt]} (parse-subcont-args args)]
                                             (let [result (continue-subagent! id prompt)]
                                               (if-let [e (:error result)]
                                                 (println e)
                                                 (println (str "Continuing Subagent #" id "..."))))
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
               (swap! state assoc :next-id 1)
               (refresh-widgets!)))

  (notify! "subagent-widget loaded" :info))
