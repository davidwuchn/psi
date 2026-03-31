(ns psi.rpc.session.streams
  "Shared progress/event stream lifecycle helpers for RPC session workflows."
  (:require
   [psi.rpc.events :as events]
   [psi.rpc.session.emit :as emit]))

(defn start-progress-loop!
  [{:keys [start-daemon-thread! ctx session-id emit! progress-q thread-name]
    :or   {thread-name "rpc-progress-loop"}}]
  (let [stop? (atom false)
        thread (start-daemon-thread!
                (fn []
                  (loop []
                    (when-not @stop?
                      (when-let [evt (.poll progress-q 10 java.util.concurrent.TimeUnit/MILLISECONDS)]
                        (when-let [{:keys [event data]} (events/progress-event->rpc-event evt)]
                          (emit! event data)
                          (when (= :tool-result (:event-kind evt))
                            (emit/emit-footer-updated! emit! ctx session-id)))
                        (loop []
                          (when-let [more (.poll progress-q)]
                            (when-let [{:keys [event data]} (events/progress-event->rpc-event more)]
                              (emit! event data)
                              (when (= :tool-result (:event-kind more))
                                (emit/emit-footer-updated! emit! ctx session-id)))
                            (recur))))
                      (recur))))
                thread-name)]
    {:stop? stop?
     :thread thread}))

(defn stop-progress-loop!
  [{:keys [stop? thread progress-q emit!]}]
  (reset! stop? true)
  (.join ^Thread thread 200)
  (events/emit-progress-queue! progress-q emit!))
