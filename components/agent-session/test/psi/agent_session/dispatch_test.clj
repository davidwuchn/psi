(ns psi.agent-session.dispatch-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.dispatch :as dispatch]))

;; ── Fixture: clean handler registry and event log between tests ─

(defn- clean-state [f]
  (dispatch/clear-handlers!)
  (dispatch/clear-event-log!)
  (dispatch/set-interceptors! nil)
  (try (f)
       (finally
         (dispatch/clear-handlers!)
         (dispatch/clear-event-log!)
         (dispatch/set-interceptors! nil))))

(use-fixtures :each clean-state)

;; ── Handler registration ────────────────────────────────────

(deftest register-handler-test
  (testing "register-handler! adds handler to registry"
    (dispatch/register-handler! :test-event (fn [_ctx _data] :handled))
    (is (contains? (dispatch/registered-event-types) :test-event)))

  (testing "register-handler! replaces existing handler"
    (dispatch/register-handler! :test-event (fn [_ctx _data] :first))
    (dispatch/register-handler! :test-event (fn [_ctx _data] :second))
    (is (= :second (dispatch/dispatch! {} :test-event))))

  (testing "register-handler! with opts still works (opts ignored)"
    (dispatch/register-handler! :classified {} (fn [_ctx _data] :ok))
    (is (= {:fn :present}
           (some-> (dispatch/handler-entry :classified)
                   (update :fn (constantly :present)))))))

