(ns psi.agent-session.dispatch
  "Event dispatch pipeline — the single coordination point for state mutations.

   Architecture
   ────────────
   Every state change enters the system as a named event — a keyword plus
   a data map.  The dispatch function runs the event through an interceptor
   chain, then calls the handler.

   Interceptor chain
   ─────────────────
   Each interceptor is a map with :id, optional :before and :after fns.
   :before fns run in registration order; :after fns run in reverse order.
   Both receive and return an interceptor context map.

   The default chain is:
   [permission-interceptor
    log-interceptor
    statechart-interceptor
    handler-interceptor
    effect-interceptor
    trim-effects-on-replay
    validate-interceptor
    apply-interceptor]

   Sequencing contract for pure handler results:
   1. handler computes a pure result
   2. apply writes state and surfaces declared effects onto interceptor context
   3. validate checks post-apply state / interceptor context
   4. during replay, effects are suppressed while state application is preserved
   5. effects execute last

   Pure result shape:
   - {:root-state-update f :effects [...] :return val :return-key path}
   Session-data handlers use the `session-update` wrapper from core.

   Interceptor context keys:
     :ctx         — session context map (opaque to interceptors)
     :event       — canonical normalized event map
     :event-type  — keyword (compat projection from :event)
     :event-data  — map or nil (compat projection from :event)
     :result      — handler return value (set by handler-interceptor)
     :blocked?    — if true, handler is skipped
     :block-reason — why blocked (string or keyword)
     :validation-error — validation failure data when validation blocks dispatch
     :statechart-claimed? — true when statechart routing handled the event directly
     :replaying?  — when true, effect execution is suppressed
     :log         — event log entries (populated by log-interceptor)

   Migration model
   ───────────────
   During migration, handlers may still perform side effects directly.
   As handlers become pure (returning {:db new-state :effects [...]}),
   the dispatch fn will apply state and execute effects at the boundary.

   Current architectural boundary
   ─────────────────────────────
   Dispatch now owns most runtime-visible mutation/effect coordination,
   including tool lifecycle side effects around execution. Actual tool
   execution itself is still intentionally executor-owned and has not yet
   fully moved under one dispatch-owned runtime effect boundary.

   Usage
   ─────
   (dispatch! ctx :on-streaming-entered {})
   (dispatch! ctx :on-agent-done {:pending-agent-event event})"
  (:require
   [psi.agent-session.dispatch-schema :as schema]
   [taoensso.timbre :as timbre]))

(def effect-schema schema/effect-schema)
(def pure-result-schema schema/pure-result-schema)
(def valid-effect? schema/valid-effect?)
(def explain-effect schema/explain-effect)
(def valid-pure-result?* schema/valid-pure-result?*)
(def explain-pure-result schema/explain-pure-result)
(def validate-dispatch-schemas schema/validate-dispatch-schemas)

;; ============================================================
;; Event handler registry
;; ============================================================

(defonce ^:private handler-registry
  ;; Map of event-type keyword → handler entry.
  ;; Entry shape: {:fn handler-fn}
  ;; Handler fn signature: (fn [ctx event-data]) → any
  (atom {}))

(defn handler-entry
  "Return the registered handler entry for `event-type`, or nil."
  [event-type]
  (get @handler-registry event-type))

(defn register-handler!
  "Register a handler for `event-type`.

   Arity 2:
   - (register-handler! event-type handler-fn)

   Arity 3 (opts currently unused, retained for call-site compatibility):
   - (register-handler! event-type opts handler-fn)

   Replaces any existing handler for that type."
  ([event-type handler-fn]
   (register-handler! event-type nil handler-fn))
  ([event-type _opts handler-fn]
   (swap! handler-registry assoc event-type
          {:fn handler-fn})
   nil))

(defn registered-event-types
  "Return the set of registered event type keywords."
  []
  (set (keys @handler-registry)))

(defn registered-handler-entries
  "Return all registered handler entries keyed by event type.

   Function values are preserved for internal use; query/read-model adapters
   should project them into non-executable metadata before exposing them."
  []
  @handler-registry)

(defn clear-handlers!
  "Remove all registered handlers. Used in tests and during reload."
  []
  (reset! handler-registry {})
  nil)

