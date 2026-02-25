(ns psi.engine.core
  "Core engine implementation using statecharts for system evolution"
  (:require
   [com.fulcrologic.statecharts.simple :as sc]
   [malli.core :as m]))

;; ========================================
;; Malli schemas for Engine entities
;; ========================================

(def engine-status-schema
  [:enum :initializing :ready :processing :error])

(def evolution-stage-schema
  [:enum :bootstrap :developing :integrating :complete])

(def system-mode-schema
  [:enum :build :explore :debug :reflect :play :atom])

(def engine-id-schema
  :string)

(def statechart-config-schema
  :map)

(def active-states-schema
  [:set :string])

(def state-transition-schema
  [:map
   [:engine-id :string]
   [:from-state :string]
   [:to-state :string]
   [:trigger :string]
   [:timestamp inst?]
   [:context :map]])

(def engine-schema
  [:map
   [:engine-id :string]
   [:engine-status engine-status-schema]
   [:statechart-config statechart-config-schema]
   [:active-states active-states-schema]
   [:state-transitions {:optional true} [:vector state-transition-schema]]])

(def system-state-schema
  [:map
   [:current-mode system-mode-schema]
   [:evolution-stage evolution-stage-schema]
   [:last-updated inst?]
   [:git-commit {:optional true} [:maybe :string]]
   [:engine-ready {:optional true} :boolean]
   [:query-ready {:optional true} :boolean]
   [:graph-ready {:optional true} :boolean]
   [:introspection-ready {:optional true} :boolean]
   [:history-ready {:optional true} :boolean]
   [:knowledge-ready {:optional true} :boolean]
   [:memory-ready {:optional true} :boolean]])

;; ========================================
;; Validation functions
;; ========================================

(defn valid-engine?
  "Validate engine data against schema"
  [engine-data]
  (m/validate engine-schema engine-data))

(defn valid-system-state?
  "Validate system state data against schema"
  [state-data]
  (m/validate system-state-schema state-data))

(defn valid-state-transition?
  "Validate state transition data against schema"
  [transition-data]
  (m/validate state-transition-schema transition-data))

(defn explain-validation-error
  "Get human-readable validation error explanation"
  [schema data]
  (m/explain schema data))

;; ========================================
;; State management atoms
;; ========================================

(defonce ^:private engines (atom {}))
(defonce ^:private system-state (atom nil))
(defonce ^:private state-transitions (atom []))
(defonce ^:private sc-env (atom nil))

;; ========================================
;; Engine statechart definition
;; ========================================

(def ^:private engine-statechart
  "Core engine statechart modeling system evolution"
  {::sc/id :engine
   ::sc/initial :initializing
   ::sc/states
   {:initializing {::sc/on {:initialization-failed :error
                            :configuration-start :configuring}}

    :configuring  {::sc/on {:configuration-complete :ready
                            :configuration-failed :error}}

    :ready        {::sc/entry (fn [env]
                                (-> env
                                    (assoc-in [:data :can-model-functionality] true)
                                    (assoc-in [:data :provides-state-access] true)))
                   ::sc/on {:start-processing :processing
                            :error-occurred :error}}

    :processing   {::sc/on {:processing-complete :ready
                            :processing-failed :error}}

    :error        {::sc/on {:recover :ready}}}})

;; ========================================
;; System evolution statechart
;; ========================================

(def ^:private system-evolution-statechart
  "System-wide evolution statechart"
  {::sc/id :system-evolution
   ::sc/initial :bootstrap
   ::sc/states
   {:bootstrap   {::sc/entry (fn [env]
                               (assoc-in env [:data :evolution-stage] :bootstrap))
                  ::sc/on {:engine-ready :developing}}

    :developing  {::sc/on {:graph-emerges :integrating}}

    :integrating {::sc/on {:ai-complete :complete}}

    :complete    {::sc/entry (fn [env]
                               (assoc-in env [:data :is-ai-complete] true))}}})

;; ========================================
;; Engine management functions
;; ========================================

