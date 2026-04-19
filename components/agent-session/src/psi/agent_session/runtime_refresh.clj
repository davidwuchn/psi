(ns psi.agent-session.runtime-refresh
  "Canonical post-code-reload runtime refresh pass.

   This pass is intentionally fixed-order and best-effort. It preserves normal
   runtime data by default while refreshing known executable wiring surfaces and
   reporting honest in-place refresh limitations."
  (:require
   [clojure.string :as str]
   [psi.agent-session.dispatch :as dispatch]))

(def ^:private refresh-step-order
  [:query-graph :dispatch-handlers :extensions :runtime-hooks])

(defn- duration-ms
  [started-at]
  (long (/ (- (System/nanoTime) started-at) 1000000)))

(defn- step-result
  ([step status summary]
   {:step step :status status :summary summary})
  ([step status summary details]
   (cond-> {:step step :status status :summary summary}
     (some? details) (assoc :details details))))

(defn- error-summary
  [phase e]
  (cond-> {:message (.getMessage e)
           :class   (.getName (class e))
           :phase   phase}
    (map? (ex-data e))
    (assoc :data (ex-data e))))

(defn- capture-step
  [step f]
  (try
    (f)
    (catch Exception e
      {:step  step
       :status :error
       :summary (str (name step) " refresh failed")
       :error (error-summary step e)})))

(defn- limitation
  [boundary reason remediation]
  {:boundary boundary
   :reason reason
   :remediation remediation})

