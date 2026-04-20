(ns psi.agent-session.workflow-agent-chain-runtime
  "Runtime registration helpers for compiled legacy `agent-chain` definitions."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [psi.agent-session.workflow-agent-chain :as workflow-agent-chain]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn read-agent-chain-config
  [cwd]
  (let [path (str cwd "/.psi/agents/agent-chain.edn")
        f    (io/file path)]
    (if (.exists f)
      (try
        (let [data (edn/read-string (slurp f))]
          {:path   path
           :chains (if (vector? data) data [])
           :error  nil})
        (catch Exception e
          {:path   path
           :chains []
           :error  (ex-message e)}))
      {:path   path
       :chains []
       :error  nil})))

(defn register-agent-chain-definitions
  "Compile and register all legacy agent-chain definitions found under `cwd`.

   Returns [new-state report]."
  [state cwd]
  (let [{:keys [path chains error]} (read-agent-chain-config cwd)]
    (if error
      [state {:config-path     path
              :definition-ids  []
              :registered-count 0
              :error           error}]
      (let [definitions (workflow-agent-chain/chains->workflow-definitions chains)
            [new-state ids]
            (reduce (fn [[s acc] definition]
                      (let [[s* definition-id _] (workflow-runtime/register-definition s definition)]
                        [s* (conj acc definition-id)]))
                    [state []]
                    definitions)]
        [new-state {:config-path      path
                    :definition-ids   ids
                    :registered-count (count ids)
                    :error            nil}]))))

(defn register-agent-chain-definitions!
  [ctx]
  (let [cwd (or (:cwd ctx) (System/getProperty "user.dir"))
        [new-state report] (register-agent-chain-definitions @(:state* ctx) cwd)]
    ((:apply-root-state-update-fn ctx) ctx (constantly new-state))
    report))