(deftest registered-event-types-test
  (testing "returns empty set when no handlers registered"
    (is (= #{} (dispatch/registered-event-types))))

  (testing "returns all registered event types"
    (dispatch/register-handler! :a (fn [_ _] nil))
    (dispatch/register-handler! :b (fn [_ _] nil))
    (is (= #{:a :b} (dispatch/registered-event-types)))))

;; ── Dispatch ────────────────────────────────────────────────

(deftest dispatch-test
  (testing "dispatch! calls registered handler with ctx and event-data"
    (let [calls (atom [])]
      (dispatch/register-handler! :test-event
                                  (fn [ctx data]
                                    (swap! calls conj {:ctx ctx :data data})
                                    :result))
      (let [ctx {:some "context"}
            result (dispatch/dispatch! ctx :test-event {:foo "bar"})]
        (is (= :result result))
        (is (= 1 (count @calls)))
        (is (= {:ctx {:some "context"} :data {:foo "bar"}}
               (first @calls))))))

  (testing "dispatch normalizes a canonical internal event value while preserving compat projections"
    (let [seen (atom nil)
          probe (dispatch/->interceptor
                 {:id :probe
                  :before (fn [ictx]
                            (reset! seen {:event (:event ictx)
                                          :event-type (:event-type ictx)
                                          :event-data (:event-data ictx)
                                          :origin (:origin ictx)
                                          :ext-id (:ext-id ictx)
                                          :replaying? (:replaying? ictx)})
                            ictx)})]
      (dispatch/set-interceptors! [probe dispatch/handler-interceptor])
      (dispatch/register-handler! :normalized (fn [_ _] :ok))
      (is (= :ok (dispatch/dispatch! {} :normalized {:x 1}
                                     {:origin :extension
                                      :ext-id "/ext/test.clj"
                                      :replaying? true})))
      (is (= {:event {:event/type       :normalized
                      :event/data       {:x 1}
                      :event/session-id nil
                      :event/origin     :extension
                      :event/ext-id     "/ext/test.clj"
                      :event/replaying? true}
              :event-type :normalized
              :event-data {:x 1}
              :origin     :extension
              :ext-id     "/ext/test.clj"
              :replaying? true}
             @seen))))

  (testing "dispatch! returns nil for unregistered event type"
    (is (nil? (dispatch/dispatch! {} :unknown-event {:x 1}))))

  (testing "dispatch! with no event-data passes nil"
    (let [received-data (atom :not-called)]
      (dispatch/register-handler! :no-data
                                  (fn [_ctx data]
                                    (reset! received-data data)))
      (dispatch/dispatch! {} :no-data)
      (is (nil? @received-data))))

  (testing "dispatch! with 2-arity call passes nil as event-data"
    (let [received-data (atom :not-called)]
      (dispatch/register-handler! :two-arity
                                  (fn [_ctx data]
                                    (reset! received-data data)))
      (dispatch/dispatch! {} :two-arity)
      (is (nil? @received-data)))))

;; ── Handler receives ctx ────────────────────────────────────

(deftest dispatch-ctx-threading-test
  (testing "handler receives the ctx passed to dispatch!"
    (let [state* (atom {:agent-session {:data {:is-streaming false}}})
          ctx {:state* state*}]
      (dispatch/register-handler! :mark-streaming
                                  (fn [ctx _data]
                                    (swap! (:state* ctx) assoc-in
                                           [:agent-session :data :is-streaming] true)))
      (dispatch/dispatch! ctx :mark-streaming)
      (is (true? (get-in @state* [:agent-session :data :is-streaming]))))))

;; ── Interceptor chain ───────────────────────────────────────

(deftest interceptor-chain-test
  (testing "before fns run in order, after fns in reverse"
    (let [order (atom [])]
      (dispatch/set-interceptors!
       [(dispatch/->interceptor
         {:id :a
          :before (fn [ictx] (swap! order conj :a-before) ictx)
          :after  (fn [ictx] (swap! order conj :a-after) ictx)})
        (dispatch/->interceptor
         {:id :b
          :before (fn [ictx] (swap! order conj :b-before) ictx)
          :after  (fn [ictx] (swap! order conj :b-after) ictx)})])
      (dispatch/register-handler! :test (fn [_ _] nil))
      (dispatch/dispatch! {} :test)
      (is (= [:a-before :b-before :b-after :a-after] @order))))

  (testing "default pure-result sequencing is apply then validate then trim-effects-on-replay then effects"
    (let [order (atom [])
          session-data (atom {:retry-attempt 0})
          apply-fn (fn [_ctx f]
                     (swap! order conj :apply)
                     (swap! session-data f))
          execute-fn (fn [_ctx effect]
                       (swap! order conj [:effect effect]))
          validate-fn (fn [_ctx ictx]
                        (swap! order conj [:validate (:applied-effects ictx) @session-data])
                        {:valid? true})
          trim-probe (dispatch/->interceptor
                      {:id :trim-probe
                       :after (fn [ictx]
                                (swap! order conj [:trim (boolean (:replaying? ictx)) (:applied-effects ictx)])
                                ictx)})
          effects-probe (dispatch/->interceptor
                         {:id :effects-probe
                          :after (fn [ictx]
                                   (swap! order conj [:effects-stage (:applied-effects ictx)])
                                   ictx)})
          apply-probe (dispatch/->interceptor
                       {:id :apply-probe
                        :after (fn [ictx]
                                 (swap! order conj [:apply-stage (:applied-effects ictx) @session-data])
                                 ictx)})
          ctx {:apply-root-state-update-fn apply-fn
               :execute-dispatch-effect-fn execute-fn
               :validate-dispatch-result-fn validate-fn}]
      (dispatch/set-interceptors!
       [dispatch/permission-interceptor
        dispatch/log-interceptor
        dispatch/handler-interceptor
        dispatch/effect-interceptor
        effects-probe
        dispatch/trim-effects-on-replay
        trim-probe
        dispatch/validate-interceptor
        apply-probe
        dispatch/apply-interceptor])
      (dispatch/register-handler!
       :ordered-pure
       (fn [_ctx _data]
         {:root-state-update #(update % :retry-attempt inc)
          :effects [{:effect/type :schedule}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :ordered-pure)))
      (is (= [:apply
              [:apply-stage [{:effect/type :schedule}] {:retry-attempt 1}]
              [:validate [{:effect/type :schedule}] {:retry-attempt 1}]
              [:trim false [{:effect/type :schedule}]]
              [:effects-stage [{:effect/type :schedule}]]
              [:effect {:effect/type :schedule}]]
             @order))))

  (testing "root-state-update pure-result sequencing applies before validate and effects"
    (let [order (atom [])
          root-state (atom {:agent-session {:data {:session-name "before"}}})
          apply-root-fn (fn [_ctx f]
                          (let [new-state (swap! root-state f)]
                            (swap! order conj [:root-update (get-in new-state [:agent-session :data :session-name])])))
          execute-fn (fn [_ctx effect]
                       (swap! order conj [:effect effect]))
          validate-fn (fn [_ctx ictx]
                        (swap! order conj [:validate (:applied-effects ictx) @root-state])
                        {:valid? true})
          trim-probe (dispatch/->interceptor
                      {:id :trim-probe
                       :after (fn [ictx]
                                (swap! order conj [:trim (boolean (:replaying? ictx)) (:applied-effects ictx)])
                                ictx)})
          effects-probe (dispatch/->interceptor
                         {:id :effects-probe
                          :after (fn [ictx]
                                   (swap! order conj [:effects-stage (:applied-effects ictx)])
                                   ictx)})
          apply-probe (dispatch/->interceptor
                       {:id :apply-probe
                        :after (fn [ictx]
                                 (swap! order conj [:apply-stage (:applied-effects ictx) @root-state])
                                 ictx)})
          ctx {:apply-root-state-update-fn apply-root-fn
               :execute-dispatch-effect-fn execute-fn
               :validate-dispatch-result-fn validate-fn}]
      (dispatch/set-interceptors!
       [dispatch/permission-interceptor
        dispatch/log-interceptor
        dispatch/statechart-interceptor
        dispatch/handler-interceptor
        dispatch/effect-interceptor
        effects-probe
        dispatch/trim-effects-on-replay
        trim-probe
        dispatch/validate-interceptor
        apply-probe
        dispatch/apply-interceptor])
      (dispatch/register-handler!
       :ordered-root-update
       (fn [_ctx _data]
         {:root-state-update #(assoc-in % [:agent-session :data :session-name] "after")
          :effects [{:effect/type :notify}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :ordered-root-update)))
      (is (= [[:root-update "after"]
              [:apply-stage [{:effect/type :notify}] {:agent-session {:data {:session-name "after"}}}]
              [:validate [{:effect/type :notify}] {:agent-session {:data {:session-name "after"}}}]
              [:trim false [{:effect/type :notify}]]
              [:effects-stage [{:effect/type :notify}]]
              [:effect {:effect/type :notify}]]
             @order))))

  (testing "statechart interceptor can claim an event and skip the handler stage"
    (dispatch/set-interceptors! nil)
    (let [calls (atom [])
          ctx {:dispatch-statechart-event-fn
               (fn [_ctx event-type event-data _ictx]
                 (swap! calls conj [:statechart event-type event-data])
                 {:claimed? true :result :claimed})}]
      (dispatch/register-handler! :claimed-event (fn [_ _] (swap! calls conj [:handler]) :handler-result))
      (is (= :claimed (dispatch/dispatch! ctx :claimed-event {:x 1})))
      (is (= [[:statechart :claimed-event {:x 1}]] @calls))))

  (testing "blocked context skips subsequent before fns"
    (let [order (atom [])]
      (dispatch/set-interceptors!
       [(dispatch/->interceptor
         {:id :blocker
          :before (fn [ictx]
                    (swap! order conj :blocker)
                    (assoc ictx :blocked? true :block-reason :test-block))})
        (dispatch/->interceptor
         {:id :skipped
          :before (fn [ictx]
                    (swap! order conj :skipped)
                    ictx)})])
      (dispatch/register-handler! :test (fn [_ _] :should-not-run))
      (let [result (dispatch/dispatch! {} :test)]
        (is (nil? result))
        (is (= [:blocker] @order)))))

  (testing "after fns still run when blocked"
    (let [after-ran? (atom false)]
      (dispatch/set-interceptors!
       [(dispatch/->interceptor
         {:id :with-after
          :after (fn [ictx] (reset! after-ran? true) ictx)})
        (dispatch/->interceptor
         {:id :blocker
          :before (fn [ictx] (assoc ictx :blocked? true))})])
      (dispatch/register-handler! :test (fn [_ _] nil))
      (dispatch/dispatch! {} :test)
      (is (true? @after-ran?)))))

;; ── Event log ───────────────────────────────────────────────

(deftest event-log-test
  (testing "dispatch writes event log entries"
    (dispatch/register-handler! :logged-event (fn [_ _] :ok))
    (dispatch/dispatch! {} :logged-event {:x 1})
    (let [entries (dispatch/event-log-entries)]
      (is (= 1 (count entries)))
      (let [entry (first entries)]
        (is (= :logged-event (:event-type entry)))
        (is (= {:x 1} (:event-data entry)))
        (is (= :core (:origin entry)))
        (is (false? (:replaying? entry)))
        (is (false? (:blocked? entry)))
        (is (number? (:timestamp entry)))
        (is (number? (:duration-ms entry))))))

  (testing "unregistered events are still logged"
    (dispatch/clear-event-log!)
    (dispatch/dispatch! {} :no-handler {:y 2})
    (let [entries (dispatch/event-log-entries)]
      (is (= 1 (count entries)))
      (is (= :no-handler (:event-type (first entries))))))

  (testing "blocked events are logged with blocked? true"
    (dispatch/clear-event-log!)
    (dispatch/set-interceptors!
     [(dispatch/->interceptor
       {:id :blocker
        :before (fn [ictx] (assoc ictx :blocked? true))})
      dispatch/log-interceptor
      dispatch/handler-interceptor])
    (dispatch/register-handler! :blocked (fn [_ _] nil))
    (dispatch/dispatch! {} :blocked)
    (let [entries (dispatch/event-log-entries)]
      (is (= 1 (count entries)))
      (is (true? (:blocked? (first entries))))))

  (testing "event log is bounded"
    ;; Use a chain with only handler (skip log) then log manually
    ;; to avoid testing the bound indirectly. Instead, test directly.
    (dispatch/clear-event-log!)
    (dispatch/register-handler! :bulk (fn [_ _] nil))
    (dotimes [i 1005]
      (dispatch/dispatch! {} :bulk {:i i}))
    (is (<= (count (dispatch/event-log-entries)) 1000))))

;; ── Default interceptors ────────────────────────────────────

(deftest default-interceptors-test
  (testing "default chain includes permission, log, statechart, handler, effects, trim-effects-on-replay, validate, and apply interceptors"
    (let [ids (mapv :id dispatch/default-interceptors)]
      (is (= [:permission :log :statechart :handler :effects :trim-effects-on-replay :validate :apply] ids))))

  (testing "current-interceptors returns defaults when no override"
    (is (= dispatch/default-interceptors (dispatch/current-interceptors))))

  (testing "set-interceptors! overrides the chain"
    (let [custom [(dispatch/->interceptor {:id :custom})]]
      (dispatch/set-interceptors! custom)
      (is (= custom (dispatch/current-interceptors)))))

  (testing "set-interceptors! nil restores defaults"
    (dispatch/set-interceptors! [(dispatch/->interceptor {:id :temp})])
    (dispatch/set-interceptors! nil)
    (is (= dispatch/default-interceptors (dispatch/current-interceptors)))))

;; ── Permission interceptor ───────────────────────────────────

(defn- make-test-registry
  "Create a minimal extension registry.
   Input may be a seq of paths or a seq of [path ext-record] pairs."
  [exts]
  (let [entries (map (fn [x]
                       (if (vector? x)
                         x
                         [x {:path x}]))
                     exts)
        extensions (into {} entries)]
    {:state (atom {:extensions extensions
                   :registration-order (mapv first entries)})}))

(deftest permission-interceptor-test
  (testing "core origin bypasses permission check"
    (dispatch/register-handler! :core-event (fn [_ _] :ok))
    (is (= :ok (dispatch/dispatch! {} :core-event nil {:origin :core}))))

  (testing "statechart origin bypasses permission check"
    (dispatch/register-handler! :sc-event (fn [_ _] :ok))
    (is (= :ok (dispatch/dispatch! {} :sc-event nil {:origin :statechart}))))

  (testing "adapter origin bypasses permission check"
    (dispatch/register-handler! :adapter-event (fn [_ _] :ok))
    (is (= :ok (dispatch/dispatch! {} :adapter-event nil {:origin :adapter}))))

  (testing "extension origin with registered ext-id and no manifest allowed-events is compatibility-allowed"
    (let [reg (make-test-registry [["/ext/a.clj" {:path "/ext/a.clj"}]])
          ctx {:extension-registry reg}]
      (dispatch/register-handler! :ext-event (fn [_ _] :allowed))
      (is (= :allowed (dispatch/dispatch! ctx :ext-event nil
                                          {:origin :extension
                                           :ext-id "/ext/a.clj"})))))

  (testing "extension origin with allowed manifest event is allowed"
    (let [reg (make-test-registry [["/ext/a.clj" {:path "/ext/a.clj"
                                                  :allowed-events #{:ext-event}}]])
          ctx {:extension-registry reg}]
      (dispatch/register-handler! :ext-event (fn [_ _] :allowed))
      (is (= :allowed (dispatch/dispatch! ctx :ext-event nil
                                          {:origin :extension
                                           :ext-id "/ext/a.clj"})))))

  (testing "extension origin with disallowed manifest event is blocked"
    (let [reg (make-test-registry [["/ext/a.clj" {:path "/ext/a.clj"
                                                  :allowed-events #{:other-event}}]])
          ctx {:extension-registry reg}]
      (dispatch/register-handler! :ext-event (fn [_ _] :should-not-run))
      (dispatch/clear-event-log!)
      (is (nil? (dispatch/dispatch! ctx :ext-event nil
                                    {:origin :extension
                                     :ext-id "/ext/a.clj"})))
      (let [entry (last (dispatch/event-log-entries))]
        (is (true? (:blocked? entry)))
        (is (= {:reason :permission-denied
                :event-type :ext-event
                :ext-id "/ext/a.clj"}
               (:block-reason entry))))))

  (testing "extension origin with unknown ext-id is blocked"
    (let [reg (make-test-registry ["/ext/a.clj"])
          ctx {:extension-registry reg}]
      (dispatch/register-handler! :ext-event (fn [_ _] :should-not-run))
      (dispatch/clear-event-log!)
      (is (nil? (dispatch/dispatch! ctx :ext-event nil
                                    {:origin :extension
                                     :ext-id "/ext/unknown.clj"})))
      (let [entry (last (dispatch/event-log-entries))]
        (is (true? (:blocked? entry)))
        (is (= :unknown-extension (:block-reason entry))))))

  (testing "extension origin with nil ext-id is blocked"
    (dispatch/register-handler! :ext-event (fn [_ _] :should-not-run))
    (is (nil? (dispatch/dispatch! {} :ext-event nil
                                  {:origin :extension
                                   :ext-id nil}))))

  (testing "extension origin with no registry on ctx is blocked"
    (dispatch/register-handler! :ext-event (fn [_ _] :should-not-run))
    (is (nil? (dispatch/dispatch! {} :ext-event nil
                                  {:origin :extension
                                   :ext-id "/ext/a.clj"})))))

;; ── Origin in log entries ───────────────────────────────────

(deftest startup-bootstrap-dispatch-test
  (let [[ctx seed-id]      (session/create-context {:persist? false})
        sd                 (session/new-session-in! ctx seed-id {})
        session-id         (:session-id sd)
        started-at         (java.time.Instant/now)
        completed-at       (java.time.Instant/now)
        prompts            [{:id "s1" :source :project :phase :system-bootstrap :priority 1}]
        summary            {:timestamp completed-at :prompt-count 1}]
    (testing "startup lifecycle helpers route writes through dispatch handlers"
      (dispatch/dispatch! ctx :session/startup-bootstrap-begin {:session-id session-id :started-at started-at} {:origin :core})
      (dispatch/dispatch! ctx :session/record-startup-message-id {:session-id session-id :message-id "m1"} {:origin :core})
      (dispatch/dispatch! ctx :session/startup-bootstrap-complete {:session-id session-id :startup-prompts prompts :completed-at completed-at} {:origin :core})
      (dispatch/dispatch! ctx :session/set-startup-bootstrap-summary {:session-id session-id :summary summary} {:origin :core})
      (let [sd         (ss/get-session-data-in ctx session-id)
            entries     (dispatch/event-log-entries)
            event-types (mapv :event-type entries)]
        (is (= started-at (:startup-bootstrap-started-at sd)))
        (is (true? (:startup-bootstrap-completed? sd)))
        (is (= completed-at (:startup-bootstrap-completed-at sd)))
        (is (= ["m1"] (:startup-message-ids sd)))
        (is (= prompts (:startup-prompts sd)))
        (is (= summary (:startup-bootstrap sd)))
        (is (= [:session/new-initialize
                :session/retarget-runtime-prompt-metadata
                :session/startup-bootstrap-begin
                :session/record-startup-message-id
                :session/startup-bootstrap-complete
                :session/set-startup-bootstrap-summary]
               event-types))))))

(deftest origin-logged-test
  (testing "log entry captures origin"
    (dispatch/register-handler! :origin-test (fn [_ _] nil))
    (dispatch/dispatch! {} :origin-test nil {:origin :statechart})
    (let [entry (last (dispatch/event-log-entries))]
      (is (= :statechart (:origin entry)))))

  (testing "log entry captures ext-id for extension origin"
    (dispatch/clear-event-log!)
    (let [reg (make-test-registry ["/ext/a.clj"])
          ctx {:extension-registry reg}]
      (dispatch/register-handler! :ext-log (fn [_ _] nil))
      (dispatch/dispatch! ctx :ext-log nil {:origin :extension :ext-id "/ext/a.clj"})
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :extension (:origin entry)))
        (is (= "/ext/a.clj" (:ext-id entry)))))))

;; ── Pure handler results ─────────────────────────────────────

(deftest pure-result-detection-test
  (testing "map with :db is a pure result"
    (is (true? (dispatch/pure-result? {:db {} :effects []}))))

  (testing "map with :root-state-update is a pure result"
    (is (true? (dispatch/pure-result? {:root-state-update identity}))))

  (testing "map with :effects is a pure result"
    (is (true? (dispatch/pure-result? {:effects []}))))

  (testing "map with both is a pure result"
    (is (true? (dispatch/pure-result? {:root-state-update identity :effects []}))))

  (testing "map with :return-key is a pure result"
    (is (true? (dispatch/pure-result? {:return-key [:agent-session :data]}))))

  (testing "nil is not a pure result"
    (is (false? (dispatch/pure-result? nil))))

  (testing "plain keyword is not a pure result"
    (is (false? (dispatch/pure-result? :ok))))

  (testing "map without session-update or effects is not a pure result"
    (is (false? (dispatch/pure-result? {:foo :bar})))))

(deftest pure-handler-apply-test
  (testing "pure handler session-update is applied via ctx fn"
    (let [session-data (atom {:is-streaming false})
          apply-fn (fn [_ctx f] (swap! session-data f))
          ctx {:apply-root-state-update-fn apply-fn}]
      (dispatch/register-handler!
       :go-streaming
       (fn [_ctx _data]
         {:root-state-update #(assoc % :is-streaming true)}))
      (dispatch/dispatch! ctx :go-streaming)
      (is (true? (:is-streaming @session-data)))))

  (testing "pure handler effects are executed via ctx fn"
    (let [seen-effects (atom [])
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          ctx {:execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :effects-only
       (fn [_ctx _data]
         {:effects [{:effect/type :notify} {:effect/type :log}]}))
      (dispatch/dispatch! ctx :effects-only)
      (is (= [{:effect/type :notify} {:effect/type :log}] @seen-effects))))

  (testing "pure handler can apply session update and execute effects"
    (let [session-data (atom {:retry-attempt 0})
          seen-effects (atom [])
          apply-fn (fn [_ctx f] (swap! session-data f))
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          ctx {:apply-root-state-update-fn apply-fn
               :execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :update-and-effect
       (fn [_ctx _data]
         {:root-state-update #(update % :retry-attempt inc)
          :effects [{:effect/type :schedule}]}))
      (dispatch/dispatch! ctx :update-and-effect)
      (is (= 1 (:retry-attempt @session-data)))
      (is (= [{:effect/type :schedule}] @seen-effects))))

  (testing "pure handler can return the first effect result when opted in"
    (let [execute-fn (fn [_ctx effect]
                       (case (:effect/type effect)
                         :runtime/tool-run {:role "toolResult"
                                            :tool-call-id "call-1"
                                            :tool-name "read"
                                            :content [{:type :text :text "ok"}]}
                         :ignored))
          ctx {:execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :effect-result-test
       (fn [_ctx _data]
         {:effects [{:effect/type :runtime/tool-run}
                    {:effect/type :ignored}]
          :return-effect-result? true}))
      (is (= {:role "toolResult"
              :tool-call-id "call-1"
              :tool-name "read"
              :content [{:type :text :text "ok"}]}
             (dispatch/dispatch! ctx :effect-result-test)))))

  (testing "replay suppresses pure handler effects while preserving state application"
    (let [session-data (atom {:retry-attempt 0})
          seen-effects (atom [])
          apply-fn (fn [_ctx f] (swap! session-data f))
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          ctx {:apply-root-state-update-fn apply-fn
               :execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :replay-update-and-effect
       (fn [_ctx _data]
         {:root-state-update #(update % :retry-attempt inc)
          :effects [{:effect/type :schedule}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :replay-update-and-effect nil {:replaying? true})))
      (is (= 1 (:retry-attempt @session-data)))
      (is (= [] @seen-effects))))

  (testing "replay suppresses effects-only pure handler execution"
    (let [seen-effects (atom [])
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          ctx {:execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :replay-effects-only
       (fn [_ctx _data]
         {:effects [{:effect/type :notify}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :replay-effects-only nil {:replaying? true})))
      (is (= [] @seen-effects))))

  (testing "retained event can be redispatched with replay semantics"
    (let [session-data (atom {:session-name "before"})
          seen-effects (atom [])
          apply-fn     (fn [_ctx f] (swap! session-data f))
          execute-fn   (fn [_ctx effect] (swap! seen-effects conj effect))
          ctx          {:apply-root-state-update-fn apply-fn
                        :execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :session/set-session-name
       (fn [_ctx {:keys [name]}]
         {:root-state-update #(assoc % :session-name name)
          :effects [{:effect/type :persist/journal-append-session-info-entry
                     :name name}]
          :return :ok}))
      (dispatch/dispatch! ctx :session/set-session-name {:name "after"})
      (let [entry (last (dispatch/event-log-entries))]
        (reset! session-data {:session-name "before"})
        (reset! seen-effects [])
        (is (= :ok (dispatch/replay-event-entry! ctx entry)))
        (is (= "after" (:session-name @session-data)))
        (is (= [] @seen-effects)))))

  (testing "replay-event-log! replays entries in order"
    (dispatch/clear-event-log!)
    (let [session-data (atom {:session-name "before"
                              :worktree-path "/repo/main"})
          seen-effects (atom [])
          apply-fn     (fn [_ctx f] (swap! session-data f))
          execute-fn   (fn [_ctx effect] (swap! seen-effects conj effect))
          ctx          {:apply-root-state-update-fn apply-fn
                        :execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :session/set-session-name
       (fn [_ctx {:keys [name]}]
         {:root-state-update #(assoc % :session-name name)
          :effects [{:effect/type :persist/journal-append-session-info-entry
                     :name name}]
          :return :name-updated}))
      (dispatch/register-handler!
       :session/set-worktree-path
       (fn [_ctx {:keys [worktree-path]}]
         {:root-state-update #(assoc % :worktree-path worktree-path)
          :return :path-updated}))
      (dispatch/dispatch! ctx :session/set-session-name {:name "after"})
      (dispatch/dispatch! ctx :session/set-worktree-path {:worktree-path "/repo/feature"})
      (let [entries (dispatch/event-log-entries)]
        (reset! session-data {:session-name "before"
                              :worktree-path "/repo/main"})
        (reset! seen-effects [])
        (is (= [:name-updated :path-updated]
               (dispatch/replay-event-log! ctx entries)))
        (is (= {:session-name "after"
                :worktree-path "/repo/feature"}
               @session-data))
        (is (= [] @seen-effects)))))

  (testing "legacy handler result is not applied as pure"
    (let [applied? (atom false)
          apply-fn (fn [_ctx _f] (reset! applied? true))
          ctx {:apply-root-state-update-fn apply-fn}]
      (dispatch/register-handler!
       :legacy
       (fn [_ctx _data] :legacy-result))
      (let [result (dispatch/dispatch! ctx :legacy)]
        (is (= :legacy-result result))
        (is (false? @applied?)))))

  (testing "pure handler without apply-fn on ctx does not crash"
    (dispatch/register-handler!
     :no-apply-fn
     (fn [_ctx _data]
       {:root-state-update #(assoc % :x true)}))
    (is (nil? (dispatch/dispatch! {} :no-apply-fn))))

  (testing "pure handler can return a post-update value via :return-key"
    (let [session-data (atom {:agent-session {:data {:session-name "before"}}})
          apply-fn     (fn [_ctx f] (swap! session-data f))
          read-fn      (fn [_ctx path] (get-in @session-data path))
          ctx          {:apply-root-state-update-fn apply-fn
                        :read-session-state-fn   read-fn}]
      (dispatch/register-handler!
       :return-key-test
       (fn [_ctx _data]
         {:root-state-update #(assoc-in % [:agent-session :data :session-name] "after")
          :return-key     [:agent-session :data]}))
      (is (= {:session-name "after"}
             (dispatch/dispatch! ctx :return-key-test)))))

  (testing "pure handler can apply a root-state update"
    (let [root-state (atom {:runtime {:nrepl nil}})
          apply-root-fn (fn [_ctx f] (swap! root-state f))
          ctx {:apply-root-state-update-fn apply-root-fn}]
      (dispatch/register-handler!
       :root-update-test
       (fn [_ctx _data]
         {:root-state-update #(assoc-in % [:runtime :nrepl] {:port 8888})}))
      (dispatch/dispatch! ctx :root-update-test)
      (is (= {:port 8888} (get-in @root-state [:runtime :nrepl])))))

  (testing "pure handler root-state-update can return a post-update value via :return-key"
    (let [root-state (atom {:agent-session {:data {:session-name "before"}}})
          apply-root-fn (fn [_ctx f] (swap! root-state f))
          read-fn (fn [_ctx path] (get-in @root-state path))
          ctx {:apply-root-state-update-fn apply-root-fn
               :read-session-state-fn read-fn}]
      (dispatch/register-handler!
       :root-update-return-key-test
       (fn [_ctx _data]
         {:root-state-update #(assoc-in % [:agent-session :data :session-name] "after")
          :return-key [:agent-session :data]}))
      (is (= {:session-name "after"}
             (dispatch/dispatch! ctx :root-update-return-key-test))))))

;; ── Handler exception safety ────────────────────────────────

(deftest validation-interceptor-test
  (testing "validation hook can block dispatch after apply and before effects"
    (let [session-data (atom {:retry-attempt 0})
          seen-effects (atom [])
          apply-fn (fn [_ctx f] (swap! session-data f))
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          validate-fn (fn [_ctx _ictx] {:valid? false :reason :test-invalid})
          ctx {:apply-root-state-update-fn apply-fn
               :execute-dispatch-effect-fn execute-fn
               :validate-dispatch-result-fn validate-fn}]
      (dispatch/register-handler!
       :invalid-after-apply
       (fn [_ctx _data]
         {:root-state-update #(update % :retry-attempt inc)
          :effects [{:effect/type :schedule}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :invalid-after-apply)))
      (is (= 1 (:retry-attempt @session-data)))
      (is (= [] @seen-effects))
      (let [entry (last (dispatch/event-log-entries))]
        (is (true? (:blocked? entry)))
        (is (= :test-invalid (:block-reason entry))))))

  (testing "validation hook default false/nil result blocks dispatch"
    (let [seen-effects (atom [])
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          validate-fn (fn [_ctx _ictx] false)
          ctx {:execute-dispatch-effect-fn execute-fn
               :validate-dispatch-result-fn validate-fn}]
      (dispatch/register-handler!
       :invalid-effects-only
       (fn [_ctx _data]
         {:effects [{:effect/type :notify}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :invalid-effects-only)))
      (is (= [] @seen-effects))
      (let [entry (last (dispatch/event-log-entries))]
        (is (true? (:blocked? entry)))
        (is (= :validation-failed (:block-reason entry))))))

  (testing "validation hook truthy result allows effect execution"
    (let [seen-effects (atom [])
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          validate-fn (fn [_ctx _ictx] {:valid? true})
          ctx {:execute-dispatch-effect-fn execute-fn
               :validate-dispatch-result-fn validate-fn}]
      (dispatch/register-handler!
       :valid-effects-only
       (fn [_ctx _data]
         {:effects [{:effect/type :notify}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :valid-effects-only)))
      (is (= [{:effect/type :notify}] @seen-effects))))

  (testing "validator exception blocks effects with structured reason"
    (let [seen-effects (atom [])
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          validate-fn (fn [_ctx _ictx] (throw (ex-info "validator boom" {})))
          ctx {:execute-dispatch-effect-fn execute-fn
               :validate-dispatch-result-fn validate-fn}]
      (dispatch/register-handler!
       :validator-throws
       (fn [_ctx _data]
         {:effects [{:effect/type :notify}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :validator-throws)))
      (is (= [] @seen-effects))
      (let [entry (last (dispatch/event-log-entries))]
        (is (true? (:blocked? entry)))
        (is (= :validator-exception (get-in entry [:block-reason :type])))))))

(deftest handler-exception-test
  (testing "handler exception is caught and returns nil"
    (dispatch/register-handler! :throws
                                (fn [_ _] (throw (ex-info "boom" {}))))
    (is (nil? (dispatch/dispatch! {} :throws)))
    ;; Event is still logged
    (is (= 1 (count (dispatch/event-log-entries))))))

;;; Schema validation

(deftest schema-validation-test
  ;; validate-dispatch-schemas wired via *assert* gate —
  ;; passes valid pure-results, blocks invalid shapes/effects
  (testing "validate-dispatch-schemas"
    (testing "passes a valid pure-result with effects"
      (let [seen-effects (atom [])
            execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
            ctx {:execute-dispatch-effect-fn execute-fn
                 :validate-dispatch-result-fn dispatch/validate-dispatch-schemas}]
        (dispatch/register-handler!
         :schema-valid
         (fn [_ctx _data]
           {:effects [{:effect/type :runtime/agent-abort}]
            :return :ok}))
        (is (= :ok (dispatch/dispatch! ctx :schema-valid)))
        (is (= [{:effect/type :runtime/agent-abort}] @seen-effects))))

    (testing "passes a valid root-state-update with effects"
      (let [root-state (atom {})
            seen-effects (atom [])
            apply-fn (fn [_ctx f] (swap! root-state f))
            execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
            ctx {:apply-root-state-update-fn apply-fn
                 :execute-dispatch-effect-fn execute-fn
                 :validate-dispatch-result-fn dispatch/validate-dispatch-schemas}]
        (dispatch/register-handler!
         :schema-valid-update
         (fn [_ctx _data]
           {:root-state-update #(assoc % :done true)
            :effects [{:effect/type :statechart/send-event :event :go}]}))
        (dispatch/dispatch! ctx :schema-valid-update)
        (is (true? (:done @root-state)))
        (is (= [{:effect/type :statechart/send-event :event :go}] @seen-effects))))

    (testing "blocks dispatch when effect has unknown type"
      (let [seen-effects (atom [])
            execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
            ctx {:execute-dispatch-effect-fn execute-fn
                 :validate-dispatch-result-fn dispatch/validate-dispatch-schemas}]
        (dispatch/register-handler!
         :schema-bad-effect
         (fn [_ctx _data]
           {:effects [{:effect/type :nonexistent/effect}]
            :return :ok}))
        (is (= :ok (dispatch/dispatch! ctx :schema-bad-effect)))
        (is (= [] @seen-effects)
            "effects suppressed on schema failure")
        (let [entry (last (dispatch/event-log-entries))]
          (is (true? (:blocked? entry)))
          (is (= :schema-validation-failed
                 (get-in entry [:block-reason :type]))))))

    (testing "blocks dispatch when effect missing required key"
      (let [seen-effects (atom [])
            execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
            ctx {:execute-dispatch-effect-fn execute-fn
                 :validate-dispatch-result-fn dispatch/validate-dispatch-schemas}]
        (dispatch/register-handler!
         :schema-missing-key
         (fn [_ctx _data]
           ;; :runtime/agent-queue-steering requires :message
           {:effects [{:effect/type :runtime/agent-queue-steering}]
            :return :ok}))
        (is (= :ok (dispatch/dispatch! ctx :schema-missing-key)))
        (is (= [] @seen-effects))
        (let [entry (last (dispatch/event-log-entries))]
          (is (true? (:blocked? entry)))
          (is (= :schema-validation-failed
                 (get-in entry [:block-reason :type]))))))

    (testing "passes when handler returns non-pure result"
      (let [ctx {:validate-dispatch-result-fn dispatch/validate-dispatch-schemas}]
        (dispatch/register-handler!
         :schema-non-pure
         (fn [_ctx _data] "plain-value"))
        (is (= "plain-value" (dispatch/dispatch! ctx :schema-non-pure)))))))
