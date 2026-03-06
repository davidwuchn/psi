(ns psi.agent-session.project-preferences
  "Project-local AgentSession preferences.

   Stored at: <cwd>/.psi/project.edn

   Shape:
   {:version 1
    :agent-session {:model-provider string?
                    :model-id string?
                    :thinking-level keyword?}}"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def ^:private current-version 1)

(def ^:private default-prefs
  {:version current-version
   :agent-session {}})

(defn project-preferences-file
  [cwd]
  (io/file (str cwd) ".psi" "project.edn"))

(defn read-preferences
  "Best-effort read. Returns default map on missing/invalid file."
  [cwd]
  (let [f (project-preferences-file cwd)]
    (try
      (if (and (.exists f) (.isFile f))
        (let [v (edn/read-string (slurp f))]
          (if (map? v)
            (merge default-prefs v)
            default-prefs))
        default-prefs)
      (catch Exception _
        default-prefs))))

(defn- write-preferences!
  [cwd prefs]
  (let [f (project-preferences-file cwd)
        parent (.getParentFile f)]
    (when parent
      (.mkdirs parent))
    (spit f (pr-str prefs))
    prefs))

(defn update-agent-session!
  "Merge `m` into :agent-session and write to disk."
  [cwd m]
  (let [prefs (read-preferences cwd)]
    (write-preferences! cwd (update prefs :agent-session merge m))))

(defn project-model
  [prefs]
  (let [provider (get-in prefs [:agent-session :model-provider])
        model-id (get-in prefs [:agent-session :model-id])]
    (when (and (string? provider) (string? model-id))
      {:provider provider :id model-id})))

(defn project-thinking-level
  [prefs]
  (let [v (get-in prefs [:agent-session :thinking-level])]
    (when (keyword? v)
      v)))
