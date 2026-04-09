(ns psi.agent-session.tool-defs
  "Helpers for normalizing session/extension tool maps into the stricter
   agent-core runtime shape.")

(defn- stringify-parameters [parameters]
  (cond
    (string? parameters) parameters
    (nil? parameters) "{}"
    :else (pr-str parameters)))

(defn agent-core-tool
  "Project a possibly richer tool map into the minimal shape required by
   psi.agent-core.core/agent-state-schema. Returns nil when :name is blank."
  [tool]
  (when-let [name (some-> (:name tool) str not-empty)]
    {:name        name
     :label       (or (some-> (:label tool) str not-empty)
                      name)
     :description (or (some-> (:description tool) str)
                      "")
     :parameters  (stringify-parameters (:parameters tool))}))

(defn agent-core-tools
  [tools]
  (mapv agent-core-tool (filter map? (or tools []))))
