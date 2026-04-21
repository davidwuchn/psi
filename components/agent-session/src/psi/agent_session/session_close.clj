(ns psi.agent-session.session-close
  "Per-session close lifecycle helpers.

   Owns targeted session removal from the in-memory context, including
   scheduler timer cleanup and projected active-session fallback.
   Persistence files are preserved; close is runtime detachment, not deletion."
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as ss]))

(defn- next-active-session-id
  [ctx closing-session-id]
  (some->> (ss/list-context-sessions-in ctx)
           (remove #(= closing-session-id (:session-id %)))
           last
           :session-id))

(defn- interrupt-scheduler-timer!
  [ctx schedule-id]
  (when-let [timers* (:scheduler-timers* ctx)]
    (when-let [thread (get @timers* schedule-id)]
      (try
        (.interrupt ^Thread thread)
        (catch Exception _
          nil))
      (swap! timers* dissoc schedule-id)
      true)))

(defn- cancel-owned-schedules!
  [ctx session-id]
  (doseq [[schedule-id schedule]
          (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules] {})]
    (when (contains? #{:pending :queued} (:status schedule))
      (dispatch/dispatch! ctx
                          :scheduler/cancel
                          {:session-id session-id
                           :schedule-id schedule-id}
                          {:origin :core})))
  true)

(defn- remove-session-state!
  [ctx session-id]
  (swap! (:state* ctx) update-in [:agent-session :sessions] dissoc session-id)
  true)

(defn close-session-in!
  "Detach a single session from the in-memory context.

   Effects:
   - cancels any pending/queued scheduler entries owned by the session
   - interrupts/removes any remaining timer handles for owned schedules
   - removes the session runtime slot from canonical root state
   - emits a context-changed projection with the fallback active-session-id

   Returns {:closed? bool :session-id sid :active-session-id sid-or-nil}."
  [ctx session-id]
  (when-not (ss/get-session-data-in ctx session-id)
    (throw (ex-info "session id not found in context session index"
                    {:error-code "request/not-found"
                     :session-id session-id})))
  (let [owned-schedule-ids (->> (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules] {})
                                keys
                                vec)
        _                  (cancel-owned-schedules! ctx session-id)
        _                  (doseq [schedule-id owned-schedule-ids]
                             (interrupt-scheduler-timer! ctx schedule-id))
        _                  (remove-session-state! ctx session-id)
        active-session-id  (next-active-session-id ctx session-id)]
    (dispatch/dispatch! ctx
                        :session/context-closed
                        {:session-id session-id
                         :active-session-id active-session-id}
                        {:origin :core})
    {:closed? true
     :session-id session-id
     :active-session-id active-session-id}))
