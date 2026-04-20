(ns psi.agent-session.workflow-agent-chain
  "Pure compilation of legacy `agent-chain` config into canonical workflow definitions.

   This keeps `agent-chain` as an authoring/compatibility surface while targeting
   the canonical deterministic workflow runtime introduced in task 026."
  (:require
   [clojure.string :as str]))

(def ^:private default-result-schema
  [:map
   [:outcome [:= :ok]]
   [:outputs [:map [:text :string]]]])

(def ^:private default-retry-policy
  {:max-attempts 1
   :retry-on #{:execution-failed :validation-failed}})

(defn- kebab-fragment
  [x]
  (-> (str x)
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")
      not-empty
      (or "step")))

(defn chain-step-id
  [idx {:keys [agent]}]
  (str "step-" (inc idx) "-" (kebab-fragment (or agent "agent"))))

(defn chain-step->workflow-step
  [{:keys [step-id previous-step-id step]}]
  (cond-> {:label (or (:agent step) step-id)
           :executor {:type :agent
                      :profile (:agent step)}
           :prompt-template (:prompt step)
           :input-bindings (cond-> {:original {:source :workflow-input
                                               :path [:original]}}
                             previous-step-id
                             (assoc :input {:source :step-output
                                            :path [previous-step-id :outputs :text]})

                             (nil? previous-step-id)
                             (assoc :input {:source :workflow-input
                                            :path [:input]}))
           :result-schema default-result-schema
           :retry-policy default-retry-policy}
    (:agent step) (assoc :description (str "Run agent profile `" (:agent step) "`."))))

(defn chain->workflow-definition
  [chain]
  (let [steps      (vec (:steps chain))
        step-order (mapv chain-step-id (range) steps)
        step-map   (into {}
                         (map-indexed
                          (fn [idx step]
                            (let [step-id          (nth step-order idx)
                                  previous-step-id (when (pos? idx)
                                                     (nth step-order (dec idx)))]
                              [step-id (chain-step->workflow-step {:step-id step-id
                                                                   :previous-step-id previous-step-id
                                                                   :step step})])))
                         steps)]
    {:definition-id (:name chain)
     :name (:name chain)
     :summary (:description chain)
     :description (str "Compiled from legacy agent-chain definition `" (:name chain) "`.")
     :step-order step-order
     :steps step-map}))

(defn chains->workflow-definitions
  [chains]
  (mapv chain->workflow-definition (or chains [])))
