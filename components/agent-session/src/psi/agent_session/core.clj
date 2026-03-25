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
   [psi.agent-session.resolvers :as resolvers]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-lifecycle :as lifecycle]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.agent-session.workflows :as wf]
   [psi.history.resolvers :as history-resolvers]
   [psi.query.core :as query]
   [psi.ui.state :as ui-state]))

;;; Forward declarations

(declare execute-compaction-in!)

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

(defn create-context
  "Create an isolated session context.

  Options (all optional):
    :initial-session   — overrides merged into the initial AgentSession data map
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
  ([{:keys [initial-session compaction-fn branch-summary-fn agent-initial config cwd persist? event-queue oauth-ctx recursion-ctx nrepl-runtime-atom ui-type mutations]
     :or   {persist? true mutations []}}]
   (let [sc-env            (sc/create-sc-env)
         sc-session-id     (java.util.UUID/randomUUID)
         resolved-cwd      (or cwd (System/getProperty "user.dir"))
         initial-session*  (cond-> (or initial-session {})
                             (not (contains? (or initial-session {}) :worktree-path))
                             (assoc :worktree-path resolved-cwd)
                             (some? ui-type) (assoc :ui-type ui-type))
         agent-ctx         (agent/create-context)
         ext-reg           (ext/create-registry)
         wf-reg            (wf/create-registry)
         merged-config     (merge session/default-config (or config {}))
         initial-sd        (assoc (session/initial-session initial-session*)
                                  :provider-error-replies []
                                  :ephemeral-seed? true)
         initial-sid       (:session-id initial-sd)
         state*            (atom {:agent-session {:sessions {initial-sid {:data          initial-sd
                                                                          :agent-ctx     agent-ctx
                                                                          :sc-session-id sc-session-id
                                                                          :telemetry {:tool-output-stats {:calls []
                                                                                                          :aggregates {:total-context-bytes 0
                                                                                                                       :by-tool {}
                                                                                                                       :limit-hits-by-tool {}}}
                                                                                      :tool-call-attempts []
                                                                                      :tool-lifecycle-events []
                                                                                      :provider-requests []
                                                                                      :provider-replies []}
                                                                          :persistence {:journal []
                                                                                        :flush-state {:flushed? false :session-file nil}}
                                                                          :turn {:ctx nil}}}
                                                  :active-session-id initial-sid}
                                  :runtime {:nrepl (or (some-> nrepl-runtime-atom deref) nil)
                                            :rpc-trace {:enabled? false
                                                        :file nil}}
                                  :background-jobs {:store (bg-jobs/empty-state)}
                                  :ui {:extension-ui @(ui-state/create-ui-state)}
                                  :recursion (or (some-> recursion-ctx :state-atom deref) nil)
                                  :oauth {:authenticated-providers []
                                          :last-login-provider nil
                                          :last-login-at nil}})
         ;; Build ctx without actions-fn so we can close over it
         ctx0              {:sc-env                sc-env
                            :started-at            (java.time.Instant/now)
                            :state*                state*
                            :nrepl-runtime-atom    nrepl-runtime-atom
                            :extension-registry    ext-reg
                            :workflow-registry     wf-reg
                            :event-queue           event-queue
                            :cwd                   resolved-cwd
                            :persist?              persist?
                            :oauth-ctx             oauth-ctx
                            :recursion-ctx         recursion-ctx
                            :compaction-fn         (or compaction-fn compaction/stub-compaction-fn)
                            :branch-summary-fn     (or branch-summary-fn compaction/stub-branch-summary-fn)
                            :config                merged-config
                           ;; Atom holding (fn [text source]) that actually runs the agent loop.
                           ;; Set by the runtime layer (main/RPC) after bootstrap.
                           ;; Extensions use this to submit prompts that trigger real LLM calls.
                            :extension-run-fn-atom (atom nil)
                            :apply-root-state-update-fn ss/apply-root-state-update-in!
                            :read-session-state-fn ss/get-state-value-in
                            :execute-dispatch-effect-fn (fn [ctx effect] (dispatch-effects/execute-effect! ctx effect))
                            :dispatch-statechart-event-fn dispatch-handlers/dispatch-statechart-event-in!
                            :runtime-tool-executor-fn tool-plan/default-execute-runtime-tool-in!
                            :execute-tool-runtime-fn #'tool-plan/execute-tool-runtime-in!
                            :refresh-system-prompt-fn (fn [ctx] (dispatch/dispatch! ctx :session/refresh-system-prompt nil {:origin :core}))
                            :execute-compaction-fn execute-compaction-in!
                            :send-extension-message-fn #'ext-rt/send-extension-message-in!
                            :register-resolvers-fn (fn [qctx rebuild?] (register-resolvers-in! qctx rebuild?))
                            :register-mutations-fn (fn [qctx mutations rebuild?] (register-mutations-in! qctx mutations rebuild?))
                            :mark-workflow-jobs-terminal-fn bg-rt/maybe-mark-workflow-jobs-terminal!
                            :emit-background-job-terminal-messages-fn bg-rt/maybe-emit-background-job-terminal-messages!
                            :reconcile-and-emit-background-job-terminals-fn bg-rt/reconcile-and-emit-background-job-terminals-in!
                            :journal-append-fn ss/journal-append-in!
                            :effective-cwd-fn (fn [ctx] (ss/effective-cwd-in ctx))
                            :daemon-thread-fn dispatch-handlers/daemon-thread
                            :drop-trailing-overflow-error-fn dispatch-handlers/drop-trailing-overflow-error!
                            :validate-dispatch-result-fn dispatch/validate-dispatch-schemas
                            :all-mutations mutations}
         run-tool-call-fn  (fn [ctx {:keys [tool-call parsed-args progress-queue]}]
                             (executor/run-tool-call-through-runtime-effect! ctx tool-call parsed-args progress-queue))
         ctx               (assoc ctx0 :run-tool-call-fn run-tool-call-fn)
         _                 (dispatch-handlers/register-all! ctx)
         actions-fn        (dispatch-handlers/make-actions-fn ctx)]

     ;; NOTE: The events bridge (add-watch on agent-core events-atom) has been
     ;; removed. Message journaling and session statechart events now go through
     ;; the executor and dispatch pipeline directly:
     ;; - Assistant messages: journaled by make-turn-actions in executor.clj
     ;; - Tool results: journaled by :session/tool-agent-record-result handler
     ;; - Agent-end: sent to session statechart by finish-agent-loop! in executor.clj
     ;; - User messages: journaled by :session/prompt-submit handler

     ;; Start the session statechart with working memory containing ctx + actions-fn
     (sc/start-session! sc-env sc-session-id
                        {:ctx        ctx
                         :actions-fn actions-fn
                         :config     merged-config})

     ;; Start agent-core
     (agent/create-agent-in! agent-ctx (or agent-initial {}))

     ctx)))

