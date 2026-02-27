(ns extensions.hello-ext)

(defn init [api]
  (let [mutate-fn (:mutate api)]
    ;; Register a slash command
    (mutate-fn 'psi.extension/register-command
               {:name "hello"
                :opts {:description "Say hello"
                       :handler     (fn [_args] (println "Hello from extension!"))}})

    ;; Listen to events
    (mutate-fn 'psi.extension/register-handler
               {:event-name "session_switch"
                :handler-fn (fn [ev] (println "Session switched:" (:reason ev)))})

    ;; Show a status line in the TUI footer
    #_(when-let [ui (:ui api)]
        ((:set-status ui) "hello-ext loaded"))))
