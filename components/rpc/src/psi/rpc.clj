(ns psi.rpc
  "Public RPC facade.

   Keeps the stable `psi.rpc` API while implementation lives in smaller
   transport/session namespaces."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.state-accessors :as sa]
   [psi.rpc.session :as rpc.session]
   [psi.rpc.transport :as rpc.transport]))

(def protocol-version rpc.transport/protocol-version)

(def response-frame rpc.transport/response-frame)
(def error-frame rpc.transport/error-frame)
(def event-frame rpc.transport/event-frame)
(def run-stdio-loop! rpc.transport/run-stdio-loop!)

(def footer-query rpc.session/footer-query)
(def footer-updated-payload rpc.session/footer-updated-payload)
(def session-updated-payload rpc.session/session-updated-payload)
(def progress-event->rpc-event rpc.session/progress-event->rpc-event)
(def session->handshake-server-info rpc.session/session->handshake-server-info)
(def make-session-request-handler rpc.session/make-session-request-handler)

(defn start-runtime!
  "Run EDN-lines RPC using an already-bootstrapped agent-session runtime context.

   The caller provides:
   - `session-ctx-factory` => (fn [] {:ctx .. :oauth-ctx .. :cwd .. :session-id ..})
   - `bootstrap-fn!`       => (fn [ctx session-id] ...)
   - `on-new-session!`     => (fn [source-session-id] {:session-id .. :agent-messages .. :messages .. :tool-calls .. :tool-order ..})

   In rpc-edn mode, stdout is protocol-only. Direct System/out writes are rebound to stderr."
  [{:keys [model-key
           memory-runtime-opts
           session-config
           rpc-trace-file
           session-state*
           nrepl-runtime
           resolve-model
           session-ctx-factory
           bootstrap-fn!
           on-new-session!]
    :or {memory-runtime-opts {}
         session-config {}
         rpc-trace-file nil}}]
  (let [protocol-out       *out*
        original-systemout System/out]
    (try
      (System/setOut (java.io.PrintStream. System/err true))
      (binding [*out* *err*]
        (let [ai-model      (resolve-model model-key)
              {:keys [ctx oauth-ctx session-id]} (session-ctx-factory ai-model session-config)
              _             (bootstrap-fn! ctx session-id ai-model memory-runtime-opts)
              trace-file*   (when-not (str/blank? rpc-trace-file)
                              rpc-trace-file)
              _             (dispatch/dispatch! ctx :session/set-rpc-trace {:session-id session-id :enabled? (boolean trace-file*) :file trace-file*} {:origin :core})
              trace-lock    (Object.)
              trace-fn      (fn [{:keys [dir raw frame parse-error]}]
                              (try
                                (let [cfg      (or (sa/rpc-trace-state-in ctx) {})
                                      enabled? (boolean (:enabled? cfg))
                                      path     (:file cfg)]
                                  (when (and enabled?
                                             (string? path)
                                             (not (str/blank? path)))
                                    (io/make-parents path)
                                    (let [entry (cond-> {:ts (str (java.time.Instant/now))
                                                         :dir dir
                                                         :raw raw}
                                                  (map? frame) (assoc :frame frame)
                                                  (and (string? parse-error)
                                                       (not (str/blank? parse-error)))
                                                  (assoc :parse-error parse-error))]
                                      (locking trace-lock
                                        (spit path (str (pr-str entry) "\n") :append true)))))
                                (catch Throwable _
                                  nil)))
              focus-atom    (atom session-id)
              state         (atom {:handshake-server-info-fn (fn [] (assoc (session->handshake-server-info ctx @focus-atom)
                                                                           :ui-type :emacs))
                                   :handshake-context-updated-payload-fn (fn [] {:active-session-id @focus-atom
                                                                                 :sessions []})
                                   :focus-session-id* focus-atom
                                   :subscribed-topics #{}
                                   :rpc-ai-model ai-model
                                   :on-new-session! (fn []
                                                      (let [source-session-id @focus-atom
                                                            result             (on-new-session! source-session-id)]
                                                        (reset! focus-atom (:session-id result))
                                                        result))})
              request-handler (make-session-request-handler ctx)]
          (reset! session-state* {:ctx ctx
                                  :ai-model ai-model
                                  :oauth-ctx oauth-ctx
                                  :nrepl-runtime-atom nrepl-runtime})
          (run-stdio-loop! {:request-handler request-handler
                            :state state
                            :out protocol-out
                            :trace-fn trace-fn})))
      (finally
        (System/setOut original-systemout)))))
