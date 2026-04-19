(ns psi.agent-session.runtime-refresh
  "Canonical post-code-reload runtime refresh pass.

   This pass is intentionally fixed-order and best-effort. It preserves normal
   runtime data by default while refreshing known executable wiring surfaces and
   reporting honest in-place refresh limitations."
  (:require
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

(defn- refresh-runtime-hooks!
  [ctx]
  (if-not ctx
    (step-result :runtime-hooks :ok "runtime hooks unchanged (no runtime ctx provided)")
    (let [installed-background? (boolean (maybe-install-background-job-ui-refresh! ctx))
          extension-run-present? (some? (some-> ctx :extension-run-fn-atom deref))
          status (if extension-run-present? :partial :ok)
          summary (if extension-run-present?
                    "reinstalled known runtime hooks with remaining extension run-fn limitations"
                    "reinstalled known runtime hooks")
          details {:reinstalled (cond-> []
                                  installed-background? (conj :background-job-ui-refresh-fn))
                   :pending     (cond-> []
                                  extension-run-present? (conj :extension-run-fn))}]
      (step-result :runtime-hooks status summary details))))

(defn- collect-limitations
  [{:keys [ctx]}]
  (cond-> []
    (some? (some-> ctx :extension-run-fn-atom deref))
    (conj (limitation :extension-run-fn
                      "Registered extension run fn may still capture pre-refresh runtime closures."
                      "Re-register extension run fn from the owning runtime/bootstrap path or restart the runtime if behavior remains mixed."))))

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
                      (capture-step :runtime-hooks #(refresh-runtime-hooks! ctx))]
        limitations  (vec (collect-limitations opts))]
    {:psi.runtime-refresh/status      (overall-status steps limitations)
     :psi.runtime-refresh/steps       steps
     :psi.runtime-refresh/limitations limitations
     :psi.runtime-refresh/duration-ms (duration-ms started-at)
     :psi.runtime-refresh/details     {:step-order refresh-step-order
                                       :recreated-ctx? false
                                       :recreated-state*? (not (identical? state*-before (:state* ctx)))}}))