(defn- refresh-query-graph!
  [ctx]
  (if-not ctx
    (step-result :query-graph :ok "query graph unchanged (no runtime ctx provided)")
    (do
      ((requiring-resolve 'psi.query.registry/reset-registry!))
      ((requiring-resolve 'psi.agent-session.context/register-resolvers!))
      ((requiring-resolve 'psi.agent-session.context/register-mutations!) (:all-mutations ctx))
      (step-result :query-graph :ok "resolver, mutation, and compiled query env refreshed"))))

(defn- refresh-dispatch-handlers!
  [ctx]
  (if-not ctx
    (step-result :dispatch-handlers :ok "dispatch handlers unchanged (no runtime ctx provided)")
    (do
      (dispatch/clear-handlers!)
      ((requiring-resolve 'psi.agent-session.dispatch-handlers/register-all!) ctx)
      (step-result :dispatch-handlers :ok "dispatch handlers refreshed; event log and trace preserved"))))

(defn- refresh-extensions!
  [{:keys [ctx session-id extension-refresh? worktree-path]}]
  (cond
    (not ctx)
    (step-result :extensions :ok "extension registry unchanged (no runtime ctx provided)"
                 {:loaded [] :errors []})

    (not extension-refresh?)
    (step-result :extensions :ok "preserved current extension registry without rediscovery"
                 {:loaded [] :errors []})

    :else
    (let [result ((requiring-resolve 'psi.agent-session.extension-runtime/reload-extensions-in!)
                   ctx session-id [] worktree-path)]
      (step-result :extensions
                   (if (seq (:errors result)) :error :ok)
                   (str "extension refresh "
                        (if (seq (:errors result)) "completed with errors" "completed")
                        " (loaded=" (count (:loaded result)) ", errors=" (count (:errors result)) ")")
                   {:loaded (:loaded result)
                    :errors (:errors result)}))))

(defn- maybe-install-background-job-ui-refresh!
  [ctx]
  (when ctx
    (when-let [install! (requiring-resolve 'psi.app-runtime.background-job-ui/install-background-job-ui-refresh!)]
      (install! ctx)
      true)))

(defn- session-ai-model
  [ctx session-id]
  (let [get-session-data-in (requiring-resolve 'psi.agent-session.session-state/get-session-data-in)
        model               (:model (get-session-data-in ctx session-id))
        provider            (:provider model)
        model-id            (:id model)]
    (when (and (string? provider)
               (not (str/blank? provider))
               (string? model-id)
               (not (str/blank? model-id)))
      {:provider           (keyword provider)
       :id                 model-id
       :supports-reasoning (boolean (:reasoning model))})))

(defn- maybe-reinstall-extension-run-fn!
  [ctx session-id]
  (let [existing-run-fn? (some? (some-> ctx :extension-run-fn-atom deref))
        ai-model         (when (and ctx session-id existing-run-fn?)
                           (session-ai-model ctx session-id))]
    (cond
      (not existing-run-fn?)
      {:reinstalled? false :pending? false :reason :not-installed}

      (nil? session-id)
      {:reinstalled? false :pending? true :reason :missing-session-id}

      (nil? ai-model)
      {:reinstalled? false :pending? true :reason :missing-model}

      :else
      (do
        ((requiring-resolve 'psi.agent-session.runtime/register-extension-run-fn-in!)
         ctx session-id nil ai-model)
        {:reinstalled? true :pending? false :reason :reinstalled :session-id session-id}))))

(defn- refresh-runtime-hooks!
  [ctx session-id]
  (if-not ctx
    (step-result :runtime-hooks :ok "runtime hooks unchanged (no runtime ctx provided)")
    (let [installed-background?   (boolean (maybe-install-background-job-ui-refresh! ctx))
          extension-run-reinstall (maybe-reinstall-extension-run-fn! ctx session-id)
          extension-run-pending?  (:pending? extension-run-reinstall)
          status                  (if extension-run-pending? :partial :ok)
          summary                 (if extension-run-pending?
                                    "reinstalled known runtime hooks with remaining extension run-fn limitations"
                                    "reinstalled known runtime hooks")
          details                 {:reinstalled (cond-> []
                                                  installed-background? (conj :background-job-ui-refresh-fn)
                                                  (:reinstalled? extension-run-reinstall) (conj :extension-run-fn))
                                   :pending     (cond-> []
                                                  extension-run-pending? (conj :extension-run-fn))
                                   :extension-run-fn (:reason extension-run-reinstall)}]
      (step-result :runtime-hooks status summary details))))

(defn- collect-limitations
  [{:keys [ctx session-id]}]
  (let [existing-run-fn? (some? (some-> ctx :extension-run-fn-atom deref))
        ai-model         (when (and ctx session-id existing-run-fn?)
                           (session-ai-model ctx session-id))]
    (cond-> []
      (and existing-run-fn? (nil? session-id))
      (conj (limitation :extension-run-fn
                        "Registered extension run fn could not be reinstalled because runtime refresh had no target session-id."
                        "Re-run runtime refresh with an explicit session-id or re-register extension run fn from the owning runtime/bootstrap path."))

      (and existing-run-fn? session-id (nil? ai-model))
      (conj (limitation :extension-run-fn
                        "Registered extension run fn could not be reinstalled because the target session has no usable model selection."
                        "Set a session model and re-run runtime refresh, or re-register extension run fn from the owning runtime/bootstrap path.")))))

(defn- overall-status
  [steps limitations]
  (cond
    (some #(= :error (:status %)) steps) :partial
    (or (seq limitations)
        (some #(= :partial (:status %)) steps)) :partial
    :else :ok))

(defn refresh-runtime!
  "Run the canonical post-reload runtime refresh pass.

   Options:
   - :ctx               live runtime ctx, when available
   - :session-id        invoking session id, when available
   - :worktree-path     effective worktree for extension rediscovery
   - :extension-refresh? when true, refresh extensions through the canonical
                         extension reload path; otherwise preserve the current
                         extension registry

   Returns a structured report:
   {:psi.runtime-refresh/status ...
    :psi.runtime-refresh/steps ...
    :psi.runtime-refresh/limitations ...
    :psi.runtime-refresh/duration-ms ...}"
  [{:keys [ctx] :as opts}]
  (let [started-at   (System/nanoTime)
        state*-before (:state* ctx)
        steps        [(capture-step :query-graph #(refresh-query-graph! ctx))
                      (capture-step :dispatch-handlers #(refresh-dispatch-handlers! ctx))
                      (capture-step :extensions #(refresh-extensions! opts))
                      (capture-step :runtime-hooks #(refresh-runtime-hooks! ctx (:session-id opts)))]
        limitations  (vec (collect-limitations opts))]
    {:psi.runtime-refresh/status      (overall-status steps limitations)
     :psi.runtime-refresh/steps       steps
     :psi.runtime-refresh/limitations limitations
     :psi.runtime-refresh/duration-ms (duration-ms started-at)
     :psi.runtime-refresh/details     {:step-order refresh-step-order
                                       :recreated-ctx? false
                                       :recreated-state*? (not (identical? state*-before (:state* ctx)))}}))