;; ============================================================
;; Interceptor chain
;; ============================================================

(defn ->interceptor
  "Create an interceptor map.
   `opts` keys: :id (required), :before (fn [ictx] → ictx), :after (fn [ictx] → ictx)."
  [{:keys [id before after]}]
  {:id     id
   :before (or before identity)
   :after  (or after identity)})

(declare next-dispatch-id append-trace-entry! pure-result? append-interceptor-trace!)

(defn run-interceptor-chain
  "Run `interceptors` over interceptor context `ictx`.
   Executes :before fns in order, then :after fns in reverse order.
   Returns the final interceptor context."
  [ictx interceptors]
  (let [after-before (reduce (fn [ctx i]
                               (if (:blocked? ctx)
                                 ctx
                                 (let [_ (append-interceptor-trace! :dispatch/interceptor-enter ctx i)]
                                   ((:before i) ctx))))
                             ictx
                             interceptors)]
    (reduce (fn [ctx i]
              (let [after-ctx ((:after i) ctx)]
                (append-interceptor-trace! :dispatch/interceptor-exit after-ctx i)
                after-ctx))
            after-before
            (reverse interceptors))))

;; ============================================================
;; Event normalization
;; ============================================================

(defn normalize-event
  "Normalize public dispatch inputs into one canonical internal event value.
   Lifts :session-id from event-data to :event/session-id so interceptors
   and effect handlers can access it without reading ctx."
  [event-type event-data opts]
  {:event/type        event-type
   :event/data        event-data
   :event/session-id  (:session-id event-data)
   :event/origin      (or (:origin opts) :core)
   :event/ext-id      (:ext-id opts)
   :event/replaying?  (boolean (:replaying? opts))
   :event/dispatch-id (or (:dispatch-id opts) (next-dispatch-id))})

(defn- event-type-of [ictx]
  (or (:event-type ictx)
      (get-in ictx [:event :event/type])))

(defn- event-data-of [ictx]
  (if (contains? ictx :event-data)
    (:event-data ictx)
    (get-in ictx [:event :event/data])))

(defn- event-origin-of [ictx]
  (or (:origin ictx)
      (get-in ictx [:event :event/origin])
      :core))

(defn- event-ext-id-of [ictx]
  (or (:ext-id ictx)
      (get-in ictx [:event :event/ext-id])))

(defn- event-replaying?-of [ictx]
  (or (:replaying? ictx)
      (boolean (get-in ictx [:event :event/replaying?]))))

(defn- event-session-id-of [ictx]
  (or (:session-id ictx)
      (get-in ictx [:event :event/session-id])))

(defn dispatch-id-of
  [ictx]
  (or (:dispatch-id ictx)
      (get-in ictx [:event :event/dispatch-id])))

(defn- append-interceptor-trace!
  [trace-kind ictx interceptor]
  (append-trace-entry! {:trace/kind     trace-kind
                        :dispatch-id    (dispatch-id-of ictx)
                        :session-id     (event-session-id-of ictx)
                        :event-type     (event-type-of ictx)
                        :interceptor-id (:id interceptor)}))

(defn- summarize-handler-result
  [result]
  (if (pure-result? result)
    {:kind                  :pure-result
     :effect-count          (count (:effects result))
     :has-root-state-update (boolean (:root-state-update result))
     :has-return            (contains? result :return)
     :return-key            (:return-key result)
     :return-effect-result? (boolean (:return-effect-result? result))}
    {:kind  :legacy-return
     :value result}))

;; ============================================================
;; Built-in interceptors
;; ============================================================

;; ── Event log ───────────────────────────────────────────────

(defonce ^:private event-log
  ;; Bounded ring buffer of recent dispatch log entries.
  (atom []))

(defonce ^:private dispatch-trace
  ;; Bounded ring buffer of canonical dispatch/service trace entries.
  (atom []))

(def ^:private max-event-log-size
  "Maximum number of entries retained in the dispatch event log."
  1000)

(def ^:private max-dispatch-trace-size
  "Maximum number of entries retained in the canonical dispatch trace."
  1000)


(defn- trim-bounded-log
  [entries max-size]
  (let [xs (vec entries)
        n  (count xs)]
    (if (> n max-size)
      (subvec xs (- n max-size))
      xs)))

