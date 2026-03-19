(ns psi.agent-session.test-support
  "Helpers for canonical-root-backed agent-session test contexts."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.statechart :as session-sc]
   [psi.agent-session.workflows :as wf]
   [psi.ui.state :as ui-state]))

(defn set-state!
  [ctx k value]
  (session/assoc-state-value-in! ctx (session/state-path k) value)
  ctx)

(defn update-state!
  [ctx k f & args]
  (apply session/update-state-value-in! ctx (session/state-path k) f args)
  ctx)

(defn- state-backed-atom-view
  [ctx path]
  (reify
    clojure.lang.IDeref
    (deref [_] (session/get-state-value-in ctx path))
    clojure.lang.IAtom
    (compareAndSet [_ oldv newv]
      (let [curv (session/get-state-value-in ctx path)]
        (if (= curv oldv)
          (do
            (session/assoc-state-value-in! ctx path newv)
            true)
          false)))
    (reset [_ newv]
      (session/assoc-state-value-in! ctx path newv)
      newv)
    (swap [_ f]
      (let [newv (f (session/get-state-value-in ctx path))]
        (session/assoc-state-value-in! ctx path newv)
        newv))
    (swap [_ f a]
      (let [newv (f (session/get-state-value-in ctx path) a)]
        (session/assoc-state-value-in! ctx path newv)
        newv))
    (swap [_ f a b]
      (let [newv (f (session/get-state-value-in ctx path) a b)]
        (session/assoc-state-value-in! ctx path newv)
        newv))
    (swap [_ f a b xs]
      (let [newv (apply f (session/get-state-value-in ctx path) a b xs)]
        (session/assoc-state-value-in! ctx path newv)
        newv))))

(defn make-session-ctx
  "Create a minimal canonical-root-backed session-like context for tests.
   Accepts overrides:
   - :state map merged into canonical root
   - :session-data map merged into [:agent-session :data]
   - :agent-ctx custom agent ctx"
  [{:keys [state session-data agent-ctx]}]
  (let [agent-ctx*    (or agent-ctx (agent/create-context))
        base-state    {:agent-session {:data (merge (assoc (session-data/initial-session {})
                                                           :provider-error-replies [])
                                                    (or session-data {}))
                                       :context-index {}}
                       :telemetry {:tool-output-stats {:calls []
                                                       :aggregates {:total-context-bytes 0
                                                                    :by-tool {}
                                                                    :limit-hits-by-tool {}}}
                                   :tool-call-attempts []
                                   :provider-requests []
                                   :provider-replies []}
                       :persistence {:journal []
                                     :flush-state {:flushed? false :session-file nil}}
                       :turn {:ctx nil}
                       :background-jobs {:store (bg-jobs/empty-state)}
                       :ui {:extension-ui @(ui-state/create-ui-state)}}
        state*        (atom (merge base-state (or state {})))
        ext-reg       (ext/create-registry)
        wf-reg        (wf/create-registry)
        sc-env        (session-sc/create-sc-env)
        sc-session-id (java.util.UUID/randomUUID)
        ctx0          {:agent-ctx             agent-ctx*
                       :state*                state*
                       :sc-env                sc-env
                       :sc-session-id         sc-session-id
                       :config                {}
                       :extension-registry    ext-reg
                       :workflow-registry     wf-reg
                       :extension-run-fn-atom (atom nil)
                       :ui-state-atom         nil
                       :persist?              false}
        ctx           (assoc ctx0
                             :session-data-atom           (state-backed-atom-view ctx0 (session/state-path :session-data))
                             :context-index-atom          (state-backed-atom-view ctx0 (session/state-path :context-index))
                             :tool-output-stats-atom      (state-backed-atom-view ctx0 (session/state-path :tool-output-stats))
                             :tool-call-attempts-atom     (state-backed-atom-view ctx0 (session/state-path :tool-call-attempts))
                             :provider-requests-atom      (state-backed-atom-view ctx0 (session/state-path :provider-requests))
                             :provider-replies-atom       (state-backed-atom-view ctx0 (session/state-path :provider-replies))
                             :provider-error-replies-atom (state-backed-atom-view ctx0 [:agent-session :data :provider-error-replies])
                             :journal-atom                (state-backed-atom-view ctx0 (session/state-path :journal))
                             :flush-state-atom            (state-backed-atom-view ctx0 (session/state-path :flush-state))
                             :turn-ctx-atom               (state-backed-atom-view ctx0 (session/state-path :turn-ctx))
                             :background-jobs-atom        (state-backed-atom-view ctx0 (session/state-path :background-jobs))
                             :ui-state-atom               (state-backed-atom-view ctx0 (session/state-path :ui-state)))]
    (session-sc/start-session! sc-env sc-session-id
                               {:session-data-atom (:session-data-atom ctx)
                                :actions-fn        nil
                                :config            {}})
    ctx))
