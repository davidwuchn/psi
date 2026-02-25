(ns psi.ai.streaming
  "Streaming response management and event handling.

   Streams are callback-based: callers supply a `consume-fn` that is
   invoked for every event on a background thread.  No core.async
   channels are used.

   Alternatively, `stream-response-seq` returns a lazy sequence of events
   via a `java.util.concurrent.LinkedBlockingQueue`; the background thread
   drains into the queue while the caller drains it from the seq side.
   "
  (:import [java.util UUID]
           [java.time Instant]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; ───────────────────────────────────────────────────────────────────────────
;; Stream session helpers
;; ───────────────────────────────────────────────────────────────────────────

(defn create-stream-session
  "Create new streaming session."
  [conversation model options]
  {:id              (str (UUID/randomUUID))
   :conversation-id (:id conversation)
   :model           model
   :status          :starting
   :started-at      (Instant/now)
   :completed-at    nil
   :temperature     (:temperature options)
   :max-tokens      (:max-tokens options)
   :cache-retention (get options :cache-retention :short)
   :events          []})

(defn add-event
  "Add event to streaming session."
  [session event-type & {:keys [content-index delta-text error-message]}]
  (let [event {:sequence      (count (:events session))
               :event-type    event-type
               :timestamp     (Instant/now)
               :content-index content-index
               :delta-text    delta-text
               :error-message error-message}]
    (update session :events conj event)))

(defn complete-session
  "Mark session as completed."
  [session]
  (assoc session
         :status       :completed
         :completed-at (Instant/now)))

(defn fail-session
  "Mark session as failed."
  [session error-msg]
  (assoc session
         :status        :failed
         :completed-at  (Instant/now)
         :error-message error-msg))

;; ───────────────────────────────────────────────────────────────────────────
;; Callback-based streaming (preferred)
;; ───────────────────────────────────────────────────────────────────────────

(defn stream-response
  "Stream assistant response, calling `consume-fn` for every event.

   Runs the provider on a background thread (via `future`).  Returns a
   map of:
     :future  — the java.util.concurrent.Future for the background work
     :session — atom holding the evolving StreamSession

   The `consume-fn` receives event maps matching the provider's schema.
   When streaming is done (`:done` or `:error` event), the future completes.

   Example:
     (stream-response provider-impl conversation model options
       (fn [event]
         (case (:type event)
           :text-delta (print (:delta event))
           :done       (println \"\n[done]\")
           nil)))
   "
  [provider-impl conversation model options consume-fn]
  (let [session (atom (create-stream-session conversation model options))]
    {:future
     (future
       (try
         (swap! session add-event :start)
         (swap! session assoc :status :streaming)
         ((:stream provider-impl) conversation model options
          (fn [event]
            (case (:type event)
              :done  (do (swap! session complete-session)
                         (consume-fn event))
              :error (do (swap! session fail-session (:error-message event))
                         (consume-fn event))
              (consume-fn event))))
         (catch Exception e
           (swap! session fail-session (str e))
           (consume-fn {:type          :error
                        :error-message (str e)}))))
     :session session}))

;; ───────────────────────────────────────────────────────────────────────────
;; Lazy-sequence streaming (convenience wrapper)
;; ───────────────────────────────────────────────────────────────────────────

(def ^:private sentinel ::end-of-stream)

(defn stream-response-seq
  "Stream assistant response as a lazy sequence of event maps.

   A `LinkedBlockingQueue` bridges the background thread (producer) and the
   lazy seq (consumer).  The sequence terminates after a `:done` or `:error`
   event, or if the background thread completes without emitting either.

   Returns a map of:
     :events  — lazy sequence of event maps
     :session — atom holding the evolving StreamSession

   Example:
     (let [{:keys [events]} (stream-response-seq provider conversation model opts)]
       (doseq [ev events]
         (when (= :text-delta (:type ev))
           (print (:delta ev)))))
   "
  [provider-impl conversation model options]
  (let [queue       (LinkedBlockingQueue. 100)
        consume-fn  (fn [event]
                      (.put queue event)
                      (when (#{:done :error} (:type event))
                        (.put queue sentinel)))
        {:keys [session]} (stream-response provider-impl conversation model
                                            options consume-fn)
        drain-seq   (fn drain []
                      (lazy-seq
                       (let [item (.poll queue 30 TimeUnit/SECONDS)]
                         (cond
                           (nil? item)   nil           ;; timeout — treat as end
                           (= item sentinel) nil       ;; explicit terminator
                           :else         (cons item (drain))))))]
    {:events  (drain-seq)
     :session session}))