;;; Session lifecycle — delegates directly to session-lifecycle.clj

(defn new-session-in!
  "Start a fresh session."
  ([ctx]
   (lifecycle/new-session-in! ctx))
  ([ctx opts]
   (lifecycle/new-session-in! ctx opts)))

(defn resume-session-in!
  "Load and resume a session from session-path."
  [ctx session-path]
  (lifecycle/resume-session-in! ctx session-path))

(defn fork-session-in!
  "Fork the session from entry-id."
  [ctx entry-id]
  (lifecycle/fork-session-in! ctx entry-id))

(defn ensure-session-loaded-in!
  "Ensure `session-id` is loaded as the active runtime session in this context.

   Behavior:
   - if session-id is nil, no-op
   - if already current, only updates the context active pointer
   - if known in context session index with a session-file path, resumes that file and
     marks it active
   - otherwise throws ex-info with :error-code request/not-found"
  [ctx session-id]
  (when session-id
    (let [current-id (:session-id (ss/get-session-data-in ctx))]
      (if (= current-id session-id)
        (do (ss/set-context-active-session-in! ctx session-id)
            (ss/get-session-data-in ctx))
        (let [sessions (ss/get-sessions-map-in ctx)
              target   (get sessions session-id)
              path     (get-in target [:data :session-file])]
          (cond
            (nil? target)
            (throw (ex-info "session id not found in context session index"
                            {:error-code "request/not-found"
                             :session-id session-id}))

            (or (nil? path) (str/blank? path))
            (throw (ex-info "session id is not resumable (missing session file)"
                            {:error-code "request/not-found"
                             :session-id session-id}))

            :else
            (do
              (resume-session-in! ctx path)
              (ss/set-context-active-session-in! ctx session-id)
              (ss/get-session-data-in ctx))))))))

