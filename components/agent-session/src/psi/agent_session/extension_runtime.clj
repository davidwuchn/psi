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
   [psi.agent-session.extensions.runtime-delivery :as runtime-delivery]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.extensions.runtime-ui :as runtime-ui]
   [psi.agent-session.session-state :as ss]))

(defn load-extensions-in!
  "Discover and load all extensions into this session's registry.
   `configured-paths` are explicit CLI paths.
   Returns {:loaded [paths] :errors [{:path :error}]}."
  ([_ctx] (throw (ex-info "load-extensions-in! requires explicit session-id"
                          {:fn :load-extensions-in!})))
  ([ctx session-id configured-paths]
   (load-extensions-in! ctx session-id configured-paths nil))
  ([ctx session-id configured-paths cwd]
   (let [runtime-fns* (runtime-fns/make-extension-runtime-fns ctx session-id nil)]
     (ext/load-extensions-in! (:extension-registry ctx) runtime-fns*
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
   (let [runtime-fns* (runtime-fns/make-extension-runtime-fns ctx session-id nil)
         result       (ext/reload-extensions-in! (:extension-registry ctx) runtime-fns*
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
                                 (runtime-fns/make-extension-runtime-fns ctx session-id path))]
     {:loaded? (some? extension)
      :path    extension
      :error   error})))

(def extension-ui-context
  runtime-ui/extension-ui-context)

(defn notify-extension-in!
  "Emit an extension-authored UI-visible notification that must not become part
   of future LLM-visible conversation assembly.

   Internal representation may use :custom-type to preserve the current
   conversation exclusion invariant, but callers should rely on the explicit
   notify semantic rather than that representation detail."
  [ctx session-id role content custom-type]
  (let [msg {:role      role
             :content   [{:type :text :text (str content)}]
             :timestamp (java.time.Instant/now)}
        msg (cond-> msg
              custom-type (assoc :custom-type custom-type)
              (not custom-type) (assoc :custom-type "extension-notification"))]
    (dispatch/dispatch! ctx
                        :session/notify-extension
                        {:session-id session-id :message msg}
                        {:origin :core})
    msg))

(defn append-extension-message-in!
  "Append an extension-authored synthetic conversation message that is both
   transcript-visible and future LLM-visible."
  [ctx session-id role content]
  (let [msg {:role      role
             :content   [{:type :text :text (str content)}]
             :timestamp (java.time.Instant/now)}]
    (dispatch/dispatch! ctx
                        :session/append-extension-message
                        {:session-id session-id :message msg}
                        {:origin :core})
    msg))

(defn send-extension-message-in!
  "Compatibility-only wrapper for the older ambiguous extension message API.

   New code should use notify-extension-in! or append-extension-message-in!
   explicitly.

   Preserved migration semantics:
   - custom-type present    => UI-visible, non-conversation notification
   - no custom-type present => conversation-visible synthetic message"
  [ctx session-id role content custom-type]
  (if custom-type
    (notify-extension-in! ctx session-id role content custom-type)
    (append-extension-message-in! ctx session-id role content)))

(defn set-extension-run-fn-in!
  "Register the runtime agent-loop runner for extension-initiated prompts.
   `run-fn` is (fn [text source]) — it journals the user message and runs
   the full agent loop (LLM call included) in a background thread.
   Called by the runtime layer (main/RPC) after bootstrap."
  [ctx session-id run-fn]
  (reset! (:extension-run-fn-atom ctx) run-fn)
  (runtime-delivery/maybe-reconcile-after-run-fn-registration! ctx session-id))

(defn send-extension-prompt-in!
  "Submit extension-authored text to the agent as a user prompt.

   Delivery semantics:
   - run-fn registered + idle      -> run immediately in background (:prompt)
   - run-fn registered + streaming -> run deferred in background (:deferred)
   - no run-fn registered          -> queue follow-up text (:follow-up)"
  [ctx session-id text source]
  (let [delivery (runtime-delivery/deliver-extension-prompt! ctx session-id text source)]
    (dispatch/dispatch! ctx
                        :session/record-extension-prompt
                        {:session-id session-id
                         :source     source
                         :delivery   delivery
                         :at         (java.time.Instant/now)}
                        {:origin :core})
    (runtime-delivery/maybe-reconcile-after-prompt-record! ctx session-id)
    {:accepted true :delivery delivery}))
