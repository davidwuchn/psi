(ns psi.agent-session.runtime
  "Shared runtime helpers for prompt execution across CLI, TUI, and RPC.

   Provides one path for:
   - input expansion (skills/templates)
   - memory recover/remember hooks
   - user message journaling
   - API key resolution
   - agent loop execution + context usage update"
  (:require
   [clojure.string :as str]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.startup-prompts :as startup-prompts]
   [psi.memory.runtime :as memory-runtime]
   [psi.recursion.core :as recursion]
   [taoensso.timbre :as timbre]))

(defn make-user-message
  "Create a canonical user message map from text and optional image blocks."
  ([text]
   (make-user-message text nil))
  ([text images]
   {:role      "user"
    :content   (cond-> [{:type :text :text text}]
                 (seq images) (into images))
    :timestamp (java.time.Instant/now)}))

(defn- require-session-id!
  [session-id]
  (when-not (and (string? session-id) (not (str/blank? session-id)))
    (throw (ex-info "missing or invalid session-id"
                    {:error-code "request/invalid-session-id"
                     :session-id session-id})))
  session-id)

(defn journal-user-message-in!
  "Append a raw user message to the session journal and return it.
   Use for command traces that should not trigger expansion/memory hooks."
  [ctx session-id text images]
  (require-session-id! session-id)
  (let [user-msg (make-user-message text images)]
    (ss/journal-append-in! ctx session-id (persist/message-entry user-msg))
    user-msg))

(defn- last-assistant-message-in-journal
  [ctx session-id]
  (let [journal (ss/get-state-value-in ctx (ss/state-path :journal session-id))]
    (some (fn [entry]
            (when (and (= :message (:kind entry))
                       (= "assistant" (get-in entry [:data :message :role])))
              (get-in entry [:data :message])))
          (rseq (vec journal)))))

(defn- last-user-message-entry-id-in-journal
  [ctx session-id]
  (let [journal (ss/get-state-value-in ctx (ss/state-path :journal session-id))]
    (some (fn [entry]
            (when (and (= :message (:kind entry))
                       (= "user" (get-in entry [:data :message :role])))
              (:id entry)))
          (rseq (vec journal)))))

(defn expand-input-in
  "Expand user input through skills (/skill:name) or prompt templates (/name).

   Returns:
   {:text <expanded-text>
    :expansion {:kind :skill|:template :name string} | nil}"
  [ctx session-id text]
  (let [sd            (ss/get-session-data-in ctx session-id)
        loaded-skills (:skills sd)
        templates     (:prompt-templates sd)
        commands      (ext/command-names-in (:extension-registry ctx))]
    (if-let [skill-result (skills/invoke-skill loaded-skills text)]
      {:text      (:content skill-result)
       :expansion {:kind :skill :name (:skill-name skill-result)}}
      (if-let [tpl-result (pt/invoke-template templates commands text)]
        {:text      (:content tpl-result)
         :expansion {:kind :template :name (:source-template tpl-result)}}
        {:text      text
         :expansion nil}))))

(defn safe-recover-memory!
  "Trigger memory recovery for the given query text. No-op on failure."
  [query-text]
  (try
    (memory-runtime/recover-for-query! query-text)
    (catch Exception _
      nil)))

(defn safe-remember-session-message!
  "Record a session message to memory. No-op on failure."
  [_ctx session-id msg]
  (try
    (memory-runtime/remember-session-message!
     msg
     {:session-id session-id})
    (catch Exception _
      nil)))

(defn prepare-user-message-in!
  "Expand text, run memory hooks, journal the user message, and return payload.

   Returns:
   {:user-message map
    :expanded-text string
    :expansion {:kind ... :name ...} | nil}"
  [ctx session-id text images]
  (require-session-id! session-id)
  (let [{:keys [text expansion]} (expand-input-in ctx session-id text)
        _        (safe-recover-memory! text)
        user-msg (make-user-message text images)]
    (ss/journal-append-in! ctx session-id (persist/message-entry user-msg))
    (safe-remember-session-message! ctx session-id user-msg)
    {:user-message  user-msg
     :expanded-text text
     :expansion     expansion}))

(defn resolve-api-key-in
  "Resolve API key for ai-model provider from session oauth context, if any.
   `session-id` accepted for call-path symmetry; oauth lookup itself is not session-scoped."
  [ctx _session-id ai-model]
  (when-let [oauth-ctx (:oauth-ctx ctx)]
    (oauth/get-api-key oauth-ctx (:provider ai-model))))