;;; Prompting

(defn prompt-in!
  "Submit `text` (and optional `images`) to the agent.
  Requires the session to be idle.

   Current conforming-slice direction:
   prompt entry now reads as a dispatch-shaped sequence:
   session/prompt-submit -> session/prompt -> session/prompt-execute.
   The initial user-message journal append is now dispatch-visible via the
   `:session/prompt-submit` handler/effect path; agent start-loop and
   background-job reconciliation are emitted as dispatch-owned effects rather
   than local orchestration in this entrypoint."
  ([ctx text] (prompt-in! ctx text nil))
  ([ctx text images]
   (when-not (ss/idle-in? ctx)
     (throw (ex-info "Session is not idle" {:phase (ss/sc-phase-in ctx)})))
   (let [user-msg {:role      "user"
                   :content   (cond-> [{:type :text :text text}]
                                images (into images))
                   :timestamp (java.time.Instant/now)}]
     (dispatch/dispatch! ctx :session/prompt-submit {:user-msg user-msg} {:origin :core})
     (dispatch/dispatch! ctx :session/prompt nil {:origin :core})
     (dispatch/dispatch! ctx :session/prompt-execute {:user-msg user-msg} {:origin :core}))))

(defn steer-in!
  "Inject a steering message while the agent is streaming.
   State recording and agent-core queue mutation are both dispatch-owned."
  [ctx text]
  (dispatch/dispatch! ctx :session/enqueue-steering-message {:text text} {:origin :core}))

(defn follow-up-in!
  "Queue a follow-up message for delivery after the current agent run.
   State recording and agent-core queue mutation are both dispatch-owned."
  [ctx text]
  (dispatch/dispatch! ctx :session/enqueue-follow-up-message {:text text} {:origin :core}))

(defn queue-while-streaming-in!
  "Queue prompt text while streaming.

   Behavior:
   - when interrupt is pending, both steer/queue inputs are coerced to follow-up
   - otherwise steer remains steering and queue remains follow-up

   Returns {:accepted? bool :behavior keyword} where behavior is
   :steer | :queue | :coerced-follow-up."
  [ctx text behavior]
  (let [sd                (ss/get-session-data-in ctx)
        interrupt-pending? (boolean (:interrupt-pending sd))
        mode              (cond
                            interrupt-pending? :coerced-follow-up
                            (= behavior :steer) :steer
                            :else :queue)]
    (case mode
      :steer
      (do (steer-in! ctx text)
          {:accepted? true :behavior :steer})

      (:queue :coerced-follow-up)
      (do (follow-up-in! ctx text)
          {:accepted? true :behavior (if interrupt-pending? :coerced-follow-up :queue)}))))

