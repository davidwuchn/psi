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
(def ^:private max-title-chars 60)
(def ^:private checkpoint-event "auto_session_name/rename_checkpoint")

(defonce ^:private state
  (atom {:turn-counts            {}
         :helper-session-ids     #{}
         :last-auto-name-by-session {}
         :turn-interval          default-turn-interval
         :delay-ms               default-delay-ms
         :log-fn                 nil
         :ui                     nil}))

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

(defn- slash-command-text?
  [text]
  (let [trimmed (some-> text str/trim)]
    (and (seq trimmed)
         (str/starts-with? trimmed "/"))))

(defn- message-text-fragments
  [msg]
  (->> (:content msg)
       (keep (fn [part]
               (when (= :text (:type part))
                 (squish (:text part)))))))

(defn- session-entry->message
  [entry]
  (when (= :message (:psi.session-entry/kind entry))
    (get-in entry [:psi.session-entry/data :message])))

(defn- visible-message-line
  [msg]
  (when-not (:custom-type msg)
    (case (:role msg)
      "user"
      (let [text (some->> (message-text-fragments msg)
                          (str/join " ")
                          squish)]
        (when (and text (not (slash-command-text? text)))
          (str "User: " text)))

      "assistant"
      (let [text (some->> (message-text-fragments msg)
                          (str/join " ")
                          squish)]
        (when text
          (str "Assistant: " text)))

      nil)))

(defn- sanitize-message-history
  [messages]
  (->> (or messages [])
       (keep visible-message-line)
       vec))

(defn- sanitize-session-entries
  [entries]
  (->> (or entries [])
       (keep session-entry->message)
       sanitize-message-history))

(defn- build-rename-prompt
  [lines]
  (when (seq lines)
    (str "Based on this conversation, what is the current purpose of the session?\n"
         "Reply with only a terse phrase of 2–8 words.\n"
         "No quotes. No explanation.\n\n"
         (str/join "\n" lines))))

(defn- normalize-title
  [text]
  (some-> text squish (str/replace #"^[\"'`]+|[\"'`]+$" "") squish))

(defn- valid-title?
  [title]
  (boolean
   (and (seq title)
        (<= (count title) max-title-chars)
        (not (re-find #"\n" title)))))

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

(defn- helper-session? [session-id]
  (contains? (:helper-session-ids @state) session-id))

(defn- remember-helper-session! [session-id]
  (swap! state update :helper-session-ids (fnil conj #{}) session-id)
  session-id)

(defn- stale-checkpoint?
  [session-id checkpoint-turn-count]
  (> (turn-count session-id) (or checkpoint-turn-count 0)))

(defn- last-auto-name [session-id]
  (get-in @state [:last-auto-name-by-session session-id]))

(defn- manual-override?
  [session-id current-name]
  (let [last-auto (last-auto-name session-id)]
    (boolean (and (seq last-auto)
                  (not= (squish current-name) (squish last-auto))))))

(defn- remember-auto-name! [session-id title]
  (swap! state assoc-in [:last-auto-name-by-session session-id] title)
  title)

(defn- query-session-entries [api session-id]
  (:psi.agent-session/session-entries
   ((:query-session api) session-id [{:psi.agent-session/session-entries
                                      [:psi.session-entry/kind
                                       :psi.session-entry/data]}])))

(defn- query-session-name [api session-id]
  (:psi.agent-session/session-name
   ((:query-session api) session-id [:psi.agent-session/session-name])))

(defn- infer-session-title [api source-session-id checkpoint-turn-count]
  (when-not (stale-checkpoint? source-session-id checkpoint-turn-count)
    (let [lines  (sanitize-session-entries (query-session-entries api source-session-id))
          prompt (build-rename-prompt lines)]
      (when (seq prompt)
        (let [child ((:mutate-session api) source-session-id 'psi.extension/create-child-session
                     {:session-name   "auto-session-name"
                      :tool-defs      []
                      :thinking-level :off})
              child-session-id (:psi.agent-session/session-id child)]
          (when child-session-id
            (remember-helper-session! child-session-id)
            (let [run-result ((:mutate-session api) child-session-id 'psi.extension/run-agent-loop-in-session
                              {:prompt prompt})
                  title      (some-> (:psi.agent-session/agent-run-text run-result)
                                     normalize-title)
                  current-name (query-session-name api source-session-id)]
              (when (and (true? (:psi.agent-session/agent-run-ok? run-result))
                         (valid-title? title)
                         (not (stale-checkpoint? source-session-id checkpoint-turn-count))
                         (not (manual-override? source-session-id current-name)))
                ((:mutate-session api) source-session-id 'psi.extension/set-session-name
                 {:name title})
                (remember-auto-name! source-session-id title)
                title))))))))

(defn- on-turn-finished [api payload]
  (when-let [session-id (normalize-session-id payload)]
    (when-not (helper-session? session-id)
      (let [n        (increment-turn-count! session-id)
            interval (:turn-interval @state)]
        (when (checkpoint-due? n interval)
          (schedule-checkpoint! api session-id n))))))

(defn- on-checkpoint [api payload]
  (when-let [session-id (normalize-session-id payload)]
    (when-not (helper-session? session-id)
      (or (infer-session-title api session-id (:turn-count payload))
          (when-let [count* (:turn-count payload)]
            (notify! (checkpoint-text {:session-id session-id
                                       :turn-count count*})))))))

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