(defn create-engine
  "Create a new engine instance with statechart configuration"
  ([]
   (create-engine (str (random-uuid))))
  ([engine-id]
   (create-engine engine-id {}))
  ([engine-id config]
   (let [_initial-data {:config-valid? (some? config)
                        :engine-id engine-id}
         env (or @sc-env (reset! sc-env (sc/simple-env)))
         _ (sc/register! env engine-id engine-statechart)
         _ (sc/start! env engine-id)
         engine {:engine-id engine-id
                 :engine-status :initializing
                 :statechart-config config
                 :active-states #{"initializing"}
                 :state-transitions []}]

     ;; Validate engine data
     (when-not (valid-engine? engine)
       (throw (ex-info "Invalid engine data"
                       {:engine-id engine-id
                        :validation-errors (explain-validation-error
                                            engine-schema
                                            engine)})))

     ;; Store engine
     (swap! engines assoc engine-id engine)

     ;; TODO: Send initial configuration events when we figure out proper targeting
     ;; (when (seq config)
     ;;   (sc/send! env :configuration-start)
     ;;   (sc/send! env :configuration-complete))

     engine)))

(defn get-engine
  "Retrieve engine by ID"
  [engine-id]
  (get @engines engine-id))

(defn get-all-engines
  "Get all engines"
  []
  @engines)

(defn engine-status
  "Get current status of engine"
  [engine-id]
  (when-let [engine (get-engine engine-id)]
    (let [current-state (:engine-status engine)]
      {:engine-status current-state
       :active-states #{current-state}
       :can-model-functionality (= :ready current-state)
       :provides-state-access true})))

(defn trigger-engine-event!
  "Send event to engine statechart"
  [engine-id event & [data]]
  (when-let [engine (get-engine engine-id)]
    (let [old-state (:engine-status engine)
          ;; TODO: Send the event to specific engine when we figure out targeting
          ;; (let [env @sc-env]
          ;;   (if data
          ;;     (sc/send! env event data)
          ;;     (sc/send! env event)))

          ;; For now, simulate state transition manually
          ;; TODO: Find way to get actual state from statechart
          new-state (case [old-state event]
                      [:initializing :configuration-start] :configuring
                      [:configuring :configuration-complete] :ready
                      [:ready :start-processing] :processing
                      [:processing :processing-complete] :ready
                      old-state)
          transition {:engine-id engine-id
                      :from-state (str old-state)
                      :to-state (str new-state)
                      :trigger (str event)
                      :timestamp (java.time.Instant/now)
                      :context (or data {})}]

        ;; Validate transition data
      (when-not (valid-state-transition? transition)
        (throw (ex-info "Invalid state transition data"
                        {:transition transition
                         :validation-errors (explain-validation-error
                                             state-transition-schema
                                             transition)})))

      ;; Store transition
      (swap! state-transitions conj transition)

      ;; Update engine status
      (swap! engines assoc-in [engine-id :engine-status] new-state)
      (swap! engines assoc-in [engine-id :active-states] #{new-state})

      transition)))

;; ========================================
;; System state management
;; ========================================

(defn initialize-system-state!
  "Initialize system state with bootstrap configuration"
  []
  (let [initial-state {:current-mode :explore
                       :evolution-stage :bootstrap
                       :last-updated (java.time.Instant/now)
                       :git-commit nil
                       :engine-ready false
                       :query-ready false
                       :graph-ready false
                       :introspection-ready false
                       :history-ready false
                       :knowledge-ready false
                       :memory-ready false}
        env (or @sc-env (reset! sc-env (sc/simple-env)))
        _ (sc/register! env :system system-evolution-statechart)
        _ (sc/start! env :system)]

    ;; Validate system state data
    (when-not (valid-system-state? initial-state)
      (throw (ex-info "Invalid system state data"
                      {:validation-errors (explain-validation-error
                                           system-state-schema
                                           initial-state)})))

    (reset! system-state initial-state)

    @system-state))

(defn get-system-state
  "Get current system state"
  []
  @system-state)

(defn update-system-component!
  "Mark a system component as ready"
  [component-key ready?]
  (when @system-state
    (swap! system-state assoc component-key ready?)

    ;; TODO: Check for evolution triggers and send events to system statechart when we figure out targeting
    ;; (let [state @system-state]
    ;;   (when-let [env @sc-env]
    ;;     (cond
    ;;       ;; Engine becomes ready
    ;;       (and (= component-key :engine-ready) ready?)
    ;;       (sc/send! env :engine-ready)

    ;;       ;; Graph emerges when engine, query, and graph are all ready
    ;;       (and (= component-key :graph-ready) ready?
    ;;            (:engine-ready state) (:query-ready state))
    ;;       (sc/send! env :graph-emerges)

    ;;       ;; AI Complete when all components are ready
    ;;       (and ready? (every? #(get state %) [:engine-ready :query-ready :graph-ready
    ;;                                           :introspection-ready :history-ready
    ;;                                           :knowledge-ready :memory-ready]))
    ;;       (sc/send! env :ai-complete)))))

    @system-state))

