(ns psi.agent-session.test-support
  "Helpers for canonical-root-backed agent-session test contexts."
  (:require
   [psi.agent-session.core :as session]
   [psi.agent-session.background-jobs :as bg-jobs]))

(defn set-state!
  [ctx k value]
  (session/assoc-state-value-in! ctx (session/state-path k) value)
  ctx)

(defn update-state!
  [ctx k f & args]
  (apply session/update-state-value-in! ctx (session/state-path k) f args)
  ctx)

(defn make-session-ctx
  "Create a minimal canonical-root-backed session-like context for tests.
   Accepts overrides:
   - :state map merged into canonical root
   - :session-data map merged into [:agent-session :data]
   - :agent-ctx custom agent ctx"
  [{:keys [state session-data agent-ctx]}]
  (let [base-state {:agent-session {:data (merge {:tool-output-overrides {}
                                                  :thinking-level :off}
                                                 (or session-data {}))}
                    :telemetry {:tool-output-stats {:calls []
                                                    :aggregates {:total-context-bytes 0
                                                                 :by-tool {}
                                                                 :limit-hits-by-tool {}}}
                                :tool-call-attempts []
                                :provider-requests []
                                :provider-replies []}
                    :turn {:ctx nil}
                    :background-jobs {:store (bg-jobs/empty-state)}}]
    {:agent-ctx agent-ctx
     :state*    (atom (merge base-state (or state {})))}))
