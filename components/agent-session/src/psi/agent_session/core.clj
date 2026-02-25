(ns psi.agent-session.core
  "AgentSession — orchestration layer over the core LLM agent.

   Architecture
   ────────────
   ① Session statechart (statechart.clj) owns phase transitions:
        :idle → :streaming → :idle | :compacting | :retrying
      Guards on agent-end events decide the next phase reactively.

   ② Session data atom (:session-data-atom) holds the full AgentSession
      map (model, thinking level, queues, config, skills, extensions…).

   ③ Agent-core context (:agent-ctx) is owned by the session.  The session
      uses add-watch on the agent-core events atom so that every event
      emitted by agent-core is forwarded to the session statechart as
      :session/agent-event (with the event stored as :pending-agent-event
      in working memory for guards to inspect).

   ④ Extension registry (:extension-registry) holds registered extensions
      and dispatches named events.

   ⑤ Journal atom (:journal-atom) is the append-only session entry log.

   ⑥ EQL resolvers (resolvers.clj) expose all of the above via
      :psi.agent-session/* attributes.

   Nullable pattern
   ────────────────
   `create-context` returns an isolated map with its own atoms.
   All public *-in functions take a context as first arg.
   Global singleton wrappers delegate to the global context.

   Context map keys
   ────────────────
   :sc-env              — statechart environment
   :sc-session-id       — UUID for this session's statechart session
   :session-data-atom   — atom holding AgentSession data map
   :agent-ctx           — agent-core context (owns the LLM loop)
   :extension-registry  — ExtensionRegistry record
   :journal-atom        — atom of [SessionEntry]
   :compaction-fn       — (fn [session-data preparation instructions]) → CompactionResult
   :branch-summary-fn   — (fn [session-data entries instructions]) → BranchSummaryResult
   :config              — merged config map (global defaults + per-session overrides)"
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.compaction :as compaction]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.resolvers :as resolvers]
   [psi.agent-session.session :as session]
   [psi.agent-session.statechart :as sc]))

;; ============================================================
;; Forward declarations
;; ============================================================

(declare execute-compaction-in!)

;; ============================================================
;; Actions dispatcher
;; ============================================================

(defn- make-actions-fn
  "Return the side-effect dispatcher wired into the statechart working memory.
  The statechart calls (actions-fn action-key) from guard/entry/script fns."
  [ctx]
  (fn [action-key]
    (case action-key
      :on-streaming-entered
      (swap! (:session-data-atom ctx) assoc :is-streaming true)

      :on-agent-done
      (swap! (:session-data-atom ctx) assoc
             :is-streaming false
             :retry-attempt 0)

      :on-abort
      (do (agent/abort-in! (:agent-ctx ctx))
          (swap! (:session-data-atom ctx) assoc :is-streaming false))

      :on-auto-compact-triggered
      (do (swap! (:session-data-atom ctx) assoc :is-compacting true)
          (future
            (try
              (execute-compaction-in! ctx nil)
              (catch Exception _e nil)
              (finally
                (sc/send-event! (:sc-env ctx) (:sc-session-id ctx)
                                :session/compact-done)))))

      :on-compacting-entered
      (swap! (:session-data-atom ctx) assoc :is-compacting true)

      :on-compact-done
      (swap! (:session-data-atom ctx) assoc :is-compacting false)

      :on-retry-triggered
      (let [sd       @(:session-data-atom ctx)
            attempt  (:retry-attempt sd)
            base-ms  (get-in ctx [:config :auto-retry-base-delay-ms] 2000)
            max-ms   (get-in ctx [:config :auto-retry-max-delay-ms] 60000)
            delay-ms (session/exponential-backoff-ms attempt base-ms max-ms)]
        (swap! (:session-data-atom ctx) update :retry-attempt inc)
        (future
          (Thread/sleep ^long delay-ms)
          (sc/send-event! (:sc-env ctx) (:sc-session-id ctx) :session/retry-done)))

      :on-retrying-entered
      nil ;; retry-attempt increment handled in :on-retry-triggered

      :on-retry-resume
      (agent/start-loop-in! (:agent-ctx ctx) [])

      ;; unknown action — ignore
      nil)))

;; ============================================================
;; Context creation (Nullable pattern)
;; ============================================================

(defn create-context
  "Create an isolated session context.

  Options (all optional):
    :initial-session   — overrides merged into the initial AgentSession data map
    :compaction-fn     — (fn [session-data preparation instructions]) → CompactionResult
                          default: stub (no LLM call)
    :branch-summary-fn — (fn [session-data entries instructions]) → BranchSummaryResult
                          default: stub
    :agent-initial     — initial data overrides for the agent-core context
    :config            — config overrides merged over session/default-config"
  ([] (create-context {}))
  ([{:keys [initial-session compaction-fn branch-summary-fn agent-initial config]}]
   (let [sc-env            (sc/create-sc-env)
         sc-session-id     (java.util.UUID/randomUUID)
         session-data-atom (atom (session/initial-session (or initial-session {})))
         agent-ctx         (agent/create-context)
         ext-reg           (ext/create-registry)
         journal-atom      (persist/create-journal)
         merged-config     (merge session/default-config (or config {}))
         ;; Build ctx without actions-fn so we can close over it
         ctx               {:sc-env             sc-env
                            :sc-session-id      sc-session-id
                            :session-data-atom  session-data-atom
                            :agent-ctx          agent-ctx
                            :extension-registry ext-reg
                            :journal-atom       journal-atom
                            :compaction-fn      (or compaction-fn compaction/stub-compaction-fn)
                            :branch-summary-fn  (or branch-summary-fn compaction/stub-branch-summary-fn)
                            :config             merged-config}
         actions-fn        (make-actions-fn ctx)]

     ;; Watch agent-core events atom — forward new events to session statechart
     ;; The watch fires whenever events-atom changes value.
     ;; We detect the newest event by comparing old vs new vectors.
     (add-watch (:events-atom agent-ctx) ::session-bridge
                (fn [_key _ref old-events new-events]
                  (let [new-count (count new-events)
                        old-count (count old-events)]
                    (when (> new-count old-count)
                      (doseq [ev (subvec new-events old-count new-count)]
                        (sc/send-event! sc-env sc-session-id
                                        :session/agent-event
                                        {:pending-agent-event ev}))))))

     ;; Start the session statechart with working memory containing the actions-fn
     (sc/start-session! sc-env sc-session-id
                        {:session-data-atom session-data-atom
                         :actions-fn        actions-fn
                         :config            merged-config})

     ;; Start agent-core
     (agent/create-agent-in! agent-ctx (or agent-initial {}))

     ctx)))

(defonce ^:private global-ctx (atom nil))

(defn- ensure-global-ctx! []
  (or @global-ctx
      (let [ctx (create-context)]
        (reset! global-ctx ctx)
        ctx)))

(defn- global-context [] (ensure-global-ctx!))

;; ============================================================
;; Session data read helpers
;; ============================================================

(defn get-session-data-in
  "Return the current AgentSession data map from `ctx`."
  [ctx]
  @(:session-data-atom ctx))

(defn- swap-session! [ctx f & args]
  (apply swap! (:session-data-atom ctx) f args))

(defn sc-phase-in
  "Return the active statechart phase for `ctx`."
  [ctx]
  (sc/sc-phase (:sc-env ctx) (:sc-session-id ctx)))

(defn idle-in?
  "True when the session phase is :idle."
  [ctx]
  (= :idle (sc-phase-in ctx)))

;; ============================================================
;; Session lifecycle
;; ============================================================

(defn new-session-in!
  "Start a fresh session (resets agent and session data).
  Dispatches session_before_switch / session_switch extension events."
  ([ctx] (new-session-in! ctx {}))
  ([ctx _opts]
   (let [reg                  (:extension-registry ctx)
         {:keys [cancelled?]} (ext/dispatch-in reg "session_before_switch" {:reason :new})]
     (when-not cancelled?
       (agent/reset-agent-in! (:agent-ctx ctx))
       (swap-session! ctx assoc
                      :session-id (str (java.util.UUID/randomUUID))
                      :session-name nil
                      :steering-messages []
                      :follow-up-messages []
                      :retry-attempt 0)
       (swap! (:journal-atom ctx) conj
              (persist/thinking-level-entry (:thinking-level (get-session-data-in ctx))))
       (ext/dispatch-in reg "session_switch" {:reason :new})
       (get-session-data-in ctx)))))

(defn resume-session-in!
  "Resume a session from `session-path`.
  Disk I/O deferred — for now records the path."
  [ctx session-path]
  (let [reg                  (:extension-registry ctx)
        {:keys [cancelled?]} (ext/dispatch-in reg "session_before_switch" {:reason :resume})]
    (when-not cancelled?
      (swap-session! ctx assoc :session-file session-path)
      (ext/dispatch-in reg "session_switch" {:reason :resume})
      (get-session-data-in ctx))))

(defn fork-session-in!
  "Fork the session from `entry-id`, creating a new session branch."
  [ctx entry-id]
  (let [reg     (:extension-registry ctx)
        journal (:journal-atom ctx)]
    (ext/dispatch-in reg "session_before_fork" {:entry-id entry-id})
    (let [messages (persist/messages-up-to journal entry-id)]
      (swap-session! ctx assoc
                     :session-id (str (java.util.UUID/randomUUID))
                     :session-file nil)
      (agent/replace-messages-in! (:agent-ctx ctx) (vec messages))
      (ext/dispatch-in reg "session_fork" {})
      (get-session-data-in ctx))))

;; ============================================================
;; Prompting
;; ============================================================

(defn prompt-in!
  "Submit `text` (and optional `images`) to the agent.
  Requires the session to be idle."
  ([ctx text] (prompt-in! ctx text nil))
  ([ctx text images]
   (when-not (idle-in? ctx)
     (throw (ex-info "Session is not idle" {:phase (sc-phase-in ctx)})))
   (let [user-msg {:role      "user"
                   :content   (cond-> [{:type :text :text text}]
                                images (into images))
                   :timestamp (java.time.Instant/now)}]
     (swap! (:journal-atom ctx) conj (persist/message-entry user-msg))
     (sc/send-event! (:sc-env ctx) (:sc-session-id ctx) :session/prompt)
     (agent/start-loop-in! (:agent-ctx ctx) [user-msg]))))

(defn steer-in!
  "Inject a steering message while the agent is streaming."
  [ctx text]
  (swap-session! ctx update :steering-messages conj text)
  (agent/queue-steering-in! (:agent-ctx ctx)
                             {:role      "user"
                              :content   [{:type :text :text text}]
                              :timestamp (java.time.Instant/now)}))

(defn follow-up-in!
  "Queue a follow-up message for delivery after the current agent run."
  [ctx text]
  (swap-session! ctx update :follow-up-messages conj text)
  (agent/queue-follow-up-in! (:agent-ctx ctx)
                              {:role      "user"
                               :content   [{:type :text :text text}]
                               :timestamp (java.time.Instant/now)}))

(defn abort-in!
  "Abort the current agent run."
  [ctx]
  (sc/send-event! (:sc-env ctx) (:sc-session-id ctx) :session/abort))

;; ============================================================
;; Model and thinking level
;; ============================================================

(defn set-model-in!
  "Set the model for this session."
  [ctx model]
  (let [clamped-level (session/clamp-thinking-level
                       (:thinking-level (get-session-data-in ctx))
                       model)]
    (swap-session! ctx assoc :model model :thinking-level clamped-level)
    (agent/set-model-in! (:agent-ctx ctx) model)
    (swap! (:journal-atom ctx) conj (persist/model-entry (:provider model) (:id model)))
    (ext/dispatch-in (:extension-registry ctx) "model_select" {:model model :source :set})
    (get-session-data-in ctx)))

(defn cycle-model-in!
  "Cycle to the next available scoped model."
  [ctx direction]
  (let [sd         (get-session-data-in ctx)
        candidates (seq (:scoped-models sd))
        next-m     (when candidates
                     (session/next-model candidates (:model sd) direction))]
    (when next-m
      (set-model-in! ctx next-m))
    (get-session-data-in ctx)))

(defn set-thinking-level-in!
  "Set the thinking level, clamping to what the model supports."
  [ctx level]
  (let [sd      (get-session-data-in ctx)
        model   (:model sd)
        clamped (if model (session/clamp-thinking-level level model) level)]
    (swap-session! ctx assoc :thinking-level clamped)
    (agent/set-thinking-level-in! (:agent-ctx ctx) clamped)
    (swap! (:journal-atom ctx) conj (persist/thinking-level-entry clamped))
    (get-session-data-in ctx)))

(defn cycle-thinking-level-in!
  "Cycle to the next thinking level."
  [ctx]
  (let [sd    (get-session-data-in ctx)
        model (:model sd)]
    (when (:reasoning model)
      (let [next-l (session/next-thinking-level (:thinking-level sd) model)]
        (set-thinking-level-in! ctx next-l)))
    (get-session-data-in ctx)))

;; ============================================================
;; Tool management
;; ============================================================

(defn set-active-tools-in!
  "Replace the agent's active tool set."
  [ctx tool-maps]
  (agent/set-tools-in! (:agent-ctx ctx) tool-maps)
  (get-session-data-in ctx))

;; ============================================================
;; Compaction
;; ============================================================

(defn execute-compaction-in!
  "Execute a compaction cycle: prepare → dispatch before-compact → run
  compaction-fn → append entry → rebuild agent messages → dispatch compact.
  Returns the CompactionResult, or nil if cancelled."
  ([ctx] (execute-compaction-in! ctx nil))
  ([ctx custom-instructions]
   (let [sd                        (get-session-data-in ctx)
         preparation               (compaction/prepare-compaction sd)
         reg                       (:extension-registry ctx)
         {:keys [cancelled? override]}
         (ext/dispatch-in reg "session_before_compact"
                          {:preparation         preparation
                           :custom-instructions custom-instructions})]
     (when-not cancelled?
       (let [result   (or override
                          ((:compaction-fn ctx) sd preparation custom-instructions))
             entry    (persist/compaction-entry result false)
             new-msgs (compaction/rebuild-messages-from-entries result sd)]
         (swap! (:journal-atom ctx) conj entry)
         (agent/replace-messages-in! (:agent-ctx ctx) new-msgs)
         (swap-session! ctx assoc :is-compacting false :context-tokens nil)
         (ext/dispatch-in reg "session_compact" {})
         result)))))

(defn manual-compact-in!
  "User-triggered compaction. Aborts agent if running, then compacts synchronously."
  ([ctx] (manual-compact-in! ctx nil))
  ([ctx custom-instructions]
   (when-not (idle-in? ctx)
     (abort-in! ctx))
   (sc/send-event! (:sc-env ctx) (:sc-session-id ctx) :session/compact-start)
   (let [result (execute-compaction-in! ctx custom-instructions)]
     (sc/send-event! (:sc-env ctx) (:sc-session-id ctx) :session/compact-done)
     result)))

(defn set-auto-compaction-in!
  "Enable or disable auto-compaction."
  [ctx enabled?]
  (swap-session! ctx assoc :auto-compaction-enabled (boolean enabled?))
  (get-session-data-in ctx))

;; ============================================================
;; Auto-retry config
;; ============================================================

(defn set-auto-retry-in!
  "Enable or disable auto-retry."
  [ctx enabled?]
  (swap-session! ctx assoc :auto-retry-enabled (boolean enabled?))
  (get-session-data-in ctx))

;; ============================================================
;; Extension management
;; ============================================================

(defn register-extension-in!
  "Register an extension by path into this session's registry."
  [ctx path]
  (ext/register-extension-in! (:extension-registry ctx) path)
  ctx)

(defn register-handler-in!
  "Register an event handler for `event-name` on extension at `ext-path`."
  [ctx ext-path event-name handler-fn]
  (ext/register-handler-in! (:extension-registry ctx) ext-path event-name handler-fn)
  ctx)

(defn dispatch-extension-event-in!
  "Dispatch a named extension event. Returns the dispatch result map."
  [ctx event-name event-data]
  (ext/dispatch-in (:extension-registry ctx) event-name event-data))

;; ============================================================
;; Session naming
;; ============================================================

(defn set-session-name-in!
  "Set the human-readable session name."
  [ctx name]
  (swap-session! ctx assoc :session-name name)
  (swap! (:journal-atom ctx) conj (persist/session-info-entry name))
  (get-session-data-in ctx))

;; ============================================================
;; Context token tracking
;; ============================================================

(defn update-context-usage-in!
  "Update the session's tracked context token count and window size."
  [ctx tokens window]
  (swap-session! ctx assoc :context-tokens tokens :context-window window)
  (get-session-data-in ctx))

;; ============================================================
;; Introspection
;; ============================================================

(defn diagnostics-in
  "Return a diagnostic snapshot map."
  [ctx]
  (let [sd    (get-session-data-in ctx)
        phase (sc-phase-in ctx)]
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
     :journal-entries         (count @(:journal-atom ctx))
     :agent-diagnostics       (agent/diagnostics-in (:agent-ctx ctx))}))

;; ============================================================
;; EQL query surface
;; ============================================================

(defn query-in
  "Run EQL `q` against `ctx` through the component's Pathom resolvers."
  [ctx q]
  (resolvers/query-in ctx q))

;; ============================================================
;; Global (singleton) wrappers
;; ============================================================

(defn create-session!
  "Start or reset the global session."
  ([]    (ensure-global-ctx!))
  ([opts] (reset! global-ctx (create-context opts)) @global-ctx))

(defn get-session-data  [] (get-session-data-in  (global-context)))
(defn sc-phase          [] (sc-phase-in           (global-context)))
(defn idle?             [] (idle-in?              (global-context)))
(defn diagnostics       [] (diagnostics-in        (global-context)))

(defn prompt!           [text]   (prompt-in!              (global-context) text))
(defn steer!            [text]   (steer-in!               (global-context) text))
(defn follow-up!        [text]   (follow-up-in!           (global-context) text))
(defn abort!            []       (abort-in!               (global-context)))
(defn new-session!      []       (new-session-in!         (global-context)))
(defn set-model!        [m]      (set-model-in!           (global-context) m))
(defn set-thinking!     [l]      (set-thinking-level-in!  (global-context) l))
(defn cycle-thinking!   []       (cycle-thinking-level-in!(global-context)))
(defn set-name!         [n]      (set-session-name-in!    (global-context) n))
(defn compact!          []       (manual-compact-in!      (global-context)))
(defn compact-with!     [instr]  (manual-compact-in!      (global-context) instr))
(defn set-auto-compact! [e]      (set-auto-compaction-in! (global-context) e))
(defn set-auto-retry!   [e]      (set-auto-retry-in!      (global-context) e))

(defn query
  "Run EQL `q` against the global session context."
  [q]
  (query-in (global-context) q))