(defn usage->context-tokens
  "Best-effort context token count from an assistant usage map."
  [usage]
  (when (map? usage)
    (let [input  (or (:input-tokens usage) 0)
          output (or (:output-tokens usage) 0)
          read   (or (:cache-read-tokens usage) 0)
          write  (or (:cache-write-tokens usage) 0)
          total  (or (:total-tokens usage)
                     (+ input output read write))]
      (when (and (number? total) (pos? total))
        total))))

(defn update-context-usage-from-result-in!
  "Update session context usage from assistant result usage when available."
  [ctx session-id ai-model result]
  (let [tokens (usage->context-tokens (:usage result))
        window (:context-window ai-model)]
    (when (and (some? tokens) (number? window) (pos? window))
      (dispatch/dispatch! ctx :session/update-context-usage {:session-id session-id :tokens tokens :window window} {:origin :core}))))

(defn- sync->recursion-trigger-type
  [sync]
  (if (true? (get-in sync [:capture :changed?]))
    :graph-changed
    :memory-updated))

(defn- sync->recursion-system-state
  [sync]
  (let [activation    (:activation sync)
        graph-status  (get-in sync [:capability-graph :status])
        query-ready?  (true? (:query-env-built? activation))
        graph-ready?  (contains? #{:stable :expanding} graph-status)
        memory-ready? (= :ready (:memory-status activation))]
    {:query-ready query-ready?
     :graph-ready graph-ready?
     :introspection-ready query-ready?
     :memory-ready memory-ready?}))

(defn- sync->recursion-graph-state
  [sync]
  (let [cg (:capability-graph sync)]
    {:node-count (or (:node-count cg) 0)
     :capability-count (count (or (:capability-ids cg) []))
     :status (or (:status cg) :emerging)}))

(defn- sync->recursion-memory-state
  [sync]
  (let [activation (:activation sync)
        history-sync (:history-sync sync)]
    {:entry-count (or (:memory-entry-count history-sync) 0)
     :status (if (= :ready (:memory-status activation)) :ready :initializing)
     :recovery-count 0}))

(defn- maybe-trigger-recursion-from-git-sync!
  [ctx git-sync]
  (let [recursion-ctx (:recursion-ctx ctx)
        sync          (:sync git-sync)]
    (when (and recursion-ctx (:changed? git-sync) (map? sync))
      (let [trigger-type  (sync->recursion-trigger-type sync)
            trigger-signal {:type trigger-type
                            :reason "git-head-changed"
                            :payload {:source :git-head-change-hook
                                      :head (:head git-sync)
                                      :previous-head (:previous-head git-sync)
                                      :graph-changed? (true? (get-in sync [:capture :changed?]))
                                      :history-imported-count (or (get-in sync [:history-sync :imported-count]) 0)}
                            :timestamp (java.time.Instant/now)}]
        (recursion/orchestrate-manual-trigger-in!
         recursion-ctx
         trigger-signal
         {:system-state (sync->recursion-system-state sync)
          :graph-state (sync->recursion-graph-state sync)
          :memory-state (sync->recursion-memory-state sync)
          :memory-ctx (:memory-ctx ctx)
          :approval-decision :approve
          :approver "git-head-change-hook"
          :approval-notes "auto-approved from git head change hook"})))))

(defn invoke-git-head-sync!
  [opts]
  (let [sync-fn (requiring-resolve 'psi.memory.runtime/maybe-sync-on-git-head-change!)]
    (sync-fn opts)))

(defn- maybe-dispatch-git-head-changed-event!
  [ctx session-id git-sync]
  (when (and (true? (:changed? git-sync))
             (string? (:head git-sync))
             (not (str/blank? (:head git-sync))))
    (ext/dispatch-in (:extension-registry ctx)
                     "git_head_changed"
                     {:cwd (ss/effective-cwd-in ctx session-id)
                      :head (:head git-sync)
                      :previous-head (:previous-head git-sync)
                      :reason "head-changed"
                      :timestamp (java.time.Instant/now)})))

(defn safe-maybe-sync-on-git-head-change!
  [ctx session-id]
  (try
    (let [git-sync (invoke-git-head-sync!
                    {:cwd (ss/effective-cwd-in ctx session-id)
                     :memory-ctx (:memory-ctx ctx)})]
      (try
        (maybe-trigger-recursion-from-git-sync! ctx git-sync)
        (catch Exception _
          nil))
      (try
        (maybe-dispatch-git-head-changed-event! ctx session-id git-sync)
        (catch Exception _
          nil))
      git-sync)
    (catch Exception _
      nil)))

(defn- submit-prompt-turn-in!
  "Dispatch the shared prompt lifecycle for an already-expanded user message."
  [ctx session-id text images {:keys [progress-queue runtime-opts sync-on-git-head-change?]}]
  (when-not (ss/idle-in? ctx session-id)
    (throw (ex-info "Session is not idle" {:phase (ss/sc-phase-in ctx session-id)})))
  (let [user-msg {:role      "user"
                  :content   (cond-> [{:type :text :text text}]
                               images (into images))
                  :timestamp (java.time.Instant/now)}
        submit-r (dispatch/dispatch! ctx :session/prompt-submit
                                     {:session-id session-id :user-msg user-msg}
                                     {:origin :core})
        turn-id  (:turn-id submit-r)
        _        (dispatch/dispatch! ctx :session/prompt {:session-id session-id} {:origin :core})
        result   (dispatch/dispatch! ctx :session/prompt-prepare-request
                                     (cond-> {:session-id session-id
                                              :turn-id    turn-id
                                              :user-msg   user-msg}
                                       progress-queue
                                       (assoc :progress-queue progress-queue)
                                       runtime-opts
                                       (assoc :runtime-opts runtime-opts))
                                     {:origin :core})]
    (when sync-on-git-head-change?
      (safe-maybe-sync-on-git-head-change! ctx session-id))
    result))

(defn run-agent-loop-in!
  "Run executor loop with shared option shaping and usage updates.

   opts:
   - :run-loop-fn   custom runner (default executor/run-agent-loop!)
   - :api-key       optional provider API key
   - :progress-queue optional LinkedBlockingQueue
   - :sync-on-git-head-change? trigger maybe-sync memory hook after loop (default false)"
  ([ctx session-id ai-ctx ai-model user-messages]
   (run-agent-loop-in! ctx session-id ai-ctx ai-model user-messages nil))
  ([ctx session-id ai-ctx ai-model user-messages {:keys [run-loop-fn api-key progress-queue sync-on-git-head-change?]}]
   (let [_      (require-session-id! session-id)
         runner (or run-loop-fn executor/run-agent-loop!)
         opts   (cond-> {}
                  api-key
                  (assoc :api-key api-key)

                  progress-queue
                  (assoc :progress-queue progress-queue))
         result (runner ai-ctx ctx session-id (ss/agent-ctx-in ctx session-id) ai-model user-messages opts)]
     (update-context-usage-from-result-in! ctx session-id ai-model result)
     (when sync-on-git-head-change?
       (safe-maybe-sync-on-git-head-change! ctx session-id))
     result)))

(defn register-extension-run-fn-in!
  "Register a background prompt-lifecycle runner for extension-initiated prompts.

   After this is called, `send-extension-prompt-in!` can always invoke the
   runner in a background thread.

   The runner fn is (fn [text source]) — it waits until the session is idle,
   expands input through the shared path, resolves runtime opts, and routes
   through the dispatch-visible prompt lifecycle.

   Call this once after bootstrap, passing the live ai-ctx and ai-model."
  ([ctx session-id _ai-ctx ai-model]
   (let [run-fn (fn [text _source]
                  (try
                    (loop [attempt 0]
                      (if (ss/idle-in? ctx session-id)
                        (let [{expanded-text :text} (expand-input-in ctx session-id text)
                              _        (safe-recover-memory! expanded-text)
                              api-key  (resolve-api-key-in ctx session-id ai-model)]
                          (dispatch/dispatch! ctx :session/set-model
                                              {:session-id session-id
                                               :model ai-model
                                               :scope :session}
                                              {:origin :core})
                          (submit-prompt-turn-in! ctx session-id expanded-text nil
                                                  {:runtime-opts (cond-> {}
                                                                   api-key (assoc :api-key api-key))
                                                   :sync-on-git-head-change? true}))
                        (when (< attempt 1200) ;; ~5m max wait (250ms backoff)
                          (Thread/sleep 250)
                          (recur (inc attempt)))))
                    (catch Exception e
                      (timbre/warn e "Extension run-fn failed"))))]
     (ext-rt/set-extension-run-fn-in! ctx session-id run-fn))))

(defn run-startup-prompts-in!
  "Run configured startup prompts as visible user turns.

   opts:
   - :ai-ctx
   - :ai-model
   - :run-loop-fn
   - :progress-queue
   - :session-mode (keyword, optional; default :build)
   - :spawn-mode (keyword, optional; default :new-root)
   - :fail-fast? (boolean, optional; default false)

   Returns {:rules [...] :applied [...] :errors [...]} where :applied contains
   per-rule outcomes. On prompt execution failure, execution continues by default
   with an error outcome captured in :applied/:errors."
  [ctx session-id {:keys [ai-ctx ai-model run-loop-fn progress-queue session-mode spawn-mode fail-fast?]
                   :or   {session-mode :build
                          spawn-mode :new-root
                          fail-fast? false}}]
  (let [should-run? (startup-prompts/should-run?
                     {:spawn-mode spawn-mode})
        started-at (java.time.Instant/now)
        _ (dispatch/dispatch! ctx :session/startup-bootstrap-begin {:session-id session-id :started-at started-at} {:origin :core})
        rules (if should-run?
                (startup-prompts/discover-rules
                 {:cwd (ss/effective-cwd-in ctx session-id)
                  :session-mode session-mode})
                [])
        {:keys [applied errors]}
        (loop [remaining rules
               applied []
               errors []]
          (if-let [rule (first remaining)]
            (let [text    (:text rule)
                  outcome (try
                            (if run-loop-fn
                              (let [user-msg   (journal-user-message-in! ctx session-id text nil)
                                    message-id (some-> (ss/get-state-value-in ctx (ss/state-path :journal session-id)) last :id)
                                    api-key    (resolve-api-key-in ctx session-id ai-model)
                                    _          (when message-id
                                                 (dispatch/dispatch! ctx :session/record-startup-message-id {:session-id session-id :message-id message-id} {:origin :core}))]
                                {:status :ok
                                 :user-message user-msg
                                 :assistant-result
                                 (run-agent-loop-in! ctx session-id ai-ctx ai-model [user-msg]
                                                     {:run-loop-fn run-loop-fn
                                                      :api-key api-key
                                                      :progress-queue progress-queue
                                                      :sync-on-git-head-change? true})})
                              (let [api-key (resolve-api-key-in ctx session-id ai-model)
                                    _       (dispatch/dispatch! ctx :session/set-model
                                                                {:session-id session-id
                                                                 :model ai-model
                                                                 :scope :session}
                                                                {:origin :core})
                                    _       (submit-prompt-turn-in! ctx session-id text nil
                                                                    {:progress-queue progress-queue
                                                                     :runtime-opts (cond-> {}
                                                                                     api-key (assoc :api-key api-key))
                                                                     :sync-on-git-head-change? true})
                                    user-id (last-user-message-entry-id-in-journal ctx session-id)
                                    _       (when user-id
                                              (dispatch/dispatch! ctx :session/record-startup-message-id {:session-id session-id :message-id user-id} {:origin :core}))]
                                {:status :ok
                                 :user-message (some->> (persist/all-entries-in ctx session-id)
                                                        reverse
                                                        (filter #(= :message (:kind %)))
                                                        (map #(get-in % [:data :message]))
                                                        (filter #(= "user" (:role %)))
                                                        first)
                                 :assistant-result (last-assistant-message-in-journal ctx session-id)}))
                            (catch Exception e
                              {:status :error
                               :error {:message (ex-message e)
                                       :data (ex-data e)}}))
                  entry    (cond-> {:id (:id rule)
                                    :user-message (:user-message outcome)}
                             (= :ok (:status outcome))
                             (merge {:status :ok
                                     :assistant-result (:assistant-result outcome)})
                             (= :error (:status outcome))
                             (merge {:status :error
                                     :error (:error outcome)}))
                  applied' (conj applied entry)
                  errors'  (if (= :error (:status outcome))
                             (conj errors (:error outcome))
                             errors)]
              (if (and fail-fast? (= :error (:status outcome)))
                {:applied applied' :errors errors'}
                (recur (rest remaining) applied' errors')))
            {:applied applied :errors errors}))
        completed-at (java.time.Instant/now)]
    (dispatch/dispatch! ctx :session/startup-bootstrap-complete {:session-id session-id :startup-prompts (startup-prompts/applied-view rules) :completed-at completed-at} {:origin :core})
    {:rules rules
     :applied applied
     :errors errors}))
