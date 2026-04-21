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
   [clojure.repl.deps :as repl.deps]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-installs :as installs]
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

(defn sync-non-local-extension-deps!
  [deps-to-realize]
  (if (seq deps-to-realize)
    (try
      (repl.deps/sync-deps :aliases [] :deps deps-to-realize)
      {:deps-realized? true}
      (catch Throwable t
        (let [message (.getMessage t)]
          (if (and (string? message)
                   (.contains message "only available at the REPL"))
            {:deps-realized? false
             :deps-restart-required? true}
            {:deps-realized? false
             :deps-realize-error (str "Failed to realize non-local extension deps in-process: " message)}))))
    {:deps-realized? false}))

(defn- manifest-extension-id
  [lib]
  (str "manifest:" lib))

(defn- activation-entries
  [plan]
  (vec
   (concat
    (map (fn [path]
           {:type :path
            :id   path
            :path path})
         (:extension-paths plan))
    (keep (fn [[lib {:keys [dep extension? enabled?]}]]
            (when (and extension?
                       enabled?
                       (not= :local (installs/coordinate-family dep))
                       (:deps-realized? plan))
              {:type     :init-var
               :id       (manifest-extension-id lib)
               :lib      lib
               :init-var (:psi/init dep)}))
          (:entries-by-lib plan)))))

(defn- merge-extension-results
  [activation-result deps-result]
  (merge activation-result (select-keys deps-result [:deps-realized? :deps-restart-required? :deps-realize-error])))

(defn activate-manifest-extensions-in!
  "Activate manifest-aware extensions through one shared abstraction.

   `plan` keys used:
   - :extension-paths
   - :entries-by-lib
   - :deps-realized?

   Returns {:loaded [id ...] :errors [{:path :error}] ...deps-result-keys}."
  [ctx session-id plan deps-result]
  (let [runtime-fns* (runtime-fns/make-extension-runtime-fns ctx session-id nil)
        activation-result (ext/activate-extensions-in! (:extension-registry ctx)
                                                       runtime-fns*
                                                       (activation-entries (assoc plan :deps-realized? (:deps-realized? deps-result))))]
    (merge-extension-results activation-result deps-result)))

(defn bootstrap-manifest-extensions-in!
  "Compute, realize, activate, summarize, and persist manifest-backed startup
   extension state for a bootstrapped session.

   Returns:
   - :manifest-result  — activation result with dependency realization details
   - :finalized-state  — persisted install-state payload
   - :summary-updates  — startup-summary deltas derived from manifest activation"
  [ctx session-id cwd]
  (let [install-state      (installs/compute-install-state cwd)
        install-plan0      (installs/activation-plan install-state)
        deps-result        (sync-non-local-extension-deps! (:deps-to-realize install-plan0))
        install-plan       (-> install-plan0
                               (assoc :entries-by-lib (get-in install-state [:psi.extensions/effective :entries-by-lib]))
                               (assoc :restart-required-libs #{}))
        manifest-result0   (activate-manifest-extensions-in! ctx session-id install-plan deps-result)
        deps-failure-errors (if-let [msg (:deps-realize-error deps-result)]
                              (mapv (fn [lib]
                                      {:path (installs/manifest-extension-id lib)
                                       :error msg})
                                    (:deps-extension-libs install-plan))
                              [])
        manifest-result    (update manifest-result0 :errors into deps-failure-errors)
        finalized-state    (installs/finalize-apply-state install-state install-plan manifest-result)
        persisted-state    (installs/persist-install-state-in! ctx finalized-state)
        summary-updates    {:extension-loaded-count (count (:loaded manifest-result))
                            :extension-error-count  (count (:errors manifest-result))
                            :extension-errors       (:errors manifest-result)}]
    {:manifest-result manifest-result
     :finalized-state persisted-state
     :summary-updates summary-updates}))

(defn reload-extensions-in!
  "Unregister all extensions and re-discover/load them.

   Clears extension-owned prompt contributions before reload so stale
   fragments do not persist when extension composition changes.

   Apply behavior:
   - path-discovered legacy extensions still reload as before
   - manifest-backed enabled `:local/root` extensions are also loaded in-process
   - manifest-backed git/mvn deps are synced in-process when the current runtime
     can safely preserve already-realized non-local deps; otherwise they remain
     `:restart-required`"
  ([_ctx] (throw (ex-info "reload-extensions-in! requires explicit session-id"
                          {:fn :reload-extensions-in!})))
  ([ctx session-id configured-paths]
   (reload-extensions-in! ctx session-id configured-paths nil))
  ([ctx session-id configured-paths cwd]
   (dispatch/dispatch! ctx :session/reset-prompt-contributions {:session-id session-id} {:origin :core})
   (let [runtime-fns*      (runtime-fns/make-extension-runtime-fns ctx session-id nil)
         effective-cwd     (or cwd (ss/session-worktree-path-in ctx session-id))
         previous-state    (installs/extension-installs-state-in ctx)
         install-state     (installs/compute-install-state effective-cwd)
         plan-base         (installs/activation-plan install-state previous-state)
         deps-result       (if (:deps-apply-safe? plan-base)
                             (sync-non-local-extension-deps! (:deps-to-realize plan-base))
                             {:deps-realized? false})
         configured*       (vec configured-paths)
         path-reload       (ext/reload-extensions-in! (:extension-registry ctx) runtime-fns* configured* effective-cwd)
         plan              (assoc plan-base
                                  :entries-by-lib (get-in install-state [:psi.extensions/effective :entries-by-lib]))
         manifest-result   (activate-manifest-extensions-in! ctx session-id plan deps-result)
         reload-result     (-> path-reload
                               (update :loaded into (:loaded manifest-result))
                               (update :errors into (:errors manifest-result))
                               (merge (select-keys manifest-result [:deps-realized? :deps-restart-required? :deps-realize-error])))
         finalized-state   (installs/finalize-apply-state install-state plan reload-result)
         persisted-state   (installs/persist-install-state-in! ctx finalized-state)]
     (dispatch/dispatch! ctx :session/refresh-system-prompt {:session-id session-id} {:origin :core})
     (assoc reload-result :install-state persisted-state))))

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