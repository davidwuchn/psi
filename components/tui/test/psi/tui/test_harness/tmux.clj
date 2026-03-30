(ns psi.tui.test-harness.tmux
  "Reusable tmux-backed integration harness utilities for TUI tests."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [psi.tui.ansi :as ansi]))

(def default-launch-command
  "exec clojure -M:run --tui")

(def default-startup-timeout-ms 120000)
(def default-step-timeout-ms 15000)
(def default-poll-interval-ms 100)
(def default-ready-markers ["刀:" "Type a message"])
(def default-help-marker "(anything else is sent to the agent)")
(def default-capture-lines 3000)

(defn- run-sh
  [cmd]
  (shell/sh "bash" "-lc" cmd))

(defn tmux-available?
  []
  (zero? (:exit (run-sh "command -v tmux >/dev/null 2>&1"))))

(defn unique-session-name
  []
  (str "psi-tui-it-" (System/currentTimeMillis) "-" (rand-int 1000000)))

(defn sanitize-pane-text
  [s]
  (-> (or s "")
      ansi/strip-ansi
      (str/replace #"\r" "\n")
      (str/replace #"\u0008" "")
      (str/replace #"\u000e|\u000f" "")))

(defn capture-pane
  ([session-name]
   (capture-pane session-name {}))
  ([session-name {:keys [capture-lines]
                  :or {capture-lines default-capture-lines}}]
   (let [{:keys [exit out err]}
         (run-sh (format "tmux capture-pane -pt %s:0.0 -S -%d"
                         session-name
                         capture-lines))]
     (if (zero? exit)
       out
       (str "tmux-capture-pane-failed: " (or err ""))))))

(defn pane-current-command
  [session-name]
  (let [{:keys [exit out]}
        (run-sh (format "tmux display-message -p -t %s:0.0 '#{pane_current_command}'"
                        session-name))]
    (when (zero? exit)
      (str/trim out))))

(defn send-line!
  [session-name s]
  (run-sh (format "tmux send-keys -l -t %s:0.0 %s"
                  session-name
                  (pr-str s)))
  (run-sh (format "tmux send-keys -t %s:0.0 Enter" session-name)))

(defn wait-until
  ([pred timeout-ms]
   (wait-until pred timeout-ms default-poll-interval-ms))
  ([pred timeout-ms poll-interval-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (if (pred)
         true
         (if (>= (System/currentTimeMillis) deadline)
           false
           (do
             (Thread/sleep poll-interval-ms)
             (recur))))))))

(defn kill-session-if-exists!
  [session-name]
  (run-sh (format "tmux kill-session -t %s >/dev/null 2>&1 || true" session-name)))

(defn start-session!
  [{:keys [session-name working-dir launch-command]
    :or {working-dir (str (.getCanonicalPath (io/file ".")))
         launch-command default-launch-command}}]
  (run-sh (format "tmux new-session -d -s %s -c %s"
                  session-name
                  (pr-str working-dir)))
  (send-line! session-name launch-command)
  session-name)

(defn wait-for-any-marker
  [session-name markers timeout-ms]
  (wait-until
   (fn []
     (let [pane (sanitize-pane-text (capture-pane session-name))]
       (boolean (some #(str/includes? pane %) markers))))
   timeout-ms))

(defn wait-for-marker
  [session-name marker timeout-ms]
  (wait-until
   (fn []
     (str/includes? (sanitize-pane-text (capture-pane session-name)) marker))
   timeout-ms))

(defn wait-for-java-exit
  [session-name timeout-ms]
  (wait-until
   (fn []
     (not= "java" (pane-current-command session-name)))
   timeout-ms))

(defn run-basic-help-quit-scenario!
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           help-marker
           keep-session-on-failure?]
    :or {working-dir (str (.getCanonicalPath (io/file ".")))
         launch-command default-launch-command
         startup-timeout-ms default-startup-timeout-ms
         step-timeout-ms default-step-timeout-ms
         ready-markers default-ready-markers
         help-marker default-help-marker
         keep-session-on-failure? false}}]
  (let [session-name* (or session-name (unique-session-name))
        result        (try
                        (start-session! {:session-name session-name*
                                         :working-dir working-dir
                                         :launch-command launch-command})
                        (if-not (wait-for-any-marker session-name* ready-markers startup-timeout-ms)
                          {:status :failed
                           :reason :startup-timeout
                           :session-name session-name*
                           :pane-snapshot (sanitize-pane-text (capture-pane session-name*))}
                          (do
                            (send-line! session-name* "/help")
                            (if-not (wait-for-marker session-name* help-marker step-timeout-ms)
                              {:status :failed
                               :reason :help-timeout
                               :session-name session-name*
                               :pane-snapshot (sanitize-pane-text (capture-pane session-name*))}
                              (do
                                (send-line! session-name* "/quit")
                                (if (wait-for-java-exit session-name* step-timeout-ms)
                                  {:status :passed
                                   :session-name session-name*}
                                  {:status :failed
                                   :reason :quit-timeout
                                   :session-name session-name*
                                   :pane-snapshot (sanitize-pane-text (capture-pane session-name*))})))))
                        (catch Throwable t
                          {:status :failed
                           :reason :exception
                           :session-name session-name*
                           :error-message (or (ex-message t) (str t))
                           :pane-snapshot (sanitize-pane-text (capture-pane session-name*))}))]
    (when (or (= :passed (:status result))
              (not keep-session-on-failure?))
      (kill-session-if-exists! session-name*))
    result))