(defn event-log-entries
  "Return the current mixed dispatch event log entries (most recent last)."
  []
  @event-log)

(defn dispatch-trace-entries
  "Return the current canonical dispatch trace entries (most recent last)."
  []
  @dispatch-trace)

(defn clear-event-log!
  "Clear the retained dispatch event log. Used in tests."
  []
  (reset! event-log [])
  nil)

(defn clear-dispatch-trace!
  "Clear the retained canonical dispatch trace. Used in tests."
  []
  (reset! dispatch-trace [])
  nil)

(defn next-dispatch-id
  "Create a stable id for one dispatched event."
  []
  (str (java.util.UUID/randomUUID)))

(defn append-trace-entry!
  "Append one canonical trace entry to the bounded dispatch trace."
  [entry]
  (swap! dispatch-trace
         (fn [log]
           (trim-bounded-log
            (conj log (assoc entry :timestamp (System/currentTimeMillis)))
            max-dispatch-trace-size)))
  entry)

(defn assoc-dispatch-id
  "Assoc `:dispatch-id` onto `m` when `dispatch-id` is present.
   Small helper to keep explicit trace-id threading terse."
  [m dispatch-id]
  (cond-> (or m {})
    dispatch-id (assoc :dispatch-id dispatch-id)))

(defn- summarize-dispatch-db
  [db]
  (when (map? db)
    {:root-keys            (-> db keys vec sort)
     :session-count        (count (get-in db [:agent-session :sessions]))
     :has-background-jobs? (boolean (get-in db [:background-jobs :store]))
     :has-turn-ctx?        (boolean (get-in db [:turn :ctx]))}))

(declare dispatch!)

(defn replay-event-entry!
  "Replay one retained dispatch log entry against `ctx`.

   Re-dispatches the entry's event-type and event-data with `:replaying? true`,
   which suppresses effect execution while preserving pure state application."
  [ctx entry]
  (dispatch! ctx
             (:event-type entry)
             (:event-data entry)
             {:origin (:origin entry)
              :ext-id (:ext-id entry)
              :replaying? true}))

