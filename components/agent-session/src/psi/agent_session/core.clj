(ns psi.agent-session.core
  "AgentSession — orchestration layer over the core LLM agent.

   Architecture
   ────────────
   ① Session statechart (statechart.clj) owns phase transitions:
        :idle → :streaming → :idle | :compacting | :retrying
      Guards on agent-end events decide the next phase reactively.

   ② Canonical mutable session/runtime-visible state lives under one root atom
      (:state*). Session data, per-session journal, per-session telemetry,
      per-session turn introspection, background jobs, UI state, recursion state,
      nREPL metadata, and runtime-visible OAuth state are stored as paths within
      that root.

   ③ Agent-core context (:agent-ctx) is owned by the session. The session
      uses add-watch on the agent-core events atom so that every event
      emitted by agent-core is forwarded to the session statechart as
      :session/agent-event (with the event stored as :pending-agent-event
      in working memory for guards to inspect).

   ④ Extension and workflow registries remain runtime handles.

   ⑤ Dispatch is the main mutation/effect boundary for runtime-visible session
      behavior, but actual tool execution still remains an intentional
      executor-owned boundary. Tool lifecycle side effects around execution now
      route through dispatch-owned runtime effects.

   ⑥ Compatibility adapters may expose atom-like views (for UI/background jobs)
      but they are backed by the canonical root state, not separate sources of truth.

   ⑦ EQL resolvers (resolvers.clj) expose the runtime-visible state via
      :psi.agent-session/* and related attrs.

   Nullable pattern
   ────────────────
   `create-context` returns an isolated map with one canonical mutable root.
   All public *-in functions take a context as first arg.
   Global singleton wrappers delegate to the global context.

   Context map keys
   ────────────────
   :state*              — canonical mutable root atom for runtime-visible state
   :sc-env              — statechart environment
   :extension-registry  — ExtensionRegistry record
   :workflow-registry   — WorkflowRegistry record
   :cwd                 — working directory string (used for session dir layout)
   :session-defaults    — resolved session override map (model, thinking-level,
                          worktree-path, ui-type, etc.) used by new-session-in!
                          when no source session exists
   :compaction-fn       — (fn [session-data preparation instructions]) → CompactionResult
   :branch-summary-fn   — (fn [session-data entries instructions]) → BranchSummaryResult
   :config              — merged config map (global defaults + per-session overrides)
   :oauth-ctx           — OAuth runtime context (secure store/provider integration)

   Global query graph integration
   ──────────────────────────────
   Call `register-resolvers!` once at startup to add :psi.agent-session/*
   attributes to the global Pathom graph.  For isolated (test) contexts,
   use `register-resolvers-in!` with a QueryContext from psi.query.core."
  (:require
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.compaction :as compaction]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.dispatch-handlers :as dispatch-handlers]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-chain :as prompt-chain]
   [psi.agent-session.prompt-recording :as prompt-recording]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.prompt-runtime :as prompt-runtime]
   [psi.agent-session.resolvers :as resolvers]
   [psi.agent-session.services :as services]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-lifecycle :as lifecycle]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.agent-session.workflows :as wf]
   [psi.history.resolvers :as history-resolvers]
   [psi.query.core :as query]
   [psi.ui.state :as ui-state])
  (:import
   (java.util.concurrent ExecutorService Executors TimeUnit)))

;;; Forward declarations

(declare execute-compaction-in!)

(defn- create-tool-batch-executor
  ^ExecutorService
  [config]
  (let [n (long (max 1 (or (:tool-batch-max-parallelism config) 4)))]
    (Executors/newFixedThreadPool n)))

(defn- shutdown-tool-batch-executor!
  [^ExecutorService executor]
  (when executor
    (.shutdown executor)
    (.awaitTermination executor 5 TimeUnit/SECONDS)))

;;; Query graph registration

(defn register-resolvers-in!
  "Register all agent-session resolvers into an isolated `qctx` query context.
   Includes history, memory, and recursion resolvers so session-root attrs
   like :git.worktree/current are resolvable.
   Rebuilds the env unless `rebuild?` is false (useful when the caller will
   rebuild after adding further operations).
   Use in tests to avoid touching global state."
  ([qctx] (register-resolvers-in! qctx true))
  ([qctx rebuild?]
   (doseq [r (resolvers/session-resolver-surface)]
     (query/register-resolver-in! qctx r))
   (when rebuild?
     (query/rebuild-env-in! qctx))))

(defn register-mutations-in!
  "Register agent-session mutations into an isolated `qctx` query context.
   `mutations` is the list of mutation vars to register (e.g. mutations/all-mutations).
   Includes history mutations so extension/runtime mutation calls can execute
   git worktree and branch operations through the isolated query env."
  ([qctx mutations] (register-mutations-in! qctx mutations true))
  ([qctx mutations rebuild?]
   (doseq [m (concat mutations history-resolvers/all-mutations)]
     (query/register-mutation-in! qctx m))
   (when rebuild?
     (query/rebuild-env-in! qctx))))

(defn register-resolvers!
  "Register all agent-session resolvers into the global query graph and
   rebuild the environment."
  []
  (doseq [r resolvers/all-resolvers]
    (query/register-resolver! r))
  (query/rebuild-env!))

(defn register-mutations!
  "Register agent-session mutations into the global query graph and
   rebuild the environment.
   `mutations` is the list of mutation vars to register (e.g. mutations/all-mutations)."
  [mutations]
  (doseq [m mutations]
    (query/register-mutation! m))
  (query/rebuild-env!))

;;; Context creation (Nullable pattern)

(defn- resolve-session-defaults
  "Merge caller-supplied session-defaults with resolved cwd and ui-type."
  [session-defaults resolved-cwd ui-type]
  (cond-> (or session-defaults {})
    (not (contains? (or session-defaults {}) :worktree-path))
    (assoc :worktree-path resolved-cwd)
    (some? ui-type) (assoc :ui-type ui-type)))

(defn- initial-root-state
  "Build the initial value for the canonical root state atom."
  [nrepl-runtime-atom recursion-ctx]
  {:agent-session {:sessions {}}
   :runtime {:nrepl (or (some-> nrepl-runtime-atom deref) nil)
             :rpc-trace {:enabled? false
                         :file nil}}
   :background-jobs {:store (bg-jobs/empty-state)}
   :ui {:extension-ui @(ui-state/create-ui-state)}
   :recursion (or (some-> recursion-ctx :state-atom deref) nil)
   :oauth {:authenticated-providers []
           :last-login-provider nil
           :last-login-at nil}})

(defn- callback-fns
  "Build the indirection table of callback/strategy fns for ctx.
   These allow dispatch handlers and extensions to call back into the
   session layer without circular requires."
  [mutations]
  {:apply-root-state-update-fn                    ss/apply-root-state-update-in!
   :read-session-state-fn                         ss/get-state-value-in
   :execute-dispatch-effect-fn                    (fn [ctx effect] (dispatch-effects/execute-effect! ctx effect))
   :dispatch-statechart-event-fn                  dispatch-handlers/dispatch-statechart-event-in!
   :runtime-tool-executor-fn                      tool-plan/default-execute-runtime-tool-in!
   :execute-tool-runtime-fn                       #'tool-plan/execute-tool-runtime-in!
   :build-prepared-request-fn                     #'prompt-request/build-prepared-request
   :execute-prepared-request-fn                   #'prompt-runtime/execute-prepared-request!
   :build-record-response-fn                      #'prompt-recording/build-record-response
   :continue-prompt-chain-fn                      #'prompt-chain/run-prompt-tools!
   :refresh-system-prompt-fn                      (fn
                                                    ([_ctx]
                                                     (throw (ex-info "refresh-system-prompt-fn requires explicit session-id"
                                                                     {:callback :refresh-system-prompt-fn})))
                                                    ([ctx session-id]
                                                     (dispatch/dispatch! ctx :session/refresh-system-prompt {:session-id session-id} {:origin :core})))
   :execute-compaction-fn                         (fn [ctx session-id custom-instructions]
                                                    (execute-compaction-in! ctx session-id custom-instructions))
   :send-extension-message-fn                     #'ext-rt/send-extension-message-in!
   :register-resolvers-fn                         (fn [qctx rebuild?] (register-resolvers-in! qctx rebuild?))
   :register-mutations-fn                         (fn [qctx mutations rebuild?] (register-mutations-in! qctx mutations rebuild?))
   :mark-workflow-jobs-terminal-fn                bg-rt/maybe-mark-workflow-jobs-terminal!
   :emit-background-job-terminal-messages-fn      bg-rt/maybe-emit-background-job-terminal-messages!
   :reconcile-and-emit-background-job-terminals-fn bg-rt/reconcile-and-emit-background-job-terminals-in!
   :journal-append-fn                             ss/journal-append-in!
   :effective-cwd-fn                              (fn
                                                    ([_ctx]
                                                     (throw (ex-info "effective-cwd-fn requires explicit session-id"
                                                                     {:callback :effective-cwd-fn})))
                                                    ([ctx session-id]
                                                     (ss/effective-cwd-in ctx session-id)))
   :daemon-thread-fn                              dispatch-handlers/daemon-thread
   :drop-trailing-overflow-error-fn               dispatch-handlers/drop-trailing-overflow-error!
   :validate-dispatch-result-fn                   dispatch/validate-dispatch-schemas
   :all-mutations                                 mutations})

(defn- create-context*
  "Internal: create a session context without creating a session.
   Returns ctx (not a vector). Used by create-context and tests."
  [{:keys [session-defaults compaction-fn branch-summary-fn agent-initial config cwd persist? event-queue oauth-ctx recursion-ctx nrepl-runtime-atom ui-type mutations]
    :or   {persist? true mutations []}}]
  (let [resolved-cwd      (or cwd (System/getProperty "user.dir"))
        resolved-defaults (resolve-session-defaults session-defaults resolved-cwd ui-type)
        state*            (atom (initial-root-state nrepl-runtime-atom recursion-ctx))
        tool-batch-executor (create-tool-batch-executor (merge session/default-config (or config {})))
        ctx0              (merge
                           {:sc-env                (sc/create-sc-env)
                            :started-at            (java.time.Instant/now)
                            :state*                state*
                            :session-defaults      resolved-defaults
                            :agent-initial         agent-initial
                            :nrepl-runtime-atom    nrepl-runtime-atom
                            :extension-registry    (ext/create-registry)
                            :workflow-registry     (wf/create-registry)
                            :service-registry      (services/create-registry)
                            :post-tool-registry    (post-tool/create-registry)
                            :event-queue           event-queue
                            :cwd                   resolved-cwd
                            :persist?              persist?
                            :oauth-ctx             oauth-ctx
                            :recursion-ctx         recursion-ctx
                            :compaction-fn         (or compaction-fn compaction/stub-compaction-fn)
                            :branch-summary-fn     (or branch-summary-fn compaction/stub-branch-summary-fn)
                            :config                (merge session/default-config (or config {}))
                            :tool-batch-executor   tool-batch-executor
                            ;; Atom holding (fn [text source]) that actually runs the agent loop.
                            ;; Set by the runtime layer (main/RPC) after bootstrap.
                            ;; Extensions use this to submit prompts that trigger real LLM calls.
                            :extension-run-fn-atom (atom nil)
                            ;; Optional runtime-owned hook for projecting background jobs into
                            ;; adapter-neutral UI state after command/runtime transitions.
                            :background-job-ui-refresh-fn (atom nil)}
                           (callback-fns mutations))
        _                 (dispatch-handlers/register-all! ctx0)
        actions-fn        (dispatch-handlers/make-actions-fn ctx0)
        ctx               (assoc ctx0 :session-actions-fn actions-fn)]
    ctx))

(defn create-context
  "Create an isolated session context.

  Does not create a session. Call `new-session-in!` explicitly when a live
  session is required.

  Options (all optional):
    :session-defaults  — overrides merged into session defaults (model,
                          thinking-level, worktree-path, ui-type, etc.)
                          Stored as :session-defaults on ctx.
    :compaction-fn     — (fn [session-data preparation instructions]) → CompactionResult
                          default: stub (no LLM call)
    :branch-summary-fn — (fn [session-data entries instructions]) → BranchSummaryResult
                          default: stub
    :agent-initial     — initial data overrides for the agent-core context
    :config            — config overrides merged over session/default-config
    :cwd               — working directory for session file layout (default: process cwd)
    :persist?          — if false, disable all disk I/O (default: true)
    :oauth-ctx         — optional OAuth context for extension auth helpers
    :nrepl-runtime-atom — optional runtime metadata atom used only to seed canonical nREPL state
    :ui-type           — runtime UI type hint (:console | :tui | :emacs)
    :mutations         — mutation var list for extension query contexts (default: [])"
  ([] (create-context {}))
  ([opts]
   (create-context* opts)))

(defn shutdown-context!
  "Release runtime resources owned by ctx. Safe to call multiple times."
  [ctx]
  (shutdown-tool-batch-executor! (:tool-batch-executor ctx))
  nil)

;;; Session lifecycle — delegates directly to session-lifecycle.clj

(defn new-session-in!
  "Start a fresh session."
  [ctx source-session-id opts]
  (lifecycle/new-session-in! ctx source-session-id opts))

(defn resume-session-in!
  "Load and resume a session from session-path."
  [ctx source-session-id session-path]
  (lifecycle/resume-session-in! ctx source-session-id session-path))

(defn fork-session-in!
  "Fork `parent-session-id` from `entry-id`."
  [ctx parent-session-id entry-id]
  (lifecycle/fork-session-in! ctx parent-session-id entry-id))

(defn ensure-session-loaded-in!
  "Ensure `session-id` is available in this context.

   Behavior:
   - if session-id is nil, no-op
   - if already present in the context registry, returns its data
   - if known only by persisted session-file path, resumes that file
   - otherwise throws ex-info with :error-code request/not-found

   Returns session-data for the available session."
  [ctx _source-session-id session-id]
  (when session-id
    (let [sessions (ss/get-sessions-map-in ctx)
          target   (get sessions session-id)
          path     (get-in target [:data :session-file])]
      (cond
        target
        (ss/get-session-data-in ctx session-id)

        (and path (not (str/blank? path)))
        (resume-session-in! ctx session-id path)

        :else
        (throw (ex-info "session id not found in context session index"
                        {:error-code "request/not-found"
                         :session-id session-id}))))))

;;; Queue text extraction (shared by interrupt + consume)

(defn- extract-text-from-content-blocks
  "Extract :text values from agent-core message content blocks."
  [messages]
  (keep (fn [msg]
          (some (fn [block]
                  (when (= :text (:type block))
                    (:text block)))
                (:content msg)))
        messages))

(defn- merge-text-sources
  "Deduplicate, trim, and join text fragments from multiple sources."
  [& text-colls]
  (->> (apply concat text-colls)
       (keep #(when (string? %) (str/trim %)))
       (remove str/blank?)
       distinct
       (str/join "\n")))

;;; Prompting

(defn prompt-in!
  "Submit `text` (and optional `images`) to the agent for `session-id`.
  Requires the session to be idle.

   Current convergence scaffold routes prompt entry through the existing
   statechart transition plus a chained prepare/execute-and-record dispatch slice:
   session/prompt-submit -> session/prompt -> session/prompt-prepare-request
   -> runtime/prompt-execute-and-record -> session/prompt-record-response.

   The slice is now shared by initial prompt submission and tool-result
   continuation. The active path uses a runtime execute-and-record bridge
   between prepared request projection and recorded turn outcome."
  ([ctx session-id text]
   (prompt-in! ctx session-id text nil))
  ([ctx session-id text images]
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
         _        (dispatch/dispatch! ctx :session/prompt {:session-id session-id} {:origin :core})]
     (dispatch/dispatch! ctx :session/prompt-prepare-request
                         {:session-id session-id
                          :turn-id    turn-id
                          :user-msg   user-msg}
                         {:origin :core}))))

(defn steer-in!
  "Inject a steering message while the agent is streaming for `session-id`.
   State recording and agent-core queue mutation are both dispatch-owned."
  [ctx session-id text]
  (dispatch/dispatch! ctx :session/enqueue-steering-message {:session-id session-id :text text} {:origin :core}))

(defn follow-up-in!
  "Queue a follow-up message for delivery after the current agent run for `session-id`.
   State recording and agent-core queue mutation are both dispatch-owned."
  [ctx session-id text]
  (dispatch/dispatch! ctx :session/enqueue-follow-up-message {:session-id session-id :text text} {:origin :core}))

(defn queue-while-streaming-in!
  "Queue prompt text while streaming for `session-id`.

   Behavior:
   - when interrupt is pending, both steer/queue inputs are coerced to follow-up
   - otherwise steer remains steering and queue remains follow-up

   Returns {:accepted? bool :behavior keyword} where behavior is
   :steer | :queue | :coerced-follow-up."
  [ctx session-id text behavior]
  (let [sd                 (ss/get-session-data-in ctx session-id)
        interrupt-pending? (boolean (:interrupt-pending sd))
        mode               (cond
                             interrupt-pending? :coerced-follow-up
                             (= behavior :steer) :steer
                             :else :queue)]
    (case mode
      :steer
      (do (steer-in! ctx session-id text)
          {:accepted? true :behavior :steer})

      (:queue :coerced-follow-up)
      (do (follow-up-in! ctx session-id text)
          {:accepted? true :behavior (if interrupt-pending? :coerced-follow-up :queue)}))))

(defn request-interrupt-in!
  "Request a deferred interrupt at the next turn boundary for `session-id`.

   Behavior:
   - while streaming, not yet pending: mark :interrupt-pending, drop steering
   - while streaming, already pending: idempotent — preserve original timestamp,
     still drop any newly queued steering
   - while idle: silent no-op

   Returns {:accepted? bool :pending? bool :dropped-steering-text string}."
  [ctx session-id]
  (let [phase (ss/sc-phase-in ctx session-id)
        sd    (ss/get-session-data-in ctx session-id)]
    (if (= :streaming phase)
      (let [already-pending? (boolean (:interrupt-pending sd))
            agent-data       (agent/get-data-in (ss/agent-ctx-in ctx session-id))
            dropped-text     (merge-text-sources
                              (extract-text-from-content-blocks (:steering-queue agent-data))
                              (:steering-messages sd))]
        (dispatch/dispatch! ctx
                            :session/request-interrupt
                            {:session-id       session-id
                             :already-pending? already-pending?
                             :requested-at     (java.time.Instant/now)}
                            {:origin :core})
        ;; Agent-core steering queue cleared via dispatch effect
        {:accepted? (not already-pending?)
         :pending? true
         :dropped-steering-text dropped-text})
      {:accepted? false
       :pending? (boolean (:interrupt-pending sd))
       :dropped-steering-text ""})))

(defn abort-in!
  "Abort the current agent run immediately for `session-id`. Prefer `request-interrupt-in!` for deferred semantics."
  [ctx session-id]
  (dispatch/dispatch! ctx :session/abort {:session-id session-id} {:origin :core}))

(defn consume-queued-input-text-in!
  "Return queued steering/follow-up text (joined by newlines) and clear queues for `session-id`.
   State clearing and agent-core queue clearing are both dispatch-owned.
   This is used by legacy immediate-abort TUI interrupt flows.

   For deferred interrupt semantics, prefer `request-interrupt-in!` which only
   drops steering and preserves follow-ups."
  [ctx session-id]
  (let [agent-data (agent/get-data-in (ss/agent-ctx-in ctx session-id))
        sd         (ss/get-session-data-in ctx session-id)
        merged     (merge-text-sources
                    (extract-text-from-content-blocks
                     (concat (:steering-queue agent-data)
                             (:follow-up-queue agent-data)))
                    (:steering-messages sd)
                    (:follow-up-messages sd))]
    (dispatch/dispatch! ctx :session/clear-queued-messages {:session-id session-id} {:origin :core})
    merged))

;;; Model and thinking level

(defn set-model-in!
  "Set the session model for `session-id`."
  [ctx session-id model]
  (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model model} {:origin :core}))

(defn set-thinking-level-in!
  "Set the thinking level for `session-id`."
  [ctx session-id level]
  (dispatch/dispatch! ctx :session/set-thinking-level {:session-id session-id :level level} {:origin :core}))

(defn cycle-model-in!
  "Cycle to the next available scoped model for `session-id`."
  [ctx session-id direction]
  (let [sd         (ss/get-session-data-in ctx session-id)
        candidates (seq (:scoped-models sd))
        next-m     (when candidates
                     (session/next-model candidates (:model sd) direction))]
    (when next-m
      (set-model-in! ctx session-id next-m))
    (ss/get-session-data-in ctx session-id)))

(defn cycle-thinking-level-in!
  "Cycle to the next thinking level for `session-id`."
  [ctx session-id]
  (let [sd    (ss/get-session-data-in ctx session-id)
        model (:model sd)]
    (when (:reasoning model)
      (let [next-l (session/next-thinking-level (:thinking-level sd) model)]
        (set-thinking-level-in! ctx session-id next-l)))
    (ss/get-session-data-in ctx session-id)))

(defn set-session-name-in!
  "Set the session name for `session-id`."
  [ctx session-id session-name]
  (dispatch/dispatch! ctx :session/set-session-name {:session-id session-id :name session-name} {:origin :core}))

(defn set-auto-compaction-in!
  "Enable or disable auto-compaction for `session-id`."
  [ctx session-id enabled?]
  (dispatch/dispatch! ctx :session/set-auto-compaction {:session-id session-id :enabled? enabled?} {:origin :core}))

(defn set-auto-retry-in!
  "Enable or disable auto-retry for `session-id`."
  [ctx session-id enabled?]
  (dispatch/dispatch! ctx :session/set-auto-retry {:session-id session-id :enabled? enabled?} {:origin :core}))

;;; Compaction

(defn execute-compaction-in!
  "Execute a compaction cycle: prepare → dispatch before-compact → run
  compaction-fn → append entry → rebuild agent messages → dispatch compact.
  Returns the CompactionResult, or nil when cancelled or no-op."
  [ctx session-id custom-instructions]
  (let [sd          (ss/get-session-data-in ctx session-id)
        keep-recent (get-in ctx [:config :auto-compaction-keep-recent-tokens] 20000)
        preparation (compaction/prepare-compaction sd keep-recent)
        reg         (:extension-registry ctx)]
    (when preparation
      (let [{:keys [cancelled? override]}
            (ext/dispatch-in reg "session_before_compact"
                             {:preparation         preparation
                              :branch-entries      (:session-entries sd)
                              :custom-instructions custom-instructions})]
        (when-not cancelled?
          (let [from-extension? (some? override)
                result   (or override
                             ((:compaction-fn ctx) sd preparation custom-instructions))
                entry    (persist/compaction-entry result from-extension?)
                new-msgs (compaction/rebuild-messages-from-entries result sd)]
            (ss/journal-append-in! ctx session-id entry)
            (dispatch/dispatch! ctx :session/compaction-finished
                                {:session-id session-id :messages new-msgs} {:origin :core})
            (ext/dispatch-in reg "session_compact"
                             {:compaction-entry entry
                              :from-extension  from-extension?})
            result))))))

(defn manual-compact-in!
  "User-triggered compaction. Aborts agent if running, then compacts through
   a dispatch-shaped vertical slice:
   compact-start -> manual-compaction-execute -> compact-done.

   The execution step remains an intentional synchronous dispatch handler so the
   caller can still receive the compaction result directly."
  [ctx session-id custom-instructions]
  (when-not (ss/idle-in? ctx session-id)
    (abort-in! ctx session-id))
  (dispatch/dispatch! ctx :session/compact-start {:session-id session-id} {:origin :core})
  (let [result (dispatch/dispatch! ctx
                                   :session/manual-compaction-execute
                                   {:session-id session-id :custom-instructions custom-instructions}
                                   {:origin :core})]
    (dispatch/dispatch! ctx :session/compact-done {:session-id session-id} {:origin :core})
    result))

;;; Replay

(defn replay-dispatch-event-log-in!
  "Replay retained dispatch entries against this session context.

   Accepts either an explicit `entries` collection or, with one arity, replays
   the full retained dispatch event log in order. Effects are suppressed;
   only pure state application is performed.

   Returns the updated root state map."
  ([ctx]
   (replay-dispatch-event-log-in! ctx (dispatch/event-log-entries)))
  ([ctx entries]
   (dispatch/replay-event-log! ctx entries)
   @(:state* ctx)))

;;; Introspection

(defn diagnostics-in
  "Return a diagnostic snapshot map."
  [ctx session-id]
  (let [sd    (ss/get-session-data-in ctx session-id)
        phase (ss/sc-phase-in ctx session-id)]
    {:phase                   phase
     :session-id              session-id
     :is-idle                 (= phase :idle)
     :is-streaming            (= phase :streaming)
     :is-compacting           (= phase :compacting)
     :is-retrying             (= phase :retrying)
     :model                   (:model sd)
     :thinking-level          (:thinking-level sd)
     :pending-messages        (session/pending-message-count sd)
     :retry-attempt           (:retry-attempt sd)
     :auto-retry-enabled      (:auto-retry-enabled sd)
     :auto-compaction-enabled (:auto-compaction-enabled sd)
     :context-fraction        (session/context-fraction-used sd)
     :extension-count         (ext/extension-count-in (:extension-registry ctx))
     :workflow-count          (wf/workflow-count-in (:workflow-registry ctx))
     :workflow-running-count  (wf/running-count-in (:workflow-registry ctx))
     :journal-entries         (count (ss/get-state-value-in ctx (ss/state-path :journal session-id)))
     :agent-diagnostics       (agent/diagnostics-in (ss/agent-ctx-in ctx session-id))}))

;;; EQL query surface

(defn query-in
  "Run EQL `q` against `ctx` through the component's Pathom resolvers.
   Optional explicit `session-id` and `extra-entity` map are merged into the
   Pathom query entity."
  ([ctx q]
   (resolvers/query-in ctx q))
  ([ctx x y]
   (if (or (vector? x) (list? x))
     (resolvers/query-in ctx x y)
     (resolvers/query-in ctx y {:psi.agent-session/session-id x})))
  ([ctx session-id q extra-entity]
   (resolvers/query-in ctx q (assoc (or extra-entity {}) :psi.agent-session/session-id session-id))))



