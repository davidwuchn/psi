(ns psi.rpc.state
  "Explicit helpers for RPC connection-local mutable state.

   Phase 2 uses a nested map shape while preserving compatibility with legacy
   flat test state during migration through read/write fallbacks.")

(def default-max-pending-requests 64)

(defn make-rpc-state
  [{:keys [session-id err max-pending-requests]
    :or   {max-pending-requests default-max-pending-requests}}]
  (atom {:transport
         {:ready? false
          :negotiated-protocol-version nil
          :pending {}
          :max-pending-requests max-pending-requests
          :err err}
         :connection
         {:focus-session-id session-id
          :subscribed-topics #{}
          :event-seq 0}
         :workers
         {:inflight-futures []
          :rpc-run-fn-registered? false
          :ui-watch-loop nil
          :external-event-loop nil}}))

(defn- nested-or-flat
  [nested flat fallback]
  (if (contains? nested flat)
    (get nested flat)
    (or (get fallback flat)
        (when-let [legacy* (get fallback (keyword (str (name flat) "*")))]
          (deref legacy*)))))

(defn- legacy-registered?
  [state]
  (boolean (or (:rpc-run-fn-registered? state)
               (:rpc-run-fn-registered state))))

(defn- normalized-transport-state
  [s err]
  (let [transport-existing (or (:transport s) {})]
    {:ready?                       (if (contains? transport-existing :ready?)
                                     (:ready? transport-existing)
                                     (boolean (:ready? s)))
     :negotiated-protocol-version  (nested-or-flat transport-existing :negotiated-protocol-version s)
     :pending                      (or (nested-or-flat transport-existing :pending s) {})
     :max-pending-requests         (or (nested-or-flat transport-existing :max-pending-requests s)
                                       default-max-pending-requests)
     :err                          err}))

(defn- normalized-connection-state
  [s]
  (let [connection-existing (or (:connection s) {})]
    {:focus-session-id  (nested-or-flat connection-existing :focus-session-id s)
     :subscribed-topics (or (nested-or-flat connection-existing :subscribed-topics s) #{})
     :event-seq         (or (nested-or-flat connection-existing :event-seq s) 0)}))

(defn- normalized-worker-state
  [s]
  (let [workers-existing (or (:workers s) {})]
    {:inflight-futures       (or (nested-or-flat workers-existing :inflight-futures s) [])
     :rpc-run-fn-registered? (if (contains? workers-existing :rpc-run-fn-registered?)
                               (:rpc-run-fn-registered? workers-existing)
                               (legacy-registered? s))
     :ui-watch-loop          (nested-or-flat workers-existing :ui-watch-loop s)
     :external-event-loop    (nested-or-flat workers-existing :external-event-loop s)}))

(defn- apply-compatibility-flat-keys
  [s transport connection workers]
  (assoc s
         :err (:err transport)
         :ready? (:ready? transport)
         :negotiated-protocol-version (:negotiated-protocol-version transport)
         :pending (:pending transport)
         :max-pending-requests (:max-pending-requests transport)
         :focus-session-id (:focus-session-id connection)
         :subscribed-topics (:subscribed-topics connection)
         :event-seq (:event-seq connection)
         :inflight-futures (:inflight-futures workers)
         :rpc-run-fn-registered? (:rpc-run-fn-registered? workers)
         :ui-watch-loop (:ui-watch-loop workers)
         :external-event-loop (:external-event-loop workers)))

(defn initialize-transport-state!
  [state err]
  (swap! state
         (fn [s]
           (let [transport  (normalized-transport-state s err)
                 connection (normalized-connection-state s)
                 workers    (normalized-worker-state s)]
             (-> s
                 (assoc :transport transport
                        :connection connection
                        :workers workers)
                 (apply-compatibility-flat-keys transport connection workers)))))
  state)

(defn err-writer [state]
  (or (get-in @state [:transport :err])
      (:err @state)))

(defn ready? [state]
  (boolean (or (get-in @state [:transport :ready?])
               (:ready? @state))))

(defn mark-ready!
  [state protocol-version]
  (swap! state (fn [s]
                 (-> s
                     (assoc-in [:transport :ready?] true)
                     (assoc-in [:transport :negotiated-protocol-version] protocol-version)
                     ;; compatibility flat keys while tests migrate
                     (assoc :ready? true
                            :negotiated-protocol-version protocol-version))))
  state)

(defn pending [state]
  (or (get-in @state [:transport :pending])
      (:pending @state)
      {}))

(defn max-pending-requests [state]
  (or (get-in @state [:transport :max-pending-requests])
      (:max-pending-requests @state)
      default-max-pending-requests))

(defn add-pending!
  [state id op]
  (swap! state (fn [s]
                 (-> s
                     (assoc-in [:transport :pending id] op)
                     (update :pending (fnil assoc {}) id op))))
  state)

(defn clear-pending!
  [state id]
  (swap! state (fn [s]
                 (-> s
                     (update-in [:transport :pending] (fnil dissoc {}) id)
                     (update :pending (fnil dissoc {}) id))))
  state)

(defn focus-session-id
  [state]
  (or (get-in @state [:connection :focus-session-id])
      (:focus-session-id @state)
      (some-> @state :focus-session-id* deref)))

(defn set-focus-session-id!
  [state session-id]
  (swap! state (fn [s]
                 (-> s
                     (assoc-in [:connection :focus-session-id] session-id)
                     (assoc :focus-session-id session-id))))
  state)

(defn subscribed-topics
  [state]
  (or (get-in @state [:connection :subscribed-topics])
      (:subscribed-topics @state)
      #{}))

(defn subscribe-topics!
  [state topics]
  (swap! state (fn [s]
                 (let [topics' (into (or (get-in s [:connection :subscribed-topics])
                                         (:subscribed-topics s)
                                         #{})
                                     topics)]
                   (-> s
                       (assoc-in [:connection :subscribed-topics] topics')
                       (assoc :subscribed-topics topics')))))
  state)

(defn unsubscribe-topics!
  [state topics]
  (swap! state (fn [s]
                 (let [current (or (get-in s [:connection :subscribed-topics])
                                   (:subscribed-topics s)
                                   #{})
                       next-topics (apply disj current topics)]
                   (-> s
                       (assoc-in [:connection :subscribed-topics] next-topics)
                       (assoc :subscribed-topics next-topics)))))
  state)

(defn clear-subscriptions!
  [state]
  (swap! state (fn [s]
                 (-> s
                     (assoc-in [:connection :subscribed-topics] #{})
                     (assoc :subscribed-topics #{}))))
  state)

(defn next-event-seq!
  [state]
  (let [next-seq (inc (or (get-in @state [:connection :event-seq])
                          (:event-seq @state)
                          0))]
    (swap! state (fn [s]
                   (-> s
                       (assoc-in [:connection :event-seq] next-seq)
                       (assoc :event-seq next-seq))))
    next-seq))

(defn rpc-run-fn-registered?
  [state]
  (boolean (or (get-in @state [:workers :rpc-run-fn-registered?])
               (:rpc-run-fn-registered? @state)
               (:rpc-run-fn-registered @state))))

(defn mark-rpc-run-fn-registered!
  [state]
  (swap! state (fn [s]
                 (-> s
                     (assoc-in [:workers :rpc-run-fn-registered?] true)
                     (assoc :rpc-run-fn-registered? true
                            :rpc-run-fn-registered true))))
  state)

(defn ui-watch-loop
  [state]
  (or (get-in @state [:workers :ui-watch-loop])
      (:ui-watch-loop @state)))

(defn set-ui-watch-loop!
  [state x]
  (swap! state (fn [s]
                 (-> s
                     (assoc-in [:workers :ui-watch-loop] x)
                     (assoc :ui-watch-loop x))))
  state)

(defn external-event-loop
  [state]
  (or (get-in @state [:workers :external-event-loop])
      (:external-event-loop @state)))

(defn set-external-event-loop!
  [state x]
  (swap! state (fn [s]
                 (-> s
                     (assoc-in [:workers :external-event-loop] x)
                     (assoc :external-event-loop x))))
  state)

(defn add-inflight-future!
  [state x]
  (swap! state (fn [s]
                 (-> s
                     (update-in [:workers :inflight-futures] (fnil conj []) x)
                     (update :inflight-futures (fnil conj []) x))))
  state)

(defn worker-handle
  [state worker-k]
  (or (get-in @state [:workers worker-k])
      (get @state worker-k)))

(defn clear-worker!
  [state worker-k]
  (swap! state (fn [s]
                 (-> s
                     (assoc-in [:workers worker-k] nil)
                     (dissoc worker-k))))
  state)

(defn stop-worker!
  [state worker-k]
  (when-let [x (worker-handle state worker-k)]
    (cond
      (instance? java.util.concurrent.Future x)
      (try (future-cancel x) (catch Throwable _ nil))

      (instance? Thread x)
      (try (.interrupt ^Thread x) (catch Throwable _ nil))

      :else nil)
    (clear-worker! state worker-k)
    true))

(defn stop-managed-workers!
  [state]
  (doseq [k [:ui-watch-loop :external-event-loop]]
    (stop-worker! state k)))