(defn request-interrupt-in!
  "Request a deferred interrupt at the next turn boundary.

   Behavior:
   - while streaming, not yet pending: mark :interrupt-pending, drop steering
   - while streaming, already pending: idempotent — preserve original timestamp,
     still drop any newly queued steering
   - while idle: silent no-op

   Returns {:accepted? bool :pending? bool :dropped-steering-text string}."
  [ctx]
  (let [phase (ss/sc-phase-in ctx)
        sd    (ss/get-session-data-in ctx)]
    (if (= :streaming phase)
      (let [already-pending?     (boolean (:interrupt-pending sd))
            agent-data           (agent/get-data-in (ss/agent-ctx-in ctx))
            queued-steering-msgs (:steering-queue agent-data)
            queued-steering-texts (keep (fn [msg]
                                          (some (fn [block]
                                                  (when (= :text (:type block))
                                                    (:text block)))
                                                (:content msg)))
                                        queued-steering-msgs)
            session-steering-texts (:steering-messages sd)
            dropped-texts         (->> (concat queued-steering-texts session-steering-texts)
                                       (keep #(when (string? %) (str/trim %)))
                                       (remove str/blank?)
                                       distinct)
            dropped-text          (str/join "\n" dropped-texts)]
        (dispatch/dispatch! ctx
                            :session/request-interrupt
                            {:already-pending? already-pending?
                             :requested-at (java.time.Instant/now)}
                            {:origin :core})
        ;; Agent-core steering queue cleared via dispatch effect
        {:accepted? (not already-pending?)
         :pending? true
         :dropped-steering-text dropped-text})
      {:accepted? false
       :pending? (boolean (:interrupt-pending sd))
       :dropped-steering-text ""})))

(defn abort-in!
  "Abort the current agent run immediately. Prefer `request-interrupt-in!` for deferred semantics."
  [ctx]
  (dispatch/dispatch! ctx :session/abort nil {:origin :core}))

(defn consume-queued-input-text-in!
  "Return queued steering/follow-up text (joined by newlines) and clear queues.
   State clearing and agent-core queue clearing are both dispatch-owned.
   This is used by legacy immediate-abort TUI interrupt flows.

   For deferred interrupt semantics, prefer `request-interrupt-in!` which only
   drops steering and preserves follow-ups."
  [ctx]
  (let [agent-data       (agent/get-data-in (ss/agent-ctx-in ctx))
        queued-agent-msgs (concat (:steering-queue agent-data)
                                  (:follow-up-queue agent-data))
        queued-agent-texts (keep (fn [msg]
                                   (some (fn [block]
                                           (when (= :text (:type block))
                                             (:text block)))
                                         (:content msg)))
                                 queued-agent-msgs)
        sd               (ss/get-session-data-in ctx)
        queued-session-texts (concat (:steering-messages sd)
                                     (:follow-up-messages sd))
        all-texts        (->> (concat queued-agent-texts queued-session-texts)
                              (keep #(when (string? %) (str/trim %)))
                              (remove str/blank?)
                              distinct)
        merged           (str/join "\n" all-texts)]
    (dispatch/dispatch! ctx :session/clear-queued-messages nil {:origin :core})
    merged))

;;; Model and thinking level

(defn cycle-model-in!
  "Cycle to the next available scoped model."
  [ctx direction]
  (let [sd         (ss/get-session-data-in ctx)
        candidates (seq (:scoped-models sd))
        next-m     (when candidates
                     (session/next-model candidates (:model sd) direction))]
    (when next-m
      (dispatch/dispatch! ctx :session/set-model {:model next-m} {:origin :core}))
    (ss/get-session-data-in ctx)))

(defn cycle-thinking-level-in!
  "Cycle to the next thinking level."
  [ctx]
  (let [sd    (ss/get-session-data-in ctx)
        model (:model sd)]
    (when (:reasoning model)
      (let [next-l (session/next-thinking-level (:thinking-level sd) model)]
        (dispatch/dispatch! ctx :session/set-thinking-level {:level next-l} {:origin :core})))
    (ss/get-session-data-in ctx)))

;;; Compaction

(defn execute-compaction-in!
  "Execute a compaction cycle: prepare → dispatch before-compact → run
  compaction-fn → append entry → rebuild agent messages → dispatch compact.
  Returns the CompactionResult, or nil when cancelled or no-op."
  ([ctx] (execute-compaction-in! ctx nil))
  ([ctx custom-instructions]
   (let [sd                (ss/get-session-data-in ctx)
         keep-recent       (get-in ctx [:config :auto-compaction-keep-recent-tokens] 20000)
         preparation       (compaction/prepare-compaction sd keep-recent)
         reg               (:extension-registry ctx)]
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
             (ss/journal-append-in! ctx entry)
             (dispatch/dispatch! ctx :session/compaction-finished
                                 {:messages new-msgs} {:origin :core})
             (ext/dispatch-in reg "session_compact"
                              {:compaction-entry entry
                               :from-extension  from-extension?})
             result)))))))
