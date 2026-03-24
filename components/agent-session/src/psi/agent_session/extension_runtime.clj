(ns psi.agent-session.extension-runtime
  "Extension runtime orchestration — loading, messaging, and prompt delivery.

   This namespace consumes capabilities from the ctx map (set at context
   creation time) rather than depending on core.clj. The key capability
   injections are:
   - :register-resolvers-fn  — (fn [qctx rebuild?])
   - :register-mutations-fn  — (fn [qctx mutations rebuild?])

   query-fn and mutate-fn create a fresh query context per invocation because
   the resolver/mutation set can change at runtime (extension reload, new
   resolver registration). Caching would require invalidation on every
   registration change."
  (:require
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.session-state :as ss]
   [psi.history.git :as history-git]
   [psi.query.core :as query]
   [psi.ui.state :as ui-state]))

;;; Extension query/mutation capability

(defn- run-extension-mutation-in!
  "Execute a single EQL mutation op against `ctx` and return its payload.
   `op-sym` must be a qualified mutation symbol."
  [ctx op-sym params]
  (let [register-resolvers! (:register-resolvers-fn ctx)
        register-mutations! (:register-mutations-fn ctx)
        qctx (query/create-query-context)
        _    (register-resolvers! qctx false)
        _    (register-mutations! qctx (:all-mutations ctx) true)
        git-ctx (history-git/create-context (ss/effective-cwd-in ctx))
        seed {:psi/agent-session-ctx ctx
              :git/context git-ctx}
        full-params (assoc params
                           :psi/agent-session-ctx ctx
                           :git/context git-ctx)
        payload (get (query/query-in qctx seed [(list op-sym full-params)]) op-sym)]
    (bg-rt/maybe-track-background-workflow-job! ctx op-sym full-params payload)
    payload))

(defn- make-extension-runtime-fns
  "Build the runtime-fns map for extension API EQL access.
   Extensions interact with session state via query/mutation only.
   Secrets are exposed via narrow capability fns (not queryable resolvers)."
  [ctx]
  (let [register-resolvers! (:register-resolvers-fn ctx)
        register-mutations! (:register-mutations-fn ctx)]
    {:query-fn
     (fn [eql-query]
       (let [qctx (query/create-query-context)
             _    (register-resolvers! qctx false)
             _    (register-mutations! qctx (:all-mutations ctx) true)]
         (query/query-in qctx {:psi/agent-session-ctx ctx} eql-query)))

     :mutate-fn
     (fn [op-sym params]
       (run-extension-mutation-in! ctx op-sym params))

     :get-api-key-fn
     (fn [provider]
       (when-let [oauth-ctx (:oauth-ctx ctx)]
         (oauth/get-api-key oauth-ctx provider)))

     :ui-type-fn
     (fn []
       (:ui-type (ss/get-session-data-in ctx)))

     :ui-context-fn
     (fn [ext-path]
       (ui-state/create-ui-context (ss/ui-state-view-in ctx) ext-path))}))

;;; Extension loading

(defn load-extensions-in!
  "Discover and load all extensions into this session's registry.
   `configured-paths` are explicit CLI paths.
   Returns {:loaded [paths] :errors [{:path :error}]}."
  ([ctx] (load-extensions-in! ctx []))
  ([ctx configured-paths]
   (load-extensions-in! ctx configured-paths nil))
  ([ctx configured-paths cwd]
   (let [runtime-fns (make-extension-runtime-fns ctx)]
     (ext/load-extensions-in! (:extension-registry ctx) runtime-fns
                              configured-paths cwd))))

(defn reload-extensions-in!
  "Unregister all extensions and re-discover/load them.

   Clears extension-owned prompt contributions before reload so stale
   fragments do not persist when extension composition changes."
  ([ctx] (reload-extensions-in! ctx []))
  ([ctx configured-paths]
   (reload-extensions-in! ctx configured-paths nil))
  ([ctx configured-paths cwd]
   (dispatch/dispatch! ctx :session/reset-prompt-contributions nil {:origin :core})
   (let [runtime-fns (make-extension-runtime-fns ctx)
         result      (ext/reload-extensions-in! (:extension-registry ctx) runtime-fns
                                                configured-paths cwd)]
     (dispatch/dispatch! ctx :session/refresh-system-prompt nil {:origin :core})
     result)))

(defn add-extension-in!
  "Load one extension file path into this session's extension registry.
   Returns {:loaded? bool :path string? :error string?}."
  [ctx path]
  (let [{:keys [extension error]}
        (ext/load-extension-in! (:extension-registry ctx)
                                path
                                (make-extension-runtime-fns ctx))]
    {:loaded? (some? extension)
     :path    extension
     :error   error}))

;;; Extension messaging and prompt delivery

(defn send-extension-message-in!
  "Append an extension-injected message to agent history and emit message events.
   Agent-core mutations and event-queue notification are dispatch-owned effects.

   During bootstrap (before startup prompts complete) the message is NOT
   appended to LLM history — it is only forwarded to the event queue so the
   UI can display it as a transient notification without corrupting the
   conversation context."
  [ctx role content custom-type]
  (let [msg {:role      role
             :content   [{:type :text :text (str content)}]
             :timestamp (java.time.Instant/now)}
        msg (cond-> msg
              custom-type (assoc :custom-type custom-type))]
    (dispatch/dispatch! ctx
                        :session/send-extension-message
                        {:message msg}
                        {:origin :core})
    msg))

(defn set-extension-run-fn-in!
  "Register the runtime agent-loop runner for extension-initiated prompts.
   `run-fn` is (fn [text source]) — it journals the user message and runs
   the full agent loop (LLM call included) in a background thread.
   Called by the runtime layer (main/RPC) after bootstrap."
  [ctx run-fn]
  (reset! (:extension-run-fn-atom ctx) run-fn)
  (when (ss/idle-in? ctx)
    (bg-rt/reconcile-and-emit-background-job-terminals-in! ctx)))

(defn- deliver-extension-prompt!
  "Deliver extension prompt text via the best available channel.
   Returns the delivery mode keyword."
  [ctx text source]
  (let [run-fn @(:extension-run-fn-atom ctx)
        idle?  (ss/idle-in? ctx)]
    (cond
      (and run-fn idle?)
      (do (future (run-fn (str text) source))
          :prompt)

      run-fn
      (do (future (run-fn (str text) source))
          :deferred)

      ;; No run-fn registered — fall back to follow-up queue so at
      ;; least the text is not silently dropped (caller can drain it).
      :else
      (do (dispatch/dispatch! ctx :session/enqueue-follow-up-message
                              {:text (str text)} {:origin :core})
          :follow-up))))

(defn send-extension-prompt-in!
  "Submit extension-authored text to the agent as a user prompt.

   Delivery semantics:
   - run-fn registered + idle      -> run immediately in background (:prompt)
   - run-fn registered + streaming -> run deferred in background (:deferred)
   - no run-fn registered          -> queue follow-up text (:follow-up)"
  [ctx text source]
  (let [delivery (deliver-extension-prompt! ctx text source)]
    (dispatch/dispatch! ctx
                        :session/record-extension-prompt
                        {:source source
                         :delivery delivery
                         :at (java.time.Instant/now)}
                        {:origin :core})
    (when (ss/idle-in? ctx)
      (bg-rt/reconcile-and-emit-background-job-terminals-in! ctx))
    {:accepted true :delivery delivery}))