(defn replay-event-log!
  "Replay retained replayable dispatch entries against `ctx` in order.

   Returns a vector of per-entry dispatch return values."
  [ctx entries]
  (mapv #(replay-event-entry! ctx %) entries))

(defn- dispatch-log-entry
  [ictx]
  (cond-> {:event-type           (event-type-of ictx)
           :event-data           (event-data-of ictx)
           :origin               (event-origin-of ictx)
           :blocked?             (boolean (:blocked? ictx))
           :timestamp            (::log-timestamp ictx)
           :duration-ms          (- (System/currentTimeMillis)
                                    (or (::log-timestamp ictx) 0))
           :replaying?           (boolean (event-replaying?-of ictx))
           :statechart-claimed?  (boolean (:statechart-claimed? ictx))
           :validation-error     (:validation-error ictx)
           :declared-effects     (or (some-> ictx :pure-result :effects vec) [])
           :applied-effects      (or (:applied-effects ictx) [])
           :pure-result-kind     (cond
                                   (contains? (:pure-result ictx) :root-state-update) :root-state-update
                                   (some? (:pure-result ictx)) :pure
                                   :else nil)
           :db-summary-before    (or (::db-summary-before ictx)
                                     (some-> ictx :ctx :state* deref summarize-dispatch-db))
           :db-summary-after     (some-> ictx :ctx :state* deref summarize-dispatch-db)}
    (event-ext-id-of ictx)       (assoc :ext-id (event-ext-id-of ictx))
    (:block-reason ictx)         (assoc :block-reason (:block-reason ictx))))

(def log-interceptor
  "Captures event dispatch in the event log.
   :before records the timestamp and a bounded db summary.
   :after appends a log entry with event, timing, replay/validation/statechart
   metadata, effect summaries, and bounded before/after db summaries."
  (->interceptor
   {:id :log
    :before
    (fn [ictx]
      (assoc ictx
             ::log-timestamp (System/currentTimeMillis)
             ::db-summary-before (some-> ictx :ctx :state* deref summarize-dispatch-db)))

    :after
    (fn [ictx]
      (let [entry (dispatch-log-entry ictx)]
        (swap! event-log (fn [log]
                           (trim-bounded-log (conj log entry) max-event-log-size)))
        (dissoc ictx ::log-timestamp ::db-summary-before)))}))

;; ── Permission interceptor ───────────────────────────────────

(def permission-interceptor
  "Checks extension dispatch rights.
   When :origin is :extension, verifies the extension is registered in the
   extension registry on the session context and, when present, enforces
   manifest-declared `:allowed-events` membership.

   Current migration behavior for missing manifests:
   - registered extension with explicit `:allowed-events` => enforced
   - registered extension with no `:allowed-events` => compatibility allow
   - unknown extension => blocked

   Non-extension origins (:core, :statechart, :adapter) bypass this check."
  (->interceptor
   {:id :permission
    :before
    (fn [ictx]
      (if (= :extension (event-origin-of ictx))
        (let [ext-id      (event-ext-id-of ictx)
              event-type  (event-type-of ictx)
              ctx         (:ctx ictx)
              reg         (:extension-registry ctx)
              state       (when reg @(:state reg))
              ext-record  (when (and ext-id state)
                            (get-in state [:extensions ext-id]))
              known?      (some? ext-record)
              allowed-set (:allowed-events ext-record)]
          (cond
            (not known?)
            (assoc ictx
                   :blocked? true
                   :block-reason :unknown-extension)

            (set? allowed-set)
            (if (contains? allowed-set event-type)
              ictx
              (assoc ictx
                     :blocked? true
                     :block-reason {:reason :permission-denied
                                    :event-type event-type
                                    :ext-id ext-id}))

            :else
            (assoc ictx :permission-compat? true)))
        ictx))}))

;; ── Statechart interceptor ──────────────────────────────────

(def statechart-interceptor
  "Routes statechart-owned events through an explicit dispatch boundary.

   This is a minimal adapter-shaped interceptor for the current migration phase.
   If ctx provides `:dispatch-statechart-event-fn`, it is called as:
   (dispatch-statechart-event-fn ctx event-type event-data ictx)

   Return contract from the callback:
   - nil / false => event not claimed; normal dispatch continues
   - truthy non-map => event claimed; handler stage is skipped
   - map supports:
     - `:claimed?` boolean
     - `:result` optional return value
     - `:blocked?` optional boolean
     - `:block-reason` optional reason

   Claimed events set `:statechart-claimed? true` and `:blocked? true` so the
   handler stage is skipped. This makes statechart routing explicit in the
   dispatch pipeline without redesigning broader ownership yet."
  (->interceptor
   {:id :statechart
    :before
    (fn [ictx]
      (if (:blocked? ictx)
        ictx
        (let [ctx         (:ctx ictx)
              dispatch-fn (:dispatch-statechart-event-fn ctx)]
          (if-not (fn? dispatch-fn)
            ictx
            (let [result (dispatch-fn ctx (event-type-of ictx) (event-data-of ictx) ictx)
                  claimed? (cond
                             (map? result) (boolean (:claimed? result))
                             :else (boolean result))]
              (if claimed?
                (let [claimed-ictx (assoc ictx
                                          :statechart-claimed? true
                                          :blocked? true)]
                  (if (map? result)
                    (cond-> claimed-ictx
                      (contains? result :result)
                      (assoc :result (:result result))

                      (contains? result :blocked?)
                      (assoc :blocked? (boolean (:blocked? result)))

                      (contains? result :block-reason)
                      (assoc :block-reason (:block-reason result)))
                    claimed-ictx))
                ictx))))))}))

;; ── Handler interceptor ─────────────────────────────────────

(defn pure-result?
  "True when `x` is a supported pure handler result map.

   Supported shapes:
   - {:root-state-update f} — root-state transform (use session-update wrapper for session-data)
   - {:effects [...]} — effect descriptions
   - optional return payloads: :return :return-key
   - optional effect-return flag: :return-effect-result?"
  [x]
  (and (map? x)
       (or (contains? x :root-state-update)
           (contains? x :effects)
           (contains? x :return)
           (contains? x :return-key)
           (contains? x :return-effect-result?))))

(def handler-interceptor
  "Looks up and invokes the registered handler for the event type.
   Sets :result on the interceptor context.

   Ensures handlers always receive :session-id when the event carries one.
   The session-id is sourced from event-data (via the interceptor context),
   never from ctx.

   If the handler returns a pure result map ({:session-update fn :effects [...]}),
   it is stored as :pure-result for the apply interceptor.
   Otherwise, the handler is legacy and its return value is stored as :result."
  (->interceptor
   {:id :handler
    :before
    (fn [ictx]
      (if (:blocked? ictx)
        ictx
        (if-let [entry (handler-entry (event-type-of ictx))]
          (let [handler-fn   (:fn entry)
                ctx          (:ctx ictx)
                raw-data     (event-data-of ictx)
                ;; Ensure :session-id is in handler data when the event
                ;; carries one (sourced from ictx, never from ctx).
                eff-sid      (:session-id ictx)
                handler-data (cond-> (or raw-data {})
                               (and eff-sid (not (:session-id raw-data)))
                               (assoc :session-id eff-sid)

                               (and (dispatch-id-of ictx)
                                    (not (:dispatch-id raw-data)))
                               (assoc :dispatch-id (dispatch-id-of ictx)))
                result (try
                         (handler-fn ctx handler-data)
                         (catch Exception e
                           (timbre/warn "Dispatch handler error"
                                        (event-type-of ictx)
                                        (ex-message e))
                           nil))]
            (append-trace-entry! {:trace/kind  :dispatch/handler-result
                                  :dispatch-id (dispatch-id-of ictx)
                                  :session-id  (event-session-id-of ictx)
                                  :event-type  (event-type-of ictx)
                                  :result      (summarize-handler-result result)})
            (if (pure-result? result)
              (assoc ictx :pure-result result)
              (assoc ictx :result result)))
          ictx)))}))

;; ── Apply interceptor ────────────────────────────────────────

(def apply-interceptor
  "Applies pure handler results to canonical state.

   Application: `:root-state-update` via `(:apply-root-state-update-fn ctx)`.
   Session-data handlers use `(session-update f)` to wrap their transform.

   Additional behavior:
   - supports `:return-key` via `(:read-session-state-fn ctx)`
   - may request the first effect result as dispatch return via `:return-effect-result?`
   - stores declared effects on :applied-effects for the effect interceptor

   Skipped when no :pure-result is present (legacy handler)."
  (->interceptor
   {:id :apply
    :after
    (fn [ictx]
      (if-let [pure-result (:pure-result ictx)]
        (let [ctx            (:ctx ictx)
              root-update-fn (:root-state-update pure-result)
              apply-root-fn  (:apply-root-state-update-fn ctx)
              read-fn        (:read-session-state-fn ctx)
              return-key     (:return-key pure-result)
              return-effect-result? (:return-effect-result? pure-result)]
          (when (and (fn? root-update-fn) (fn? apply-root-fn))
            (apply-root-fn ctx root-update-fn))
          (when (contains? pure-result :effects)
            (append-trace-entry! {:trace/kind  :dispatch/effects-emitted
                                  :dispatch-id (dispatch-id-of ictx)
                                  :session-id  (event-session-id-of ictx)
                                  :event-type  (event-type-of ictx)
                                  :effects     (vec (:effects pure-result))}))
          (cond-> ictx
            (contains? pure-result :effects)
            (assoc :applied-effects (:effects pure-result))
            return-effect-result?
            (assoc :return-effect-result? true)
            (contains? pure-result :return)
            (assoc :result (:return pure-result))
            (and return-key (fn? read-fn))
            (assoc :result (read-fn ctx return-key))))
        ictx))}))

(def effect-interceptor
  "Executes effect descriptions at the boundary.

   Looks for :applied-effects on the interceptor context and calls
   (execute-dispatch-effect-fn ctx effect) for each effect when available.
   Pure handlers can now return {:effects [...]} while legacy handlers keep
   performing side effects inline.

   When `:return-effect-result?` is true on the interceptor context, the first
   executed effect result becomes dispatch return value unless a prior explicit
   `:result` is already set.

   Replay-aware suppression is handled by `trim-effects-on-replay`, which runs
   earlier in after-order and removes :applied-effects before this interceptor
   executes."
  (->interceptor
   {:id :effects
    :after
    (fn [ictx]
      (let [ctx         (:ctx ictx)
            execute-fn  (:execute-dispatch-effect-fn ctx)
            effects     (:applied-effects ictx)
            dispatch-id (dispatch-id-of ictx)
            ;; Inject the resolved session-id into each effect that doesn't
            ;; already carry one, so effect handlers never need to read ctx.
            eff-sid     (:session-id ictx)
            effects*    (mapv (fn [e] (if eff-sid
                                        (if (:session-id e) e (assoc e :session-id eff-sid))
                                        e))
                              effects)]
        (if (and (fn? execute-fn) (seq effects*))
          (let [results (mapv (fn [effect]
                                (append-trace-entry! {:trace/kind  :dispatch/effect-start
                                                      :dispatch-id dispatch-id
                                                      :session-id  (:session-id effect)
                                                      :event-type  (event-type-of ictx)
                                                      :effect-type (:effect/type effect)
                                                      :effect      effect})
                                (try
                                  (let [result (execute-fn ctx effect)]
                                    (append-trace-entry! {:trace/kind  :dispatch/effect-finish
                                                          :dispatch-id dispatch-id
                                                          :session-id  (:session-id effect)
                                                          :event-type  (event-type-of ictx)
                                                          :effect-type (:effect/type effect)
                                                          :effect      effect
                                                          :result      result})
                                    result)
                                  (catch Throwable t
                                    (append-trace-entry! {:trace/kind    :dispatch/effect-finish
                                                          :dispatch-id   dispatch-id
                                                          :session-id    (:session-id effect)
                                                          :event-type    (event-type-of ictx)
                                                          :effect-type   (:effect/type effect)
                                                          :effect        effect
                                                          :error-message (ex-message t)})
                                    (throw t))))
                              effects*)]
            (cond-> ictx
              (and (:return-effect-result? ictx)
                   (nil? (:result ictx))
                   (seq results))
              (assoc :result (first results))))
          ictx)))}))

(def validate-interceptor
  "Validates dispatch results after state application and before replay/effect handling.

   Validation is intentionally scaffolded and narrow in the first pass:
   - if ctx provides `:validate-dispatch-result-fn`, it is called as
     (validate-dispatch-result-fn ctx ictx)
   - a truthy non-map result means valid
   - nil or false means invalid with generic reason
   - a map result may provide:
     - `:valid?` boolean
     - `:reason` keyword/string/map

   Invalid results block dispatch and suppress effect execution by removing
   :applied-effects from the interceptor context.

   Important current semantic:
   - validation is post-apply, not pre-commit
   - invalid validation does not roll back already-applied state"
  (->interceptor
   {:id :validate
    :after
    (fn [ictx]
      (if (:blocked? ictx)
        ictx
        (let [ctx         (:ctx ictx)
              validate-fn (:validate-dispatch-result-fn ctx)]
          (if-not (fn? validate-fn)
            ictx
            (let [result (try
                           (validate-fn ctx ictx)
                           (catch Exception e
                             (timbre/warn "Dispatch validation error"
                                          (event-type-of ictx)
                                          (ex-message e))
                             {:valid? false
                              :reason {:type :validator-exception
                                       :message (ex-message e)}}))
                  valid? (cond
                           (map? result) (not= false (:valid? result true))
                           :else (boolean result))]
              (if valid?
                ictx
                (let [reason (if (map? result) (:reason result :validation-failed) :validation-failed)]
                  (assoc (dissoc ictx :applied-effects)
                         :blocked? true
                         :block-reason reason
                         :validation-error (if (map? result)
                                             (or (:reason result) result)
                                             :validation-failed)))))))))}))

(def trim-effects-on-replay
  "Suppresses effect execution during replay while preserving state application
   and handler return behavior.

   When :replaying? is true, removes any :applied-effects from the interceptor
   context before the effect interceptor runs."
  (->interceptor
   {:id :trim-effects-on-replay
    :after
    (fn [ictx]
      (if (:replaying? ictx)
        (dissoc ictx :applied-effects)
        ictx))}))

;; ── Default chain ───────────────────────────────────────────

(def default-interceptors
  "The default interceptor chain applied to every dispatched event.
   Before order: permission → log → statechart → handler → effects → trim-effects-on-replay → validate → apply
   After order:  apply → validate → trim-effects-on-replay → effects → handler → statechart → log → permission

   One obvious sequencing rule for pure results:
   - statechart may claim the event before handler dispatch
   - apply state first
   - validate second (post-apply)
   - trim effects for replay third
   - execute effects last

   Current migration semantic:
   - the statechart interceptor is an adapter boundary over ctx-provided routing
   - invalid validation suppresses effects but does not roll back already-applied state"
  [permission-interceptor
   log-interceptor
   statechart-interceptor
   handler-interceptor
   effect-interceptor
   trim-effects-on-replay
   validate-interceptor
   apply-interceptor])

;; ============================================================
;; Interceptor chain configuration
;; ============================================================

(defonce ^:private interceptor-chain-override
  ;; When non-nil, dispatch! uses this chain instead of default-interceptors.
  (atom nil))

(defn set-interceptors!
  "Override the interceptor chain. Pass nil to restore defaults."
  [interceptors]
  (reset! interceptor-chain-override interceptors)
  nil)

(defn current-interceptors
  "Return the active interceptor chain."
  []
  (or @interceptor-chain-override default-interceptors))

;; ============================================================
;; Dispatch
;; ============================================================

(defn dispatch!
  "Dispatch a named event through the interceptor chain.

   `ctx`        — session context map
   `event-type` — keyword identifying the event
   `event-data` — map of event-specific data (may be nil)
   `opts`       — optional map:
                  :origin     — :core | :statechart | :adapter | :extension (default :core)
                  :ext-id     — extension id string (required when origin is :extension)
                  :replaying? — when true, suppress effect execution while preserving
                                pure state application and return values

   Public call sites still pass `(event-type, event-data, opts)`, but dispatch
   now normalizes these into one canonical internal event map under `:event` on
   the interceptor context.

   Runs the event through the interceptor chain, which includes logging,
   permission checking, handler invocation, state application, validation,
   and replay-aware effect suppression. Returns the handler's return value,
   or nil if no handler is registered or the event was blocked."
  ([ctx event-type]
   (dispatch! ctx event-type nil nil))
  ([ctx event-type event-data]
   (dispatch! ctx event-type event-data nil))
  ([ctx event-type event-data opts]
   (let [event       (normalize-event event-type event-data opts)
         session-id  (event-session-id-of {:event event})
         dispatch-id (:event/dispatch-id event)
         ictx        {:ctx         ctx
                      :session-id  session-id
                      :dispatch-id dispatch-id
                      :event       event
                      :event-type  (:event/type event)
                      :event-data  (:event/data event)
                      :origin      (:event/origin event)
                      :ext-id      (:event/ext-id event)
                      :replaying?  (:event/replaying? event)
                      :result      nil
                      :blocked?    false}]
     (append-trace-entry! {:trace/kind      :dispatch/received
                           :dispatch-id     dispatch-id
                           :session-id      session-id
                           :event-type      (:event/type event)
                           :event-data      (:event/data event)
                           :origin          (:event/origin event)
                           :replaying?      (:event/replaying? event)})
     (try
       (let [result-ictx (run-interceptor-chain ictx (current-interceptors))
             failed?     (or (and (:blocked? result-ictx)
                                  (:validation-error result-ictx))
                             false)]
         (append-trace-entry!
          (cond-> {:trace/kind  (if failed? :dispatch/failed :dispatch/completed)
                   :dispatch-id dispatch-id
                   :session-id  (event-session-id-of result-ictx)
                   :event-type  (event-type-of result-ictx)
                   :blocked?    (boolean (:blocked? result-ictx))
                   :result      (:result result-ictx)}
            (:validation-error result-ictx)
            (assoc :validation-error (:validation-error result-ictx))
            (:block-reason result-ictx)
            (assoc :block-reason (:block-reason result-ictx))
            (:applied-effects result-ictx)
            (assoc :effects (:applied-effects result-ictx))))
         (:result result-ictx))
       (catch Throwable t
         (append-trace-entry! {:trace/kind    :dispatch/failed
                               :dispatch-id   dispatch-id
                               :session-id    session-id
                               :event-type    (:event/type event)
                               :error-message (ex-message t)})
         (throw t))))))