(defn manual-compact-in!
  "User-triggered compaction. Aborts agent if running, then compacts through
   a dispatch-shaped vertical slice:
   compact-start -> manual-compaction-execute -> compact-done.

   The execution step remains an intentional synchronous dispatch handler so the
   caller can still receive the compaction result directly."
  ([ctx] (manual-compact-in! ctx nil))
  ([ctx custom-instructions]
   (when-not (ss/idle-in? ctx)
     (abort-in! ctx))
   (dispatch/dispatch! ctx :session/compact-start nil {:origin :core})
   (let [result (dispatch/dispatch! ctx
                                    :session/manual-compaction-execute
                                    {:custom-instructions custom-instructions}
                                    {:origin :core})]
     (dispatch/dispatch! ctx :session/compact-done nil {:origin :core})
     result)))

;;; Replay

(defn replay-dispatch-event-log-in!
  "Replay retained dispatch entries against this session context.

   Accepts either an explicit `entries` collection or, with one arity, replays
   the full retained dispatch event log in order. Effects are suppressed;
   only pure state application is performed."
  ([ctx]
   (replay-dispatch-event-log-in! ctx (dispatch/event-log-entries)))
  ([ctx entries]
   (dispatch/replay-event-log! ctx entries)
   (ss/get-session-data-in ctx)))

;;; Introspection

(defn diagnostics-in
  "Return a diagnostic snapshot map."
  [ctx]
  (let [sd    (ss/get-session-data-in ctx)
        phase (ss/sc-phase-in ctx)]
    {:phase                   phase
     :session-id              (:session-id sd)
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
     :journal-entries         (count (ss/get-state-value-in ctx (ss/state-path :journal (ss/active-session-id-in ctx))))
     :agent-diagnostics       (agent/diagnostics-in (ss/agent-ctx-in ctx))}))

;;; EQL query surface

(defn query-in
  "Run EQL `q` against `ctx` through the component's Pathom resolvers.
   Optional `extra-entity` map is merged into the Pathom query entity."
  ([ctx q] (resolvers/query-in ctx q))
  ([ctx q extra-entity] (resolvers/query-in ctx q extra-entity)))

;;; REPL convenience wrappers — delegate through service surface
;;; Uses requiring-resolve to avoid core ↔ service circular dependency.

(defn- svc [sym]
  @(requiring-resolve (symbol "psi.agent-session.service" (name sym))))

(defn create-session!
  "Start or reset the global session. Initializes the service."
  ([] (let [ctx (create-context)]
        ((svc 'initialize!) ctx)
        ctx))
  ([opts] (let [ctx (create-context opts)]
            ((svc 'initialize!) ctx)
            ctx)))

(defn get-session-data  [] ((svc 'get-session-data)))
(defn idle?             [] ((svc 'idle?)))

(defn prompt!           [text]   ((svc 'prompt!) text))
(defn steer!            [text]   ((svc 'steer!) text))
(defn follow-up!        [text]   ((svc 'follow-up!) text))
(defn abort!            []       ((svc 'abort!)))

(defn query
  "Run EQL `q` against the active session."
  [q]
  ((svc 'query) q))
