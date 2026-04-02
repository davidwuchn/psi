(ns psi.agent-session.dispatch-handlers
  "Dispatch handler registration for the agent-session pipeline.
   All handler registration is called once during context creation.

   This namespace registers handlers for:
   - Statechart action events (on-streaming-entered, on-agent-done, etc.)
   - Session mutation events (session/set-model, session/set-session-name, etc.)
   - Resume fallback handler"
  (:require
   [clojure.java.io :as io]
   [psi.agent-core.core :as agent]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.system-prompt :as sys-prompt]
   [psi.ui.state :as ui-state]))

;;; Thread utilities

(defn daemon-thread
  "Start a daemon thread running f. Returns the Thread."
  [f]
  (doto (Thread. ^Runnable f)
    (.setDaemon true)
    (.start)))

;;; Auto-compaction helpers

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
         (session-data/context-overflow-error? (:error-message msg)))))

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
  [session-id data]
  (let [ctx    (:ctx data)
        sd     (session/get-session-data-in ctx session-id)
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

(defn drop-trailing-overflow-error!
  "Remove a trailing overflow-error assistant message from the agent context.
   Called via ctx :drop-trailing-overflow-error-fn."
  [ctx session-id]
  (let [messages (:messages (agent/get-data-in (session/agent-ctx-in ctx session-id)))
        last-msg (last messages)]
    (when (overflow-error-assistant? last-msg)
      (agent/replace-messages-in! (session/agent-ctx-in ctx session-id) (vec (butlast messages))))))

;;; Prompt contribution pure helpers

(defn- normalize-prompt-contribution
  [ext-path id contribution]
  (let [now (java.time.Instant/now)
        c   (or contribution {})]
    {:id         (str id)
     :ext-path   (str ext-path)
     :section    (some-> (:section c) str)
     :content    (str (or (:content c) ""))
     :priority   (int (or (:priority c) 1000))
     :enabled    (if (contains? c :enabled) (boolean (:enabled c)) true)
     :created-at now
     :updated-at now}))

(defn- merge-prompt-contribution-patch
  [existing patch]
  (let [p (or patch {})
        now (java.time.Instant/now)]
    (cond-> (assoc existing :updated-at now)
      (contains? p :section)  (assoc :section (some-> (:section p) str))
      (contains? p :content)  (assoc :content (str (or (:content p) "")))
      (contains? p :priority) (assoc :priority (int (or (:priority p) 1000)))
      (contains? p :enabled)  (assoc :enabled (boolean (:enabled p))))))

;;; Telemetry

(def ^:private initial-telemetry
  {:tool-output-stats {:calls      []
                       :aggregates {:total-context-bytes  0
                                    :by-tool              {}
                                    :limit-hits-by-tool   {}}}
   :tool-call-attempts    []
   :tool-lifecycle-events []
   :provider-requests     []
   :provider-replies      []})

;;; State update pure helpers

(defn- session-data-path [sid] [:agent-session :sessions sid :data])
(defn- session-journal-path [sid] [:agent-session :sessions sid :persistence :journal])
(defn- session-flush-state-path [sid] [:agent-session :sessions sid :persistence :flush-state])
(defn- session-telemetry-path [sid k] [:agent-session :sessions sid :telemetry k])
(defn- session-turn-ctx-path [sid] [:agent-session :sessions sid :turn :ctx])
(def ^:private ui-state-path [:ui :extension-ui])

(defn- get-ui-state
  "Return current UI state map from ctx, defaulting to {}."
  [ctx]
  (get-ui-state ctx))

(defn- ui-root-update
  "Return a root-state update fn that replaces the UI state subtree."
  [new-ui-state]
  (fn [root] (assoc-in root ui-state-path new-ui-state)))

(defn- initialize-session-slots
  "Set journal, telemetry, and turn slots for sid.
   journal-entries is the initial journal vector ([] for new, (vec entries) for resume)."
  [state sid journal-entries]
  (-> state
      (assoc-in (session-journal-path sid) journal-entries)
      (assoc-in [:agent-session :sessions sid :telemetry] initial-telemetry)
      (assoc-in [:agent-session :sessions sid :turn] {:ctx nil})))

(defn- update-runtime-rpc-trace-state
  [state enabled? file]
  (assoc-in state [:runtime :rpc-trace] {:enabled? enabled?
                                         :file file}))

(defn- update-nrepl-runtime-state
  [state runtime]
  (assoc-in state [:runtime :nrepl] runtime))

(defn- update-oauth-projection-state
  [state oauth]
  (assoc-in state [:oauth] oauth))

(defn- update-recursion-projection-state
  [state recursion-state]
  (assoc-in state [:recursion] recursion-state))

(defn- update-background-jobs-store-state
  [state update-fn]
  (if (fn? update-fn)
    (update-in state [:background-jobs :store] update-fn)
    state))

(defn- initialize-resume-missing-state
  [state current-sd session-path]
  (let [next-sd (assoc current-sd :session-file session-path)
        sid     (:session-id next-sd)]
    (-> state
        (assoc-in (session-data-path sid) next-sd)
        (assoc-in (session-flush-state-path sid) {:flushed? false
                                                  :session-file (io/file session-path)})
        (initialize-session-slots sid []))))

(defn- carry-runtime-handles
  "Copy :agent-ctx and :sc-session-id from source-session-id to new-session-id."
  [state source-session-id new-session-id]
  (let [agent-ctx (get-in state [:agent-session :sessions source-session-id :agent-ctx])
        sc-sid    (get-in state [:agent-session :sessions source-session-id :sc-session-id])]
    (-> state
        (assoc-in [:agent-session :sessions new-session-id :agent-ctx] agent-ctx)
        (assoc-in [:agent-session :sessions new-session-id :sc-session-id] sc-sid))))

(defn- initialize-new-session-state
  [state current-sd {:keys [new-session-id worktree-path session-name spawn-mode session-file]}]
  (let [source-sid (:session-id current-sd)
        next-sd (assoc current-sd
                       :session-id new-session-id
                       :session-file session-file
                       :session-name session-name
                       :worktree-path worktree-path
                       :parent-session-id nil
                       :parent-session-path nil
                       :spawn-mode (or spawn-mode :new-root)
                       :interrupt-pending false
                       :interrupt-requested-at nil
                       :steering-messages []
                       :follow-up-messages []
                       :retry-attempt 0
                       :startup-prompts []
                       :startup-bootstrap-completed? false
                       :startup-bootstrap-started-at nil
                       :startup-bootstrap-completed-at nil
                       :startup-message-ids []
                       :created-at (java.time.Instant/now))]
    (cond-> (-> state
                (assoc-in (session-data-path new-session-id) next-sd)
                (initialize-session-slots new-session-id []))
      session-file
      (assoc-in (session-flush-state-path new-session-id) {:flushed? false
                                                           :session-file (io/file session-file)}))))

(defn- initialize-child-session-state
  "Add a child session entry without switching active-session-id.
  The child is a lightweight session for agent execution."
  [state parent-sd {:keys [child-session-id session-name system-prompt tool-schemas thinking-level]}]
  (let [child-sd (merge (session-data/initial-session
                         {:worktree-path (:worktree-path parent-sd)})
                        {:session-id         child-session-id
                         :session-name       session-name
                         :spawn-mode         :agent
                         :parent-session-id  (:session-id parent-sd)
                         :system-prompt      (or system-prompt (:system-prompt parent-sd))
                         :base-system-prompt (or system-prompt (:base-system-prompt parent-sd))
                         :thinking-level     (or thinking-level :off)
                         :tool-schemas       tool-schemas
                         :model              (:model parent-sd)
                         :created-at         (java.time.Instant/now)})]
    (-> state
        (assoc-in (session-data-path child-session-id) child-sd)
        (assoc-in [:agent-session :sessions child-session-id :persistence]
                  {:journal []
                   :flush-state {:flushed? false :session-file nil}})
        (initialize-session-slots child-session-id [])
        (assoc-in [:agent-session :sessions child-session-id :sc-session-id]
                  (java.util.UUID/randomUUID)))))

(defn- initialize-resumed-session-state
  [state current-sd {:keys [session-id source-session-id session-path header entries model thinking-level]}]
  (let [session-name (some #(when (= :session-info (:kind %))
                              (get-in % [:data :name]))
                           (rseq (vec entries)))
        next-sd      (assoc current-sd
                            :session-id session-id
                            :session-file session-path
                            :session-name session-name
                            :worktree-path (or (:worktree-path header) (:cwd header))
                            :parent-session-id (:parent-session-id header)
                            :parent-session-path (:parent-session header)
                            :interrupt-pending false
                            :interrupt-requested-at nil
                            :model model
                            :thinking-level thinking-level)]
    (-> state
        (assoc-in (session-flush-state-path session-id) {:flushed? true
                                                         :session-file (io/file session-path)})
        (assoc-in (session-data-path session-id) next-sd)
        (initialize-session-slots session-id (vec entries)))))

(defn- initialize-forked-session-state
  [state parent-sd {:keys [new-session-id branch-entries session-file]}]
  (let [parent-session-id   (:session-id parent-sd)
        parent-session-file (:session-file parent-sd)
        next-sd             (assoc parent-sd
                                   :session-id new-session-id
                                   :parent-session-id parent-session-id
                                   :parent-session-path parent-session-file
                                   :session-file session-file
                                   :startup-prompts []
                                   :startup-bootstrap-completed? false
                                   :startup-bootstrap-started-at nil
                                   :startup-bootstrap-completed-at nil
                                   :startup-message-ids [])]
    ;; carry-runtime-handles must run before any pruning as active session may be ephemeral
    (cond-> (-> state
                (carry-runtime-handles parent-session-id new-session-id)
                (assoc-in (session-data-path new-session-id) next-sd)
                (initialize-session-slots new-session-id branch-entries))
      session-file
      (assoc-in (session-flush-state-path new-session-id) {:flushed? true
                                                           :session-file (io/file session-file)}))))

;;; Handler registration

(defn- register-statechart-action-handlers!
  "Register event handlers for all statechart action keys.
   Each handler receives (ctx, data) and performs the action.
   Called once during context creation.
   Handlers are context-independent — they use the ctx passed at dispatch time,
   not a closed-over reference.

   All statechart-action handlers return pure `:root-state-update` / `:effects`
   results."
  [_ctx]
  (dispatch/register-handler!
   :on-streaming-entered
   {}
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :is-streaming true))}))

  (dispatch/register-handler!
   :on-agent-done
   {}
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc %
                                                         :is-streaming false
                                                         :retry-attempt 0
                                                         :interrupt-pending false
                                                         :interrupt-requested-at nil))
      :effects [{:effect/type :runtime/mark-workflow-jobs-terminal}
                {:effect/type :runtime/emit-background-job-terminal-messages}]}))

  (dispatch/register-handler!
   :on-abort
   {}
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc %
                                                         :is-streaming false
                                                         :interrupt-pending false
                                                         :interrupt-requested-at nil))
      :effects [{:effect/type :runtime/agent-abort}]}))

  (dispatch/register-handler!
   :on-auto-compact-triggered
   {}
   (fn [_ctx {:keys [session-id] :as data}]
     (let [reason      (or (auto-compaction-reason session-id data) :threshold)
           will-retry? (= :overflow reason)]
       {:root-state-update (session/session-update session-id #(assoc % :is-compacting true))
        :effects [{:effect/type :runtime/auto-compact-workflow
                   :reason reason
                   :will-retry? will-retry?}]})))

  (dispatch/register-handler!
   :on-compacting-entered
   {}
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :is-compacting true))}))

  (dispatch/register-handler!
   :on-compact-done
   {}
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :is-compacting false))}))

  (dispatch/register-handler!
   :on-retry-triggered
   {}
   (fn [ctx {:keys [session-id]}]
     (let [sd       (session/get-session-data-in ctx session-id)
           attempt  (:retry-attempt sd)
           base-ms  (get-in ctx [:config :auto-retry-base-delay-ms] 2000)
           max-ms   (get-in ctx [:config :auto-retry-max-delay-ms] 60000)
           delay-ms (session-data/exponential-backoff-ms attempt base-ms max-ms)]
       {:root-state-update (session/session-update session-id #(update % :retry-attempt inc))
        :effects [{:effect/type :runtime/schedule-thread-sleep-send-event
                   :delay-ms delay-ms
                   :event :session/retry-done}]})))

  (dispatch/register-handler!
   :on-retrying-entered
   {}
   (fn [_ctx _data]
     nil)) ;; retry-attempt increment handled in :on-retry-triggered

  (dispatch/register-handler!
   :on-retry-resume
   {}
   (fn [_ctx _data]
     {:effects [{:effect/type :runtime/agent-start-loop}]})))

