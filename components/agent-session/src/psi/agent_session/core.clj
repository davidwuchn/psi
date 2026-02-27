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
   :flush-state-atom    — atom {:flushed? bool :session-file File?}
   :cwd                 — working directory string (used for session dir layout)
   :compaction-fn       — (fn [session-data preparation instructions]) → CompactionResult
   :branch-summary-fn   — (fn [session-data entries instructions]) → BranchSummaryResult
   :config              — merged config map (global defaults + per-session overrides)

   Global query graph integration
   ──────────────────────────────
   Call `register-resolvers!` once at startup to add :psi.agent-session/*
   attributes to the global Pathom graph.  For isolated (test) contexts,
   use `register-resolvers-in!` with a QueryContext from psi.query.core."
  (:require
   [clojure.java.io :as io]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-core.core :as agent]
   [psi.agent-session.compaction :as compaction]
   [psi.agent-session.extensions :as ext]
   [psi.tui.extension-ui :as ext-ui]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.resolvers :as resolvers]
   [psi.agent-session.session :as session]
   [psi.agent-session.statechart :as sc]
   [psi.query.core :as query]))

;; ============================================================
;; Forward declarations
;; ============================================================

(declare execute-compaction-in!)
(declare set-session-name-in!)

;; ============================================================
;; Actions dispatcher
;; ============================================================

(defn- last-assistant-message-from-event
  [event]
  (let [m (last (:messages event))]
    (when (= "assistant" (:role m))
      m)))

(defn- overflow-error-assistant?
  [msg]
  (let [stop-reason (or (:stop-reason msg)
                        (:stopReason msg))]
    (and (map? msg)
         (or (= :error stop-reason)
             (= "error" stop-reason))
         (session/context-overflow-error? (:error-message msg)))))

(defn- threshold-auto-compact?
  [session-data config]
  (let [tokens   (:context-tokens session-data)
        window   (:context-window session-data)
        reserve  (long (or (:auto-compaction-reserve-tokens config) 16384))
        cutoff   (when (and (number? window) (pos? window))
                   (max 0 (- window reserve)))]
    (and (number? tokens)
         (number? cutoff)
         (> tokens cutoff))))

(defn- auto-compaction-reason
  "Return :overflow, :threshold, or nil from statechart working-memory `data`."
  [data]
  (let [sd     @(:session-data-atom data)
        config (:config data)
        event  (:pending-agent-event data)
        last-m (last-assistant-message-from-event event)]
    (cond
      (and (:auto-compaction-enabled sd)
           (overflow-error-assistant? last-m))
      :overflow

      (and (:auto-compaction-enabled sd)
           (threshold-auto-compact? sd config)
           (let [stop-reason (or (:stop-reason last-m)
                                 (:stopReason last-m))]
             (not (or (= :error stop-reason)
                      (= "error" stop-reason)))))
      :threshold

      :else
      nil)))

(defn- drop-trailing-overflow-error!
  [ctx]
  (let [messages (:messages (agent/get-data-in (:agent-ctx ctx)))
        last-msg (last messages)]
    (when (overflow-error-assistant? last-msg)
      (agent/replace-messages-in! (:agent-ctx ctx) (vec (butlast messages))))))

(defn- make-actions-fn
  "Return the side-effect dispatcher wired into the statechart working memory.
  The statechart calls (actions-fn action-key data) from guard/entry/script fns."
  [ctx]
  (letfn [(dispatch-action [action-key data]
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
              (let [reason      (or (some-> data auto-compaction-reason) :threshold)
                    will-retry? (= :overflow reason)
                    continue?   (atom false)
                    reg         (:extension-registry ctx)]
                (swap! (:session-data-atom ctx) assoc :is-compacting true)
                (ext/dispatch-in reg "auto_compaction_start" {:reason reason})
                (when will-retry?
                  (drop-trailing-overflow-error! ctx))
                (future
                  (try
                    (let [result (execute-compaction-in! ctx nil)]
                      (if result
                        (do
                          (ext/dispatch-in reg "auto_compaction_end"
                                           {:result     result
                                            :aborted    false
                                            :will-retry will-retry?})
                          (when (or will-retry?
                                    (agent/has-queued-messages-in? (:agent-ctx ctx)))
                            (reset! continue? true)))
                        (ext/dispatch-in reg "auto_compaction_end"
                                         {:result     nil
                                          :aborted    true
                                          :will-retry false})))
                    (catch Exception e
                      (ext/dispatch-in reg "auto_compaction_end"
                                       {:result        nil
                                        :aborted       false
                                        :will-retry    false
                                        :error-message (ex-message e)}))
                    (finally
                      (sc/send-event! (:sc-env ctx) (:sc-session-id ctx)
                                      :session/compact-done)
                      (when @continue?
                        (sc/send-event! (:sc-env ctx) (:sc-session-id ctx) :session/prompt)
                        (agent/start-loop-in! (:agent-ctx ctx) [])))))
                nil)

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
              nil))]
    (fn
      ([action-key] (dispatch-action action-key nil))
      ([action-key data] (dispatch-action action-key data)))))
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
    :config            — config overrides merged over session/default-config
    :cwd               — working directory for session file layout (default: process cwd)
    :persist?          — if false, disable all disk I/O (default: true)"
  ([] (create-context {}))
  ([{:keys [initial-session compaction-fn branch-summary-fn agent-initial config cwd persist?]
     :or   {persist? true}}]
   (let [sc-env            (sc/create-sc-env)
         sc-session-id     (java.util.UUID/randomUUID)
         resolved-cwd      (or cwd (System/getProperty "user.dir"))
         session-data-atom (atom (session/initial-session (or initial-session {})))
         agent-ctx         (agent/create-context)
         ext-reg           (ext/create-registry)
         journal-atom      (persist/create-journal)
         flush-state-atom  (persist/create-flush-state)
         ui-state-atom     (ext-ui/create-ui-state)
         merged-config     (merge session/default-config (or config {}))
         ;; Build ctx without actions-fn so we can close over it
         ctx               {:sc-env             sc-env
                            :sc-session-id      sc-session-id
                            :session-data-atom  session-data-atom
                            :agent-ctx          agent-ctx
                            :extension-registry ext-reg
                            :journal-atom       journal-atom
                            :flush-state-atom   flush-state-atom
                            :turn-ctx-atom      (atom nil)
                            :ui-state-atom      ui-state-atom
                            :cwd                resolved-cwd
                            :persist?           persist?
                            :compaction-fn      (or compaction-fn compaction/stub-compaction-fn)
                            :branch-summary-fn  (or branch-summary-fn compaction/stub-branch-summary-fn)
                            :config             merged-config}
         actions-fn        (make-actions-fn ctx)]

     ;; Watch agent-core events atom — forward new events to session statechart
     ;; and persist message-end events to the journal.
     ;; The watch fires whenever events-atom changes value.
     ;; We detect the newest event by comparing old vs new vectors.
     (add-watch (:events-atom agent-ctx) ::session-bridge
                (fn [_key _ref old-events new-events]
                  (let [new-count (count new-events)
                        old-count (count old-events)]
                    (when (> new-count old-count)
                      (doseq [ev (subvec new-events old-count new-count)]
                        ;; Persist LLM-generated messages on message-end.
                        ;; User messages are already journaled in prompt-in!,
                        ;; so only journal assistant, toolResult, and custom here.
                        (when (= :message-end (:type ev))
                          (let [msg  (:message ev)
                                role (:role msg)]
                            (when (contains? #{"assistant" "toolResult" "custom"} role)
                              (persist/append-entry! journal-atom
                                                     (persist/message-entry msg))
                              (when persist?
                                (persist/persist-entry!
                                 journal-atom
                                 flush-state-atom
                                 (:session-id @session-data-atom)
                                 resolved-cwd
                                 (:session-file @session-data-atom))))))
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

;; ============================================================
;; Journal append helper
;; ============================================================

(defn- journal-append!
  "Append `entry` to the journal and conditionally persist to disk."
  [ctx entry]
  (persist/append-entry! (:journal-atom ctx) entry)
  (when (:persist? ctx)
    (let [sd (get-session-data-in ctx)]
      (persist/persist-entry!
       (:journal-atom ctx)
       (:flush-state-atom ctx)
       (:session-id sd)
       (:cwd ctx)
       (:session-file sd)))))

(defn journal-append-in!
  "Public: append `entry` to the journal and persist.
   Use `persist/message-entry` et al. to build entries."
  [ctx entry]
  (journal-append! ctx entry))

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
       (let [new-session-id (str (java.util.UUID/randomUUID))]
         (swap-session! ctx assoc
                        :session-id        new-session-id
                        :session-file      nil
                        :session-name      nil
                        :steering-messages []
                        :follow-up-messages []
                        :retry-attempt     0)
         ;; Reset journal and flush state for the new session
         (reset! (:journal-atom ctx) [])
         (when (:persist? ctx)
           (let [session-dir (persist/session-dir-for (:cwd ctx))
                 file        (persist/new-session-file-path session-dir new-session-id)]
             (swap-session! ctx assoc :session-file (str file))
             (reset! (:flush-state-atom ctx) {:flushed? false :session-file file})))
         (journal-append! ctx
                          (persist/thinking-level-entry
                           (:thinking-level (get-session-data-in ctx))))
         (when-let [model (:model (get-session-data-in ctx))]
           (journal-append! ctx (persist/model-entry (:provider model) (:id model)))))
       (ext/dispatch-in reg "session_switch" {:reason :new})
       (get-session-data-in ctx)))))

(defn resume-session-in!
  "Load and resume a session from `session-path`.
  Parses the NDEDN file, migrates if needed, rebuilds the agent message list,
  and restores model/thinking-level from the journal."
  [ctx session-path]
  (let [reg                  (:extension-registry ctx)
        {:keys [cancelled?]} (ext/dispatch-in reg "session_before_switch" {:reason :resume})]
    (when-not cancelled?
      (let [loaded (persist/load-session-file session-path)]
        (if-not loaded
          ;; File missing or invalid — treat as new session at that path
          (do (swap-session! ctx assoc :session-file session-path)
              (reset! (:journal-atom ctx) [])
              (reset! (:flush-state-atom ctx)
                      {:flushed? false
                       :session-file (io/file session-path)}))
          (let [{:keys [header entries]} loaded
                session-id             (:id header)
                current-sd             (get-session-data-in ctx)
                current-model          (:model current-sd)
                current-thinking-level (:thinking-level current-sd)
                ;; Restore model/thinking from last model/thinking entries.
                model-entry            (last (filter #(= :model (:kind %)) entries))
                thinking-entry         (last (filter #(= :thinking-level (:kind %)) entries))
                model-from-entry       (let [provider (get-in model-entry [:data :provider])
                                             model-id (get-in model-entry [:data :model-id])]
                                         (when (and provider model-id)
                                           {:provider provider
                                            :id       model-id}))
                ;; Some sessions (especially older ones) may have no model entry.
                ;; Fall back to the currently configured model in that case.
                model                  (or model-from-entry current-model)
                thinking-level         (or (get-in thinking-entry [:data :thinking-level])
                                           current-thinking-level
                                           :off)
                ;; Rebuild agent messages from journal (handles compaction)
                messages               (compaction/rebuild-messages-from-journal-entries (vec entries))]
            (reset! (:journal-atom ctx) (vec entries))
            (reset! (:flush-state-atom ctx)
                    {:flushed? true
                     :session-file (io/file session-path)})
            (swap-session! ctx assoc
                           :session-id     session-id
                           :session-file   session-path
                           :session-name   (some #(when (= :session-info (:kind %))
                                                    (get-in % [:data :name]))
                                                 (rseq (vec entries)))
                           :model          model
                           :thinking-level thinking-level)
            (agent/reset-agent-in! (:agent-ctx ctx))
            (when model
              (agent/set-model-in! (:agent-ctx ctx) model))
            (agent/set-thinking-level-in! (:agent-ctx ctx) thinking-level)
            (agent/replace-messages-in! (:agent-ctx ctx) (vec messages)))))
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
     (journal-append! ctx (persist/message-entry user-msg))
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
    (journal-append! ctx (persist/model-entry (:provider model) (:id model)))
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
    (journal-append! ctx (persist/thinking-level-entry clamped))
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
  Returns the CompactionResult, or nil when cancelled or no-op." 
  ([ctx] (execute-compaction-in! ctx nil))
  ([ctx custom-instructions]
   (let [sd                (get-session-data-in ctx)
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
             (journal-append! ctx entry)
             (agent/replace-messages-in! (:agent-ctx ctx) new-msgs)
             (swap-session! ctx assoc :is-compacting false :context-tokens nil)
             (ext/dispatch-in reg "session_compact"
                              {:compaction-entry entry
                               :from-extension  from-extension?})
             result)))))))
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

(defn- make-extension-action-fns
  "Build the action-fns map that extension APIs delegate to.
   These close over the session context so extensions can interact with
   the session without holding a reference to the context directly."
  [ctx]
  {:send-message-fn     (fn [_msg _opts] nil) ;; stub — wired by run mode
   :send-user-message-fn (fn [_content _opts] nil)
   :append-entry-fn     (fn [custom-type data]
                          (journal-append! ctx
                                           (persist/custom-message-entry
                                            custom-type (str data) nil false)))
   :set-session-name-fn (fn [name] (set-session-name-in! ctx name))
   :get-session-name-fn (fn [] (:session-name (get-session-data-in ctx)))
   :set-label-fn        (fn [entry-id label]
                          (journal-append! ctx (persist/label-entry entry-id label)))
   :get-active-tools-fn (fn [] (mapv :name (:tools (agent/get-data-in (:agent-ctx ctx)))))
   :set-active-tools-fn (fn [tool-names]
                          (let [current-tools (:tools (agent/get-data-in (:agent-ctx ctx)))
                                name-set      (set tool-names)]
                            (agent/set-tools-in! (:agent-ctx ctx)
                                                 (filterv #(name-set (:name %))
                                                          current-tools))))
   :get-model-fn        (fn [] (:model (get-session-data-in ctx)))
   :set-model-fn        (fn [model] (set-model-in! ctx model))
   :is-idle-fn          (fn [] (idle-in? ctx))
   :abort-fn            (fn [] (abort-in! ctx))
   :compact-fn          (fn [_opts] (manual-compact-in! ctx))
   :get-system-prompt-fn (fn [] (:system-prompt (get-session-data-in ctx)))
   :ui-state-atom       (:ui-state-atom ctx)})

(defn load-extensions-in!
  "Discover and load all extensions into this session's registry.
   `configured-paths` are explicit CLI paths.
   Returns {:loaded [paths] :errors [{:path :error}]}."
  ([ctx] (load-extensions-in! ctx []))
  ([ctx configured-paths]
   (load-extensions-in! ctx configured-paths nil))
  ([ctx configured-paths cwd]
   (let [action-fns (make-extension-action-fns ctx)]
     (ext/load-extensions-in! (:extension-registry ctx) action-fns
                              configured-paths cwd))))

(defn reload-extensions-in!
  "Unregister all extensions and re-discover/load them."
  ([ctx] (reload-extensions-in! ctx []))
  ([ctx configured-paths]
   (reload-extensions-in! ctx configured-paths nil))
  ([ctx configured-paths cwd]
   (let [action-fns (make-extension-action-fns ctx)]
     (ext/reload-extensions-in! (:extension-registry ctx) action-fns
                                configured-paths cwd))))

(defn extension-summary-in
  "Return introspection summary of the extension registry."
  [ctx]
  (ext/summary-in (:extension-registry ctx)))

(defn extension-details-in
  "Return per-extension detail maps."
  [ctx]
  (ext/extension-details-in (:extension-registry ctx)))

;; ============================================================
;; Session naming
;; ============================================================

(defn set-session-name-in!
  "Set the human-readable session name."
  [ctx name]
  (swap-session! ctx assoc :session-name name)
  (journal-append! ctx (persist/session-info-entry name))
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
;; Query graph registration (resolvers + mutations)
;; ============================================================

(defn add-prompt-template-in!
  "Add `template` to session data if its :name is not already present.
   Returns {:added? bool :count int}."
  [ctx template]
  (let [existing? (some #(= (:name %) (:name template))
                        (:prompt-templates (get-session-data-in ctx)))]
    (when-not existing?
      (swap-session! ctx update :prompt-templates conj template))
    {:added? (not existing?)
     :count  (count (:prompt-templates (get-session-data-in ctx)))}))

(defn add-skill-in!
  "Add `skill` to session data if its :name is not already present.
   Returns {:added? bool :count int}."
  [ctx skill]
  (let [existing? (some #(= (:name %) (:name skill))
                        (:skills (get-session-data-in ctx)))]
    (when-not existing?
      (swap-session! ctx update :skills conj skill))
    {:added? (not existing?)
     :count  (count (:skills (get-session-data-in ctx)))}))

(defn add-extension-in!
  "Load one extension file path into this session's extension registry.
   Returns {:loaded? bool :path string? :error string?}."
  [ctx path]
  (let [{:keys [extension error]}
        (ext/load-extension-in! (:extension-registry ctx)
                                path
                                (make-extension-action-fns ctx))]
    {:loaded? (some? extension)
     :path    extension
     :error   error}))

(pco/defmutation add-prompt-template
  [_ {:keys [psi/agent-session-ctx template]}]
  {::pco/params [:psi/agent-session-ctx :template]
   ::pco/output [:psi.prompt-template/added?
                 :psi.prompt-template/count]}
  (let [{:keys [added? count]} (add-prompt-template-in! agent-session-ctx template)]
    {:psi.prompt-template/added? added?
     :psi.prompt-template/count  count}))

(pco/defmutation add-skill
  [_ {:keys [psi/agent-session-ctx skill]}]
  {::pco/params [:psi/agent-session-ctx :skill]
   ::pco/output [:psi.skill/added?
                 :psi.skill/count]}
  (let [{:keys [added? count]} (add-skill-in! agent-session-ctx skill)]
    {:psi.skill/added? added?
     :psi.skill/count  count}))

(pco/defmutation add-extension
  [_ {:keys [psi/agent-session-ctx path]}]
  {::pco/params [:psi/agent-session-ctx :path]
   ::pco/output [:psi.extension/loaded?
                 :psi.extension/path
                 :psi.extension/error]}
  (let [{:keys [loaded? error]} (add-extension-in! agent-session-ctx path)]
    {:psi.extension/loaded? loaded?
     :psi.extension/path    path
     :psi.extension/error   error}))

(def all-mutations
  [add-prompt-template
   add-skill
   add-extension])

(defn register-resolvers-in!
  "Register all agent-session resolvers into an isolated `qctx` query context.
   Rebuilds the env unless `rebuild?` is false (useful when the caller will
   rebuild after adding further operations).
   Use in tests to avoid touching global state."
  ([qctx] (register-resolvers-in! qctx true))
  ([qctx rebuild?]
   (doseq [r resolvers/all-resolvers]
     (query/register-resolver-in! qctx r))
   (when rebuild?
     (query/rebuild-env-in! qctx))))

(defn register-mutations-in!
  "Register all agent-session mutations into an isolated `qctx` query context."
  ([qctx] (register-mutations-in! qctx true))
  ([qctx rebuild?]
   (doseq [m all-mutations]
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
  "Register all agent-session mutations into the global query graph and
   rebuild the environment."
  []
  (doseq [m all-mutations]
    (query/register-mutation! m))
  (query/rebuild-env!))

(defn- run-mutation-in!
  "Execute a registered mutation op in `qctx` with `params`.
   `op-sym` must be the qualified mutation symbol.
   Returns the mutation payload map (value under op-sym key)."
  [qctx op-sym params]
  (get (query/query-in qctx {}
                       [(list op-sym params)])
       op-sym))

(defn load-startup-resources-via-mutations-in!
  "Load startup prompt templates, skills, and extension paths by executing
   EQL mutations (one mutation call per resource).

   opts keys:
   :templates       — vector of prompt template maps
   :skills          — vector of skill maps
   :extension-paths — vector of extension file paths

   Returns {:prompt-count int :skill-count int :extension-results [result-map ...]}.
   Each extension result map includes :psi.extension/loaded?, :psi.extension/path,
   and optional :psi.extension/error."
  [ctx {:keys [templates skills extension-paths]
        :or   {templates [] skills [] extension-paths []}}]
  (let [qctx (query/create-query-context)
        _    (register-resolvers-in! qctx false)
        _    (register-mutations-in! qctx true)]
    (doseq [t templates]
      (run-mutation-in! qctx 'psi.agent-session.core/add-prompt-template
                        {:psi/agent-session-ctx ctx
                         :template              t}))
    (doseq [s skills]
      (run-mutation-in! qctx 'psi.agent-session.core/add-skill
                        {:psi/agent-session-ctx ctx
                         :skill                s}))
    (let [ext-results (mapv (fn [p]
                              (run-mutation-in! qctx 'psi.agent-session.core/add-extension
                                                {:psi/agent-session-ctx ctx
                                                 :path                  p}))
                            extension-paths)]
      {:prompt-count      (count (:prompt-templates (get-session-data-in ctx)))
       :skill-count       (count (:skills (get-session-data-in ctx)))
       :extension-results ext-results})))

(defn bootstrap-session-in!
  "Reusable session bootstrap for CLI/TUI and tests.

   Steps:
   1) ensure a session file exists (new-session-in!)
   2) optionally register global query resolvers/mutations
   3) register base tools and set system prompt
   4) load prompts/skills/extensions via EQL mutations
   5) merge extension tools into active tools
   6) persist startup summary to :startup-bootstrap in session data

   opts keys:
   :register-global-query? — register agent-session resolvers/mutations globally (default true)
   :base-tools             — base tool schema vector (default [])
   :system-prompt          — prompt string (default empty string)
   :templates              — prompt template maps (default [])
   :skills                 — skill maps (default [])
   :extension-paths        — extension file paths (default [])

   Returns startup summary map stored at :startup-bootstrap."
  [ctx {:keys [register-global-query? base-tools system-prompt templates skills extension-paths]
        :or   {register-global-query? true
               base-tools             []
               system-prompt          ""
               templates              []
               skills                 []
               extension-paths        []}}]
  (new-session-in! ctx)
  (when register-global-query?
    (register-resolvers!)
    (register-mutations!))
  (agent/set-tools-in! (:agent-ctx ctx) (vec base-tools))
  (agent/set-system-prompt-in! (:agent-ctx ctx) system-prompt)
  (let [{:keys [prompt-count skill-count extension-results]}
        (load-startup-resources-via-mutations-in!
         ctx {:templates templates
              :skills skills
              :extension-paths extension-paths})
        ext-errors (keep (fn [r]
                           (when-let [e (:psi.extension/error r)]
                             {:path  (:psi.extension/path r)
                              :error e}))
                         extension-results)
        ext-tools (ext/all-tools-in (:extension-registry ctx))
        _         (agent/set-tools-in! (:agent-ctx ctx)
                                       (into (vec base-tools) ext-tools))
        summary   {:timestamp              (java.time.Instant/now)
                   :prompt-count           prompt-count
                   :skill-count            skill-count
                   :extension-loaded-count (count (filter :psi.extension/loaded? extension-results))
                   :extension-error-count  (count ext-errors)
                   :extension-errors       (vec ext-errors)
                   :mutations              ['psi.agent-session.core/add-prompt-template
                                            'psi.agent-session.core/add-skill
                                            'psi.agent-session.core/add-extension]}]
    (swap-session! ctx assoc :startup-bootstrap summary)
    summary))

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
(defn cycle-thinking!   []       (cycle-thinking-level-in! (global-context)))
(defn set-name!         [n]      (set-session-name-in!    (global-context) n))
(defn compact!          []       (manual-compact-in!      (global-context)))
(defn compact-with!     [instr]  (manual-compact-in!      (global-context) instr))
(defn set-auto-compact! [e]      (set-auto-compaction-in! (global-context) e))
(defn set-auto-retry!   [e]      (set-auto-retry-in!      (global-context) e))

(defn query
  "Run EQL `q` against the global session context."
  [q]
  (query-in (global-context) q))