;; ========================================
;; Query functions for derived properties
;; ========================================

(defn system-has-interface?
  "Check if system has complete interface (engine + query ready)"
  []
  (when-let [state @system-state]
    (and (:engine-ready state) (:query-ready state))))

(defn system-has-substrate?
  "Check if system has substrate (engine ready)"
  []
  (when-let [state @system-state]
    (:engine-ready state)))

(defn system-has-memory-layer?
  "Check if system has complete memory layer"
  []
  (when-let [state @system-state]
    (and (:query-ready state) (:history-ready state) (:knowledge-ready state))))

(defn system-is-ai-complete?
  "Check if system has achieved AI COMPLETE status"
  []
  (when-let [state @system-state]
    (and (:engine-ready state) (:query-ready state) (:graph-ready state)
         (:introspection-ready state) (:history-ready state)
         (:knowledge-ready state) (:memory-ready state))))

;; ========================================
;; State transition queries
;; ========================================

(defn get-state-transitions
  "Get all state transitions, optionally filtered by engine"
  ([]
   @state-transitions)
  ([engine-id]
   (filter #(= (:engine-id %) engine-id) @state-transitions)))

(defn get-recent-transitions
  "Get recent state transitions within time window"
  [since-instant]
  (filter #(.isAfter (:timestamp %) since-instant) @state-transitions))

;; ========================================
;; Introspection and diagnostics
;; ========================================

(defn engine-diagnostics
  "Get diagnostic information about an engine"
  [engine-id]
  (when-let [engine (get-engine engine-id)]
    (let [status-info (engine-status engine-id)
          recent-transitions (take 10 (reverse (get-state-transitions engine-id)))]

      {:engine engine
       :status status-info
       :recent-transitions recent-transitions
       :transition-count (count (get-state-transitions engine-id))})))

(defn system-diagnostics
  "Get diagnostic information about the entire system"
  []
  (let [engines (get-all-engines)
        state (get-system-state)]

    {:system-state state
     :engine-count (count engines)
     :engines (into {} (map (fn [[id _]] [id (engine-diagnostics id)]) engines))
     :derived-properties {:has-interface (system-has-interface?)
                          :has-substrate (system-has-substrate?)
                          :has-memory-layer (system-has-memory-layer?)
                          :is-ai-complete (system-is-ai-complete?)}
     :total-transitions (count @state-transitions)}))

;; ========================================
;; Bootstrap functions
;; ========================================

(defn bootstrap-system!
  "Bootstrap the complete engine system"
  []
  (println "🚀 Bootstrapping Psi engine system...")

  ;; Initialize system state
  (initialize-system-state!)
  (println "✓ System state initialized")

  ;; Create main engine
  (let [main-engine (create-engine "main-engine" {:type :main :bootstrap true})]
    (println "✓ Main engine created:" (:engine-id main-engine))

    ;; Mark engine as ready
    (update-system-component! :engine-ready true)
    (println "✓ Engine marked as ready")

    ;; Return bootstrap summary
    {:system-state (get-system-state)
     :main-engine main-engine
     :engine-status (engine-status "main-engine")
     :diagnostics (system-diagnostics)}))