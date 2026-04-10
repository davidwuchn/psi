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
   [psi.agent-session.resolvers.support :as resolver-support]
   [psi.agent-session.session-state :as ss]
   [psi.history.git :as history-git]
   [psi.query.core :as query]))

;;; Extension query/mutation capability

(def ^:private session-scoped-extension-mutation-ops
  #{'psi.extension/set-session-name
    'psi.extension/set-active-tools
    'psi.extension/set-model
    'psi.extension/create-child-session
    'psi.extension/set-rpc-trace
    'psi.extension/interrupt
    'psi.extension/compact
    'psi.extension/append-entry
    'psi.extension/register-prompt-contribution
    'psi.extension/update-prompt-contribution
    'psi.extension/unregister-prompt-contribution
    'psi.extension/set-allowed-events
    'psi.extension.tool/read
    'psi.extension.tool/bash
    'psi.extension.tool/write
    'psi.extension.tool/update
    'psi.extension/run-tool-plan
    'psi.extension.tool/chain
    'psi.extension/send-message
    'psi.extension/send-prompt})

(def ^:private lifecycle-extension-mutation-param-builders
  {'psi.extension/create-session
   (fn [session-id params]
     (assoc params :parent-session-id session-id))

   'psi.extension/switch-session
   (fn [session-id params]
     (assoc params :source-session-id session-id))})

(defn- run-extension-mutation-in!
  "Execute a single EQL mutation op against `ctx` and return its payload.
   `op-sym` must be a qualified mutation symbol."
  [ctx session-id op-sym params]
  (let [register-resolvers! (:register-resolvers-fn ctx)
        register-mutations! (:register-mutations-fn ctx)
        qctx                (query/create-query-context)
        _                   (register-resolvers! qctx false)
        _                   (register-mutations! qctx (:all-mutations ctx) true)
        git-ctx             (history-git/create-context (ss/effective-cwd-in ctx session-id))
        seed                {:psi/agent-session-ctx ctx
                             :git/context git-ctx}
        full-params         (let [base-params (assoc params
                                                     :psi/agent-session-ctx ctx
                                                     :git/context git-ctx)]
                              (cond
                                (contains? session-scoped-extension-mutation-ops op-sym)
                                (assoc base-params :session-id session-id)

                                (contains? lifecycle-extension-mutation-param-builders op-sym)
                                ((get lifecycle-extension-mutation-param-builders op-sym) session-id base-params)

                                :else
                                base-params))
        payload             (get (query/query-in qctx seed [(list op-sym full-params)]) op-sym)]
    (bg-rt/maybe-track-background-workflow-job! ctx session-id op-sym full-params payload)
    payload))

(defn- query-extension-state
  [register-resolvers! register-mutations! ctx session-id eql-query]
  (let [qctx (query/create-query-context)
        _    (register-resolvers! qctx false)
        _    (register-mutations! qctx (:all-mutations ctx) true)]
    (binding [resolver-support/*session-id* session-id]
      (query/query-in qctx
                      {:psi/agent-session-ctx        ctx
                       :psi.agent-session/session-id session-id}
                      eql-query))))

(def ^:private extension-ui-allowed-events
  #{:session/ui-request-dialog
    :session/ui-set-widget
    :session/ui-clear-widget
    :session/ui-set-widget-spec
    :session/ui-clear-widget-spec
    :session/ui-set-status
    :session/ui-clear-status
    :session/ui-notify
    :session/ui-register-tool-renderer
    :session/ui-register-message-renderer
    :session/ui-set-tools-expanded})

(defn- extension-ui-context
  [ctx session-id current-session-data ext-path]
  (let [ext-id         (str ext-path)
        extension-meta {:origin :extension
                        :ext-id  ext-id}
        ext-dispatch!  (fn [event data]
                         (dispatch/dispatch! ctx event data extension-meta))
        ui-headless?   (fn []
                         (= :headless (:ui-type (current-session-data))))
        await-dialog   (fn [fallback request]
                         (if (ui-headless?)
                           fallback
                           (some-> (ext-dispatch! :session/ui-request-dialog request)
                                   deref)))
        ext-ui-payload (fn [data]
                         (assoc data
                                :session-id session-id
                                :extension-id ext-id))]
    {:confirm
     (fn [title message]
       (or (await-dialog false {:kind    :confirm
                                :ext-id  ext-id
                                :title   title
                                :message message})
           false))

     :select
     (fn [title options]
       (await-dialog nil {:kind    :select
                          :ext-id  ext-id
                          :title   title
                          :options options}))

     :input
     (fn [title & [placeholder]]
       (await-dialog nil {:kind        :input
                          :ext-id      ext-id
                          :title       title
                          :placeholder placeholder}))

     :set-widget
     (fn [widget-id placement content]
       (ext-dispatch! :session/ui-set-widget
                      (ext-ui-payload {:widget-id widget-id
                                       :placement placement
                                       :content   content})))

     :clear-widget
     (fn [widget-id]
       (ext-dispatch! :session/ui-clear-widget
                      (ext-ui-payload {:widget-id widget-id})))

     :set-widget-spec
     (fn [spec]
       (let [{:keys [accepted? errors]}
             (ext-dispatch! :session/ui-set-widget-spec
                            (ext-ui-payload {:spec spec}))]
         (when-not accepted?
           {:errors errors})))

     :clear-widget-spec
     (fn [widget-id]
       (ext-dispatch! :session/ui-clear-widget-spec
                      (ext-ui-payload {:widget-id widget-id})))

     :set-status
     (fn [text]
       (ext-dispatch! :session/ui-set-status
                      (ext-ui-payload {:text text})))

     :clear-status
     (fn []
       (ext-dispatch! :session/ui-clear-status
                      (ext-ui-payload {})))

     :notify
     (fn [message level]
       (ext-dispatch! :session/ui-notify
                      (ext-ui-payload {:message message
                                       :level   level})))

     :register-tool-renderer
     (fn [tool-name render-call-fn render-result-fn]
       (ext-dispatch! :session/ui-register-tool-renderer
                      (ext-ui-payload {:tool-name        tool-name
                                       :render-call-fn   render-call-fn
                                       :render-result-fn render-result-fn})))

     :register-message-renderer
     (fn [custom-type render-fn]
       (ext-dispatch! :session/ui-register-message-renderer
                      (ext-ui-payload {:custom-type custom-type
                                       :render-fn   render-fn})))

     :get-tools-expanded
     (fn []
       (boolean (get (ss/get-state-value-in ctx (ss/state-path :ui-state))
                     :tools-expanded? false)))

     :set-tools-expanded
     (fn [expanded?]
       (ext-dispatch! :session/ui-set-tools-expanded
                      {:session-id session-id
                       :expanded?  expanded?}))}))

(defn- make-extension-runtime-fns
  "Build the runtime-fns map for extension API EQL access.
   Extensions interact with session state via query/mutation only.
   Secrets are exposed via narrow capability fns (not queryable resolvers)."
  [ctx session-id ext-path]
  (let [register-resolvers! (:register-resolvers-fn ctx)
        register-mutations! (:register-mutations-fn ctx)
        current-session-data (fn []
                               (ss/get-session-data-in ctx session-id))]
    (when-let [reg (:extension-registry ctx)]
      (ext/set-allowed-events-in! reg (str ext-path) extension-ui-allowed-events))
    {:query-fn
     (fn [eql-query]
       (query-extension-state register-resolvers! register-mutations! ctx session-id eql-query))

     :mutate-fn
     (fn [op-sym params]
       (run-extension-mutation-in! ctx session-id op-sym params))

     :get-api-key-fn
     (fn [provider]
       (when-let [oauth-ctx (:oauth-ctx ctx)]
         (oauth/get-api-key oauth-ctx provider)))

     :ui-type-fn
     (fn []
       (:ui-type (current-session-data)))

     :ui-context-fn
     (fn [ext-path]
       (extension-ui-context ctx session-id current-session-data ext-path))

     :log-fn
     (fn [text]
       (binding [*out* *err*]
         (println text)))}))

;;; Extension loading

(defn load-extensions-in!
  "Discover and load all extensions into this session's registry.
   `configured-paths` are explicit CLI paths.
   Returns {:loaded [paths] :errors [{:path :error}]}."
  ([_ctx] (throw (ex-info "load-extensions-in! requires explicit session-id"
                          {:fn :load-extensions-in!})))
  ([ctx session-id configured-paths]
   (load-extensions-in! ctx session-id configured-paths nil))
  ([ctx session-id configured-paths cwd]
   (let [runtime-fns (make-extension-runtime-fns ctx session-id nil)]
     (ext/load-extensions-in! (:extension-registry ctx) runtime-fns
                              configured-paths cwd))))

(defn reload-extensions-in!
  "Unregister all extensions and re-discover/load them.

   Clears extension-owned prompt contributions before reload so stale
   fragments do not persist when extension composition changes."
  ([_ctx] (throw (ex-info "reload-extensions-in! requires explicit session-id"
                          {:fn :reload-extensions-in!})))
  ([ctx session-id configured-paths]
   (reload-extensions-in! ctx session-id configured-paths nil))
  ([ctx session-id configured-paths cwd]
   (dispatch/dispatch! ctx :session/reset-prompt-contributions {:session-id session-id} {:origin :core})
   (let [runtime-fns (make-extension-runtime-fns ctx session-id nil)
         result      (ext/reload-extensions-in! (:extension-registry ctx) runtime-fns
                                                configured-paths cwd)]
     (dispatch/dispatch! ctx :session/refresh-system-prompt {:session-id session-id} {:origin :core})
     result)))

(defn add-extension-in!
  "Load one extension file path into this session's extension registry.
   Returns {:loaded? bool :path string? :error string?}."
  ([_ctx _path]
   (throw (ex-info "add-extension-in! requires explicit session-id"
                   {:fn :add-extension-in!})))
  ([ctx session-id path]
   (let [{:keys [extension error]}
         (ext/load-extension-in! (:extension-registry ctx)
                                 path
                                 (make-extension-runtime-fns ctx session-id path))]
     {:loaded? (some? extension)
      :path    extension
      :error   error})))

;;; Extension messaging and prompt delivery

(defn send-extension-message-in!
  "Append an extension-injected message to agent history and emit message events.
   Agent-core mutations and event-queue notification are dispatch-owned effects.

   During bootstrap (before startup prompts complete) the message is NOT
   appended to LLM history — it is only forwarded to the event queue so the
   UI can display it as a transient notification without corrupting the
   conversation context."
  [ctx session-id role content custom-type]
  (let [msg {:role      role
             :content   [{:type :text :text (str content)}]
             :timestamp (java.time.Instant/now)}
        msg (cond-> msg
              custom-type (assoc :custom-type custom-type))]
    (dispatch/dispatch! ctx
                        :session/send-extension-message
                        {:session-id session-id :message msg}
                        {:origin :core})
    msg))

(defn set-extension-run-fn-in!
  "Register the runtime agent-loop runner for extension-initiated prompts.
   `run-fn` is (fn [text source]) — it journals the user message and runs
   the full agent loop (LLM call included) in a background thread.
   Called by the runtime layer (main/RPC) after bootstrap."
  [ctx session-id run-fn]
  (reset! (:extension-run-fn-atom ctx) run-fn)
  (when (ss/idle-in? ctx session-id)
    (bg-rt/reconcile-and-emit-background-job-terminals-in! ctx session-id)))

(defn- deliver-extension-prompt!
  "Deliver extension prompt text via the best available channel.
   Returns the delivery mode keyword."
  [ctx session-id text source]
  (let [run-fn @(:extension-run-fn-atom ctx)
        idle?  (ss/idle-in? ctx session-id)]
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
                              {:session-id session-id :text (str text)} {:origin :core})
          :follow-up))))

(defn send-extension-prompt-in!
  "Submit extension-authored text to the agent as a user prompt.

   Delivery semantics:
   - run-fn registered + idle      -> run immediately in background (:prompt)
   - run-fn registered + streaming -> run deferred in background (:deferred)
   - no run-fn registered          -> queue follow-up text (:follow-up)"
  [ctx session-id text source]
  (let [delivery (deliver-extension-prompt! ctx session-id text source)]
    (dispatch/dispatch! ctx
                        :session/record-extension-prompt
                        {:session-id session-id
                         :source     source
                         :delivery   delivery
                         :at         (java.time.Instant/now)}
                        {:origin :core})
    (when (ss/idle-in? ctx session-id)
      (bg-rt/reconcile-and-emit-background-job-terminals-in! ctx session-id))
    {:accepted true :delivery delivery}))
