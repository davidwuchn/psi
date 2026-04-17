(ns extensions.auto-session-name
  "MVP auto-session-name extension.

   Current behavior:
   - counts completed turns per session
   - every N turns schedules a delayed checkpoint event
   - when the checkpoint fires, emits a transient UI notification (or log fallback)

   This does not rename sessions yet."
  (:require
   [clojure.string :as str]))

(def ^:private default-turn-interval 2)
(def ^:private default-delay-ms 250)
(def ^:private checkpoint-event "auto_session_name/rename_checkpoint")

(defonce ^:private state
  (atom {:turn-counts   {}
         :turn-interval default-turn-interval
         :delay-ms      default-delay-ms
         :log-fn        nil
         :ui            nil}))

(defn- normalize-session-id [payload]
  (some-> (:session-id payload) str not-empty))

(defn- turn-count [session-id]
  (get-in @state [:turn-counts session-id] 0))

(defn- increment-turn-count! [session-id]
  (swap! state update-in [:turn-counts session-id] (fnil inc 0))
  (turn-count session-id))

(defn- checkpoint-due?
  [n interval]
  (and (pos-int? interval)
       (pos-int? n)
       (zero? (mod n interval))))

(defn- squish [s]
  (some-> s str (str/replace #"\s+" " ") str/trim not-empty))

(defn- checkpoint-text [{:keys [session-id turn-count]}]
  (str "auto-session-name: rename checkpoint for session " session-id
       " at turn " turn-count))

(defn- notify! [text]
  (if-let [notify-fn (some-> @state :ui :notify)]
    (notify-fn text :info)
    (when-let [log-fn (:log-fn @state)]
      (log-fn text))))

(defn- schedule-checkpoint! [api session-id turn-count]
  ((:mutate api) 'psi.extension/schedule-event
   {:delay-ms   (:delay-ms @state)
    :event-name checkpoint-event
    :payload    {:session-id session-id
                 :turn-count turn-count}}))

(defn- on-turn-finished [api payload]
  (when-let [session-id (normalize-session-id payload)]
    (let [n        (increment-turn-count! session-id)
          interval (:turn-interval @state)]
      (when (checkpoint-due? n interval)
        (schedule-checkpoint! api session-id n)))))

(defn- on-checkpoint [_api payload]
  (when-let [session-id (normalize-session-id payload)]
    (when-let [count* (:turn-count payload)]
      (notify! (checkpoint-text {:session-id session-id
                                 :turn-count count*})))))

(defn init [api]
  (swap! state assoc
         :log-fn (:log api)
         :ui (:ui api)
         :turn-counts {})
  ((:on api) "session_turn_finished"
   (fn [payload]
     (on-turn-finished api payload)
     nil))
  ((:on api) checkpoint-event
   (fn [payload]
     (on-checkpoint api payload)
     nil))
  (when-let [ui (:ui api)]
    (when-let [notify (:notify ui)]
      (notify "auto-session-name loaded" :info))))