(defn- register-session-mutation-handlers!
  "Register dispatch handlers for public session mutation events.
   This broadens dispatch coverage beyond statechart actions."
  [_ctx]
  (dispatch/register-handler!
   :session/manual-compaction-execute
   {}
   (fn [ctx {:keys [session-id custom-instructions]}]
     ;; Intentional narrow synchronous boundary for the manual compaction
     ;; vertical slice. The surrounding statechart transitions route through
     ;; dispatch; the compaction execution itself remains synchronous here so
     ;; callers can receive the compaction result directly.
     ;; execute-compaction-fn is set on ctx by core.clj to avoid a circular dep.
     ((:execute-compaction-fn ctx)
      ctx
      session-id
      custom-instructions)))

  (dispatch/register-handler!
   :session/prompt-submit
   {}
   (fn [_ctx {:keys [user-msg]}]
     {:effects [{:effect/type :persist/journal-append-message-entry
                 :message user-msg}]
      :return {:submitted? true}}))

  (dispatch/register-handler!
   :session/prompt-execute
   {}
   (fn [_ctx {:keys [user-msg]}]
     {:effects [{:effect/type :runtime/agent-start-loop-with-messages
                 :messages [user-msg]}
                {:effect/type :runtime/reconcile-and-emit-background-job-terminals}]}))

  (dispatch/register-handler!
   :session/set-auto-compaction
   {}
   (fn [_ctx {:keys [session-id enabled?]}]
     (let [v (boolean enabled?)]
       {:root-state-update (session/session-update session-id #(assoc % :auto-compaction-enabled v))
        :return {:auto-compaction-enabled v}})))

  (dispatch/register-handler!
   :session/set-auto-retry
   {}
   (fn [_ctx {:keys [session-id enabled?]}]
     (let [v (boolean enabled?)]
       {:root-state-update (session/session-update session-id #(assoc % :auto-retry-enabled v))
        :return {:auto-retry-enabled v}})))

  (dispatch/register-handler!
   :session/set-ui-type
   {}
   (fn [_ctx {:keys [session-id ui-type]}]
     {:root-state-update (session/session-update session-id #(assoc % :ui-type ui-type))}))

  ;; UI state mutations (dispatch-owned)
  (dispatch/register-handler!
   :session/ui-set-widget-spec
   {}
   (fn [ctx {:keys [extension-id spec]}]
     (let [ext-id (or extension-id (:extension-id spec) "unknown")
           {:keys [state result]} (ui-state/set-widget-spec (get-ui-state ctx) ext-id spec)]
       {:root-state-update (ui-root-update state)
        :return (if result
                  {:accepted? false :errors (:errors result)}
                  {:accepted? true :errors nil})})))

  (dispatch/register-handler!
   :session/ui-set-widget
   {}
   (fn [ctx {:keys [extension-id widget-id placement content]}]
     (let [{:keys [state]} (ui-state/set-widget (get-ui-state ctx) extension-id widget-id placement content)]
       {:root-state-update (ui-root-update state)
        :return {:accepted? true}})))

  (dispatch/register-handler!
   :session/ui-clear-widget
   {}
   (fn [ctx {:keys [extension-id widget-id]}]
     (let [{:keys [state]} (ui-state/clear-widget (get-ui-state ctx) extension-id widget-id)]
       {:root-state-update (ui-root-update state)
        :return {:cleared? true}})))

  (dispatch/register-handler!
   :session/ui-clear-widget-spec
   {}
   (fn [ctx {:keys [extension-id widget-id]}]
     (let [{:keys [state]} (ui-state/clear-widget-spec (get-ui-state ctx) extension-id widget-id)]
       {:root-state-update (ui-root-update state)
        :return {:cleared? true}})))

  (dispatch/register-handler!
   :session/ui-resolve-dialog
   {}
   (fn [ctx {:keys [dialog-id result]}]
     (let [{:keys [state result]} (ui-state/resolve-dialog (get-ui-state ctx) dialog-id result)
           accepted? result]
       {:root-state-update (ui-root-update state)
        :return {:accepted? (boolean accepted?)}})))

  (dispatch/register-handler!
   :session/ui-cancel-dialog
   {}
   (fn [ctx _]
     (let [{:keys [state result]} (ui-state/cancel-dialog (get-ui-state ctx))]
       {:root-state-update (ui-root-update state)
        :return {:accepted? (boolean result)}})))

  (dispatch/register-handler!
   :session/ui-set-status
   {}
   (fn [ctx {:keys [extension-id text]}]
     (let [{:keys [state]} (ui-state/set-status (get-ui-state ctx) extension-id text)]
       {:root-state-update (ui-root-update state)})))

  (dispatch/register-handler!
   :session/ui-clear-status
   {}
   (fn [ctx {:keys [extension-id]}]
     (let [{:keys [state]} (ui-state/clear-status (get-ui-state ctx) extension-id)]
       {:root-state-update (ui-root-update state)})))

  (dispatch/register-handler!
   :session/ui-register-tool-renderer
   {}
   (fn [ctx {:keys [tool-name extension-id render-call-fn render-result-fn]}]
     (let [{:keys [state]} (ui-state/register-tool-renderer (get-ui-state ctx) tool-name extension-id render-call-fn render-result-fn)]
       {:root-state-update (ui-root-update state)})))

  (dispatch/register-handler!
   :session/ui-register-message-renderer
   {}
   (fn [ctx {:keys [custom-type extension-id render-fn]}]
     (let [{:keys [state]} (ui-state/register-message-renderer (get-ui-state ctx) custom-type extension-id render-fn)]
       {:root-state-update (ui-root-update state)})))

  (dispatch/register-handler!
   :session/ui-set-tools-expanded
   {}
   (fn [ctx {:keys [expanded?]}]
     (let [{:keys [state]} (ui-state/set-tools-expanded (get-ui-state ctx) expanded?)]
       {:root-state-update (ui-root-update state)
        :return {:tools-expanded? (boolean expanded?)}})))

  (dispatch/register-handler!
   :session/ui-notify
   {}
   (fn [ctx {:keys [extension-id message level]}]
     (let [{:keys [state]} (ui-state/notify (get-ui-state ctx) extension-id message level)]
       {:root-state-update (ui-root-update state)})))

  (dispatch/register-handler!
   :session/set-model
   {}
   ;; scope: :session (default) — runtime only
   ;;        :project           — persist to .psi/project.edn
   ;;        :user              — persist to ~/.psi/agent/config.edn
   (fn [ctx {:keys [session-id model scope]}]
     (let [clamped-level (session-data/clamp-thinking-level
                          (:thinking-level (session/get-session-data-in ctx session-id))
                          model)
           persist-effect (case (or scope :project)
                            :user    {:effect/type :persist/user-config-update
                                      :prefs {:model-provider (:provider model)
                                              :model-id (:id model)
                                              :thinking-level clamped-level}}
                            :session nil
                            ;; :project is the default
                            {:effect/type :persist/project-prefs-update
                             :prefs {:model-provider (:provider model)
                                     :model-id (:id model)
                                     :thinking-level clamped-level}})]
       {:root-state-update (session/session-update session-id #(assoc % :model model :thinking-level clamped-level))
        :return {:model model :thinking-level clamped-level}
        :effects (cond-> [{:effect/type :runtime/agent-set-model
                           :model model}
                          {:effect/type :persist/journal-append-model-entry
                           :provider (:provider model)
                           :model-id (:id model)}
                          {:effect/type :notify/extension-dispatch
                           :event-name "model_select"
                           :payload {:model model :source :set}}]
                   persist-effect (conj persist-effect))})))

  (dispatch/register-handler!
   :session/set-thinking-level
   {}
   ;; scope: :session (default) — runtime only
   ;;        :project           — persist to .psi/project.edn
   ;;        :user              — persist to ~/.psi/agent/config.edn
   (fn [ctx {:keys [session-id level scope]}]
     (let [sd      (session/get-session-data-in ctx session-id)
           model   (:model sd)
           clamped (if model (session-data/clamp-thinking-level level model) level)
           persist-effect (case (or scope :project)
                            :user    {:effect/type :persist/user-config-update
                                      :prefs {:thinking-level clamped}}
                            :session nil
                            {:effect/type :persist/project-prefs-update
                             :prefs {:thinking-level clamped}})]
       {:root-state-update (session/session-update session-id #(assoc % :thinking-level clamped))
        :return {:thinking-level clamped}
        :effects (cond-> [{:effect/type :runtime/agent-set-thinking-level
                           :level clamped}
                          {:effect/type :persist/journal-append-thinking-level-entry
                           :level clamped}]
                   persist-effect (conj persist-effect))})))

  (dispatch/register-handler!
   :session/set-worktree-path
   {}
   (fn [_ctx {:keys [session-id worktree-path]}]
     {:root-state-update (session/session-update session-id #(assoc % :worktree-path (str worktree-path)))}))

  (dispatch/register-handler!
   :session/set-cache-breakpoints
   {}
   (fn [_ctx {:keys [session-id breakpoints]}]
     {:root-state-update (session/session-update session-id #(assoc % :cache-breakpoints (set (or breakpoints #{}))))}))

  (dispatch/register-handler!
   :session/set-prompt-mode
   {}
   ;; scope: :session (default) — runtime only
   ;;        :project           — persist to .psi/project.edn
   ;;        :user              — persist to ~/.psi/agent/config.edn
   (fn [_ctx {:keys [session-id mode scope]}]
     (let [validated      (if (#{:lambda :prose} mode) mode :lambda)
           persist-effect (case (or scope :session)
                            :project {:effect/type :persist/project-prefs-update
                                      :prefs {:prompt-mode validated}}
                            :user    {:effect/type :persist/user-config-update
                                      :prefs {:prompt-mode validated}}
                            nil)]
       {:root-state-update (session/session-update session-id #(assoc % :prompt-mode validated))
        :effects (cond-> [{:effect/type :runtime/refresh-system-prompt}]
                   persist-effect (conj persist-effect))})))

  (dispatch/register-handler!
   :session/set-system-prompt-build-opts
   {}
   (fn [_ctx {:keys [session-id opts]}]
     {:root-state-update (session/session-update session-id #(assoc % :system-prompt-build-opts opts))}))

  (dispatch/register-handler!
   :session/set-session-name
   {}
   (fn [_ctx {:keys [session-id name]}]
     {:root-state-update (session/session-update session-id #(assoc % :session-name name))
      :effects [{:effect/type :persist/journal-append-session-info-entry
                 :name name}]
      :return {:session-name name}}))

  (dispatch/register-handler!
   :session/refresh-system-prompt
   {}
   (fn [ctx {:keys [session-id]}]
     (let [sd      (session/get-session-data-in ctx session-id)
           contrib (session/list-prompt-contributions-in ctx session-id)
           ;; When build opts are stored, rebuild from scratch with current mode
           ;; and contributions inline (so ordering is: skills → contributions → context)
           [base prompt]
           (if-let [build-opts (:system-prompt-build-opts sd)]
             (let [full (sys-prompt/build-system-prompt
                         (assoc build-opts
                                :prompt-mode (:prompt-mode sd :lambda)
                                :prompt-contributions contrib))]
               [full full])
             (let [b (or (:base-system-prompt sd) (:system-prompt sd) "")]
               [b (sys-prompt/apply-prompt-contributions b contrib)]))]
       {:root-state-update (session/session-update session-id #(assoc %
                                                           :base-system-prompt base
                                                           :system-prompt prompt))
        :effects [{:effect/type :runtime/agent-set-system-prompt
                   :prompt prompt}]})))

  (dispatch/register-handler!
   :session/set-system-prompt
   {}
   (fn [ctx {:keys [session-id prompt]}]
     (let [base*   (or prompt "")
           contrib (session/list-prompt-contributions-in ctx session-id)
           prompt* (sys-prompt/apply-prompt-contributions base* contrib)]
       {:root-state-update (session/session-update session-id #(assoc %
                                                           :base-system-prompt base*
                                                           :system-prompt prompt*))
        :effects [{:effect/type :runtime/agent-set-system-prompt
                   :prompt prompt*}]})))

  (dispatch/register-handler!
   :session/register-prompt-contribution
   {}
   (fn [ctx {:keys [session-id ext-path id contribution]}]
     (let [ext-path* (str ext-path)
           id*       (str id)
           norm      (normalize-prompt-contribution ext-path* id* contribution)]
       {:root-state-update
        (session/session-update session-id
         (fn [sd]
           (let [xs    (or (:prompt-contributions sd) [])
                 xs*   (vec (remove #(and (= ext-path* (:ext-path %))
                                          (= id* (:id %)))
                                    xs))
                 next* (conj xs* norm)
                 base  (or (:base-system-prompt sd) (:system-prompt sd) "")
                 prompt (sys-prompt/apply-prompt-contributions
                         base
                         (session/sorted-prompt-contributions next*))]
             (assoc sd
                    :prompt-contributions next*
                    :system-prompt prompt))))
        :effects [{:effect/type :runtime/agent-set-system-prompt
                   :prompt (let [sd   (session/get-session-data-in ctx session-id)
                                 base (or (:base-system-prompt sd) (:system-prompt sd) "")
                                 xs   (or (:prompt-contributions sd) [])
                                 xs*  (vec (remove #(and (= ext-path* (:ext-path %))
                                                         (= id* (:id %)))
                                                   xs))
                                 next* (conj xs* norm)]
                             (sys-prompt/apply-prompt-contributions
                              base
                              (session/sorted-prompt-contributions next*)))}]
        :return {:registered? true
                 :contribution norm
                 :count (count (session/list-prompt-contributions-in ctx session-id))}})))

  (dispatch/register-handler!
   :session/update-prompt-contribution
   {}
   (fn [ctx {:keys [session-id ext-path id patch]}]
     (let [ext-path* (str ext-path)
           id*       (str id)
           sd        (session/get-session-data-in ctx session-id)
           xs        (vec (or (:prompt-contributions sd) []))
           found     (some #(and (= ext-path* (:ext-path %)) (= id* (:id %))) xs)]
       (if-not found
         {:return {:updated? false
                   :contribution nil
                   :count (count (session/sorted-prompt-contributions xs))}}
         (let [updated (atom nil)
               next*   (mapv (fn [c]
                               (if (and (= ext-path* (:ext-path c))
                                        (= id* (:id c)))
                                 (let [next (merge-prompt-contribution-patch c patch)]
                                   (reset! updated next)
                                   next)
                                 c))
                             xs)
               base    (or (:base-system-prompt sd) (:system-prompt sd) "")
               prompt* (sys-prompt/apply-prompt-contributions
                        base
                        (session/sorted-prompt-contributions next*))]
           {:root-state-update (session/session-update session-id #(assoc %
                                                               :prompt-contributions next*
                                                               :system-prompt prompt*))
            :effects [{:effect/type :runtime/agent-set-system-prompt
                       :prompt prompt*}]
            :return {:updated? true
                     :contribution @updated
                     :count (count (session/sorted-prompt-contributions next*))}})))))

  (dispatch/register-handler!
   :session/unregister-prompt-contribution
   {}
   (fn [ctx {:keys [session-id ext-path id]}]
     (let [ext-path* (str ext-path)
           id*       (str id)
           sd        (session/get-session-data-in ctx session-id)
           xs        (vec (or (:prompt-contributions sd) []))
           next*     (vec (remove #(and (= ext-path* (:ext-path %))
                                        (= id* (:id %)))
                                  xs))
           removed?  (< (count next*) (count xs))]
       (if-not removed?
         {:return {:removed? false
                   :count (count xs)}}
         (let [base    (or (:base-system-prompt sd) (:system-prompt sd) "")
               prompt* (sys-prompt/apply-prompt-contributions
                        base
                        (session/sorted-prompt-contributions next*))]
           {:root-state-update (session/session-update session-id #(assoc %
                                                               :prompt-contributions next*
                                                               :system-prompt prompt*))
            :effects [{:effect/type :runtime/agent-set-system-prompt
                       :prompt prompt*}]
            :return {:removed? true
                     :count (count next*)}})))))

  (dispatch/register-handler!
   :session/set-active-tools
   {}
   (fn [_ctx {:keys [session-id tool-maps]}]
     {:root-state-update (session/session-update session-id #(assoc %
                                                         :active-tools (->> tool-maps (map :name) set)
                                                         :tool-schemas (vec tool-maps)))
      :effects [{:effect/type :runtime/agent-set-tools
                 :tool-maps tool-maps}
                {:effect/type :runtime/refresh-system-prompt}]}))

  (dispatch/register-handler!
   :session/startup-bootstrap-begin
   {}
   (fn [_ctx {:keys [session-id started-at]}]
     {:root-state-update (session/session-update session-id #(assoc %
                                                         :startup-bootstrap-started-at started-at
                                                         :startup-bootstrap-completed? false
                                                         :startup-bootstrap-completed-at nil
                                                         :startup-message-ids []))}))

  (dispatch/register-handler!
   :session/record-startup-message-id
   {}
   (fn [_ctx {:keys [session-id message-id]}]
     (when message-id
       {:root-state-update (session/session-update session-id #(update % :startup-message-ids (fnil conj []) message-id))})))

  (dispatch/register-handler!
   :session/startup-bootstrap-complete
   {}
   (fn [_ctx {:keys [session-id startup-prompts completed-at]}]
     {:root-state-update (session/session-update session-id
                          (fn [sd]
                            (cond-> (assoc sd
                                           :startup-bootstrap-completed? true
                                           :startup-bootstrap-completed-at completed-at)
                              (some? startup-prompts)
                              (assoc :startup-prompts startup-prompts))))}))

  (dispatch/register-handler!
   :session/set-startup-bootstrap-summary
   {}
   (fn [_ctx {:keys [session-id summary]}]
     {:root-state-update (session/session-update session-id #(assoc % :startup-bootstrap summary))}))

  (dispatch/register-handler!
   :session/update-context-usage
   {}
   (fn [_ctx {:keys [session-id tokens window]}]
     {:root-state-update (session/session-update session-id #(assoc % :context-tokens tokens :context-window window))}))

  (dispatch/register-handler!
   :session/record-extension-prompt
   {}
   (fn [_ctx {:keys [session-id source delivery at]}]
     {:root-state-update (session/session-update session-id #(assoc %
                                                         :extension-last-prompt-source (some-> source str)
                                                         :extension-last-prompt-delivery delivery
                                                         :extension-last-prompt-at at))}))

  ;; No-op: prompt time and cwd are frozen at session creation for cache stability.
  ;; Callers (session-lifecycle new/resume) still dispatch this event; it is harmless.
  (dispatch/register-handler!
   :session/retarget-runtime-prompt-metadata
   {}
   (fn [_ctx _data]
     {:effects []}))

  (dispatch/register-handler!
   :session/set-rpc-trace
   {}
   (fn [_ctx {:keys [enabled? file]}]
     {:root-state-update #(update-runtime-rpc-trace-state % enabled? file)}))

  (dispatch/register-handler!
   :session/set-nrepl-runtime
   {}
   (fn [_ctx {:keys [runtime]}]
     {:root-state-update #(update-nrepl-runtime-state % runtime)}))

  (dispatch/register-handler!
   :session/set-oauth-projection
   {}
   (fn [_ctx {:keys [oauth]}]
     {:root-state-update #(update-oauth-projection-state % oauth)}))

  (dispatch/register-handler!
   :session/set-recursion-state
   {}
   (fn [_ctx {:keys [recursion-state]}]
     {:root-state-update #(update-recursion-projection-state % recursion-state)}))

  (dispatch/register-handler!
   :session/update-background-jobs-state
   {}
   (fn [_ctx {:keys [update-fn]}]
     {:root-state-update #(update-background-jobs-store-state % update-fn)}))

  (dispatch/register-handler!
   :session/set-turn-context
   {}
   (fn [_ctx {:keys [session-id turn-ctx]}]
     {:root-state-update (fn [state]
                           (assoc-in state (session-turn-ctx-path session-id) turn-ctx))}))

  (dispatch/register-handler!
   :session/append-tool-call-attempt
   {}
   (fn [_ctx {:keys [session-id attempt]}]
     {:root-state-update (fn [state]
                           (update-in state (session-telemetry-path session-id :tool-call-attempts)
                                      (fnil conj [])
                                      (assoc attempt :timestamp (java.time.Instant/now))))}))

  (dispatch/register-handler!
   :session/append-provider-request-capture
   {}
   (fn [_ctx {:keys [session-id capture]}]
     (let [entry (assoc capture :timestamp (java.time.Instant/now))]
       {:root-state-update
        (fn [state]
          (update-in state (session-telemetry-path session-id :provider-requests)
                     (fn [entries]
                       (let [entries* (conj (vec (or entries [])) entry)
                             n        (count entries*)]
                         (if (> n 100)
                           (subvec entries* (- n 100))
                           entries*)))))})))

  (dispatch/register-handler!
   :session/append-provider-reply-capture
   {}
   (fn [_ctx {:keys [session-id capture]}]
     (let [entry (assoc capture :timestamp (java.time.Instant/now))]
       {:root-state-update
        (fn [state]
          (update-in state (session-telemetry-path session-id :provider-replies)
                     (fn [entries]
                       (let [entries* (conj (vec (or entries [])) entry)
                             n        (count entries*)]
                         (if (> n 1000)
                           (subvec entries* (- n 1000))
                           entries*)))))})))

  (dispatch/register-handler!
   :session/record-tool-output-stat
   {}
   (fn [_ctx {:keys [session-id stat context-bytes-added limit-hit?]}]
     {:root-state-update
      (fn [state]
        (update-in state (session-telemetry-path session-id :tool-output-stats)
                   (fn [ts]
                     (-> ts
                         (update :calls (fnil conj []) stat)
                         (update-in [:aggregates :total-context-bytes] (fnil + 0) context-bytes-added)
                         (update-in [:aggregates :by-tool (:tool-name stat)] (fnil + 0) context-bytes-added)
                         (update-in [:aggregates :limit-hits-by-tool (:tool-name stat)]
                                    (fnil + 0)
                                    (if limit-hit? 1 0))))))}))

  (dispatch/register-handler!
   :session/tool-lifecycle-event
   {}
   (fn [_ctx {:keys [session-id entry]}]
     {:root-state-update (fn [state]
                           (update-in state (session-telemetry-path session-id :tool-lifecycle-events)
                                      (fnil conj [])
                                      (assoc entry :timestamp (java.time.Instant/now))))}))

  (dispatch/register-handler!
   :session/tool-agent-start
   {}
   (fn [_ctx {:keys [tool-call]}]
     {:effects [{:effect/type :runtime/agent-emit-tool-start
                 :tool-call tool-call}]}))

  (dispatch/register-handler!
   :session/tool-agent-end
   {}
   (fn [_ctx {:keys [tool-call result is-error?]}]
     {:effects [{:effect/type :runtime/agent-emit-tool-end
                 :tool-call tool-call
                 :result result
                 :is-error? is-error?}]}))

  (dispatch/register-handler!
   :session/tool-agent-record-result
   {}
   (fn [_ctx {:keys [tool-result-msg]}]
     {:effects [{:effect/type :runtime/agent-record-tool-result
                 :tool-result-msg tool-result-msg}
                {:effect/type :persist/journal-append-message-entry
                 :message tool-result-msg}]}))

  (dispatch/register-handler!
   :session/tool-execute
   {}
   (fn [_ctx {:keys [session-id tool-name args opts]}]
     {:effects [{:effect/type :runtime/tool-execute
                 :session-id session-id
                 :tool-name tool-name
                 :args args
                 :opts opts}]
      :return-effect-result? true}))

  (dispatch/register-handler!
   :session/tool-run
   {}
   (fn [_ctx {:keys [session-id tool-call parsed-args progress-queue]}]
     {:effects [{:effect/type :runtime/tool-run
                 :session-id session-id
                 :tool-call tool-call
                 :parsed-args parsed-args
                 :progress-queue progress-queue}]
      :return-effect-result? true}))

  (dispatch/register-handler!
   :session/new-initialize
   {}
   (fn [ctx {:keys [session-id new-session-id worktree-path session-name spawn-mode session-file]}]
     (let [current-sd (or (session/get-session-data-in ctx session-id)
                          (assoc (session-data/initial-session (:session-defaults ctx))
                                 :provider-error-replies []))
           payload    {:new-session-id new-session-id
                       :worktree-path worktree-path
                       :session-name session-name
                       :spawn-mode spawn-mode
                       :session-file session-file}]
       {:root-state-update #(initialize-new-session-state % current-sd payload)
        :return-key        (session-data-path new-session-id)
        :effects [{:effect/type :runtime/agent-reset}]})))

  (dispatch/register-handler!
   :session/resume-loaded
   {}
   (fn [ctx {:keys [session-id source-session-id session-path header entries model thinking-level messages]}]
     (let [source-sid  (or source-session-id session-id)
           current-sd  (session/get-session-data-in ctx source-sid)
           payload     {:session-id session-id
                        :source-session-id source-sid
                        :session-path session-path
                        :header header
                        :entries entries
                        :model model
                        :thinking-level thinking-level}]
       {:root-state-update #(initialize-resumed-session-state % current-sd payload)
        :return-key        (session-data-path session-id)
        :effects (cond-> [{:effect/type :runtime/agent-reset}
                          {:effect/type :runtime/agent-set-thinking-level
                           :level thinking-level}]
                   model
                   (conj {:effect/type :runtime/agent-set-model
                          :model model})
                   messages
                   (conj {:effect/type :runtime/agent-replace-messages
                          :messages messages}))})))

  (dispatch/register-handler!
   :session/fork-initialize
   {}
   (fn [ctx {:keys [session-id new-session-id branch-entries session-file messages]}]
     (let [parent-sd (session/get-session-data-in ctx session-id)
           payload   {:new-session-id new-session-id
                      :branch-entries branch-entries
                      :session-file session-file}]
       {:root-state-update #(initialize-forked-session-state % parent-sd payload)
        :return-key        (session-data-path new-session-id)
        :effects (when messages
                   [{:effect/type :runtime/agent-replace-messages
                     :messages messages}])})))

  (dispatch/register-handler!
   :session/create-child
   {}
   (fn [ctx {:keys [session-id child-session-id session-name system-prompt tool-schemas thinking-level]}]
     (let [parent-sd (session/get-session-data-in ctx session-id)]
       {:root-state-update #(initialize-child-session-state % parent-sd
                                                            {:child-session-id child-session-id
                                                             :session-name     session-name
                                                             :system-prompt    system-prompt
                                                             :tool-schemas     tool-schemas
                                                             :thinking-level   thinking-level})
        :return child-session-id})))

  (dispatch/register-handler!
   :session/enqueue-steering-message
   {}
   (fn [_ctx {:keys [session-id text]}]
     {:root-state-update (session/session-update session-id #(update % :steering-messages (fnil conj []) text))
      :effects [{:effect/type :runtime/agent-queue-steering
                 :message {:role      "user"
                           :content   [{:type :text :text text}]
                           :timestamp (java.time.Instant/now)}}]}))

  (dispatch/register-handler!
   :session/enqueue-follow-up-message
   {}
   (fn [_ctx {:keys [session-id text]}]
     {:root-state-update (session/session-update session-id #(update % :follow-up-messages (fnil conj []) text))
      :effects [{:effect/type :runtime/agent-queue-follow-up
                 :message {:role      "user"
                           :content   [{:type :text :text text}]
                           :timestamp (java.time.Instant/now)}}]}))

  (dispatch/register-handler!
   :session/clear-queued-messages
   {}
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :steering-messages [] :follow-up-messages []))
      :effects [{:effect/type :runtime/agent-clear-steering-queue}
                {:effect/type :runtime/agent-clear-follow-up-queue}]}))

  (dispatch/register-handler!
   :session/compaction-finished
   {}
   (fn [_ctx {:keys [session-id messages]}]
     (cond-> {:root-state-update (session/session-update session-id #(assoc % :is-compacting false :context-tokens nil))}
       messages
       (assoc :effects [{:effect/type :runtime/agent-replace-messages
                         :messages messages}]))))

  (dispatch/register-handler!
   :session/reset-prompt-contributions
   {}
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :prompt-contributions []))}))

  (dispatch/register-handler!
   :session/register-prompt-template
   {}
   (fn [ctx {:keys [session-id template]}]
     (let [templates  (vec (:prompt-templates (session/get-session-data-in ctx session-id)))
           existing?  (some #(= (:name %) (:name template)) templates)
           next-count (if existing? (count templates) (inc (count templates)))]
       (cond-> {:return {:added? (not existing?)
                         :count  next-count}}
         (not existing?)
         (assoc :root-state-update (session/session-update session-id #(update % :prompt-templates (fnil conj []) template)))))))

  (dispatch/register-handler!
   :session/register-skill
   {}
   (fn [ctx {:keys [session-id skill]}]
     (let [skills     (vec (:skills (session/get-session-data-in ctx session-id)))
           existing?  (some #(= (:name %) (:name skill)) skills)
           next-count (if existing? (count skills) (inc (count skills)))]
       (cond-> {:return {:added? (not existing?)
                         :count  next-count}}
         (not existing?)
         (assoc :root-state-update (session/session-update session-id #(update % :skills (fnil conj []) skill)))))))

  (dispatch/register-handler!
   :session/request-interrupt
   {}
   (fn [ctx {:keys [session-id already-pending? requested-at]}]
     (let [sd (session/get-session-data-in ctx session-id)]
       {:root-state-update
        (session/session-update session-id
         (fn [_]
           (cond-> (assoc sd
                          :interrupt-pending true
                          :steering-messages [])
             (not already-pending?)
             (assoc :interrupt-requested-at requested-at))))
        :effects [{:effect/type :runtime/agent-clear-steering-queue}]})))

  (dispatch/register-handler!
   :session/bootstrap-prompt-state
   {}
   (fn [_ctx {:keys [session-id system-prompt developer-prompt developer-prompt-source]}]
     {:root-state-update (session/session-update session-id #(assoc %
                                                         :base-system-prompt system-prompt
                                                         :system-prompt system-prompt
                                                         :developer-prompt developer-prompt
                                                         :developer-prompt-source developer-prompt-source))}))

  (dispatch/register-handler!
   :session/ensure-base-system-prompt
   {}
   (fn [ctx {:keys [session-id]}]
     (let [sd (session/get-session-data-in ctx session-id)]
       (when-not (contains? sd :base-system-prompt)
         {:root-state-update (session/session-update session-id #(assoc % :base-system-prompt (or (:system-prompt sd) "")))}))))

  (dispatch/register-handler!
   :session/send-extension-message
   {}
   (fn [ctx {:keys [session-id message]}]
     (let [bootstrap-complete? (:startup-bootstrap-completed?
                                (session/get-session-data-in ctx session-id))]
       {:effects (cond-> []
                   bootstrap-complete?
                   (into [{:effect/type :runtime/agent-append-message
                           :message message}
                          {:effect/type :runtime/agent-emit
                           :event {:type :message-start :message message}}
                          {:effect/type :runtime/agent-emit
                           :event {:type :message-end :message message}}])
                   true
                   (conj {:effect/type :runtime/event-queue-offer
                          :event {:type :external-message
                                  :message message}}))})))

  (dispatch/register-handler!
   :session/add-tool
   {}
   (fn [ctx {:keys [session-id tool]}]
     (let [tools     (:tools (agent/get-data-in (session/agent-ctx-in ctx session-id)))
           existing? (some #(= (:name %) (:name tool)) tools)]
       (cond-> {:return {:added? (not existing?)
                         :count  (if existing? (count tools) (inc (count tools)))}}
         (not existing?)
         (assoc :effects [{:effect/type :runtime/agent-set-tools
                           :tool-maps (conj (vec tools) tool)}])))))

  nil)

(defn- register-resume-fallback-handler!
  []
  (dispatch/register-handler!
   :session/resume-missing-initialize
   {}
   (fn [ctx {:keys [session-id session-path]}]
     (let [current-sd (session/get-session-data-in ctx session-id)]
       {:root-state-update #(initialize-resume-missing-state % current-sd session-path)
        :return-key        (session-data-path session-id)})))

  nil)

;;; Wiring functions

(defn make-actions-fn
  "Return the side-effect dispatcher wired into the statechart working memory.
   The statechart calls (actions-fn action-key data) where data is the working
   memory map containing :session-id.
   Delegates to the dispatch pipeline for all registered action keys."
  [ctx]
  (fn [action-key data]
    (dispatch/dispatch! ctx action-key data {:origin :statechart})))

(defn dispatch-statechart-event-in!
  "Adapter boundary for routing session statechart events through dispatch.

   Returns {:claimed? true} when the event was sent to the session statechart.
   This makes statechart participation explicit in the dispatch pipeline while
   preserving the existing statechart runtime and transition ownership."
  [ctx event-type event-data _ictx]
  (when (contains? #{:session/prompt :session/abort :session/compact-start :session/compact-done} event-type)
    (sc/send-event! (:sc-env ctx) (session/sc-session-id-in ctx (:session-id event-data)) event-type event-data)
    {:claimed? true}))

(defn register-all!
  "Register all dispatch handlers for the agent-session pipeline.
   Called once during context creation via requiring-resolve."
  [ctx]
  (register-statechart-action-handlers! ctx)
  (register-session-mutation-handlers! ctx)
  (register-resume-fallback-handler!))
