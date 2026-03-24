(ns psi.agent-session.startup-prompts
  "Startup prompt discovery + deterministic composition.

   Sources:
   1) ~/.psi/agent/startup-prompts.edn (global)
   2) .psi/startup-prompts.edn         (project)

   Precedence: global < project (project wins by :id)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def default-config
  {:enabled?             true
   :global-prompts-file  (str (System/getProperty "user.home") "/.psi/agent/startup-prompts.edn")
   :project-prompts-file ".psi/startup-prompts.edn"
   :run-on-new-root?     true
   :run-on-fork-head?    false
   :run-on-fork-at-entry? false
   :run-on-agent?     false})

(def ^:private phase-order
  {:system-bootstrap  0
   :project-bootstrap 1
   :mode-bootstrap    2})

(def ^:private source-order
  {:global  0
   :project 1})

(defn- normalize-phase [v]
  (let [k (cond
            (keyword? v) v
            (string? v)  (-> v str/lower-case (str/replace "_" "-") keyword)
            :else        :system-bootstrap)]
    (if (contains? phase-order k) k :system-bootstrap)))

(defn- normalize-bool [v default]
  (if (nil? v) default (boolean v)))

(defn- normalize-int [v default]
  (if (integer? v) v default))

(defn- normalize-rule [source idx rule]
  (let [id*       (or (:id rule)
                      (:name rule)
                      (str (name source) "-" idx))
        text      (or (:text rule) "")
        enabled?  (normalize-bool (if (contains? rule :enabled?)
                                    (:enabled? rule)
                                    (:enabled rule))
                                  true)
        priority  (normalize-int (:priority rule) 100)
        phase     (normalize-phase (:phase rule))
        conditions (when (map? (:conditions rule))
                     (:conditions rule))]
    {:id         (str id*)
     :phase      phase
     :text       (str text)
     :enabled?   enabled?
     :priority   priority
     :conditions conditions
     :source     source}))

(defn- safe-read-rules [f source]
  (try
    (if (and (.exists f) (.isFile f))
      (let [v (edn/read-string (slurp f))]
        (if (sequential? v)
          (->> v
               (keep-indexed (fn [idx rule]
                               (when (map? rule)
                                 (normalize-rule source idx rule))))
               vec)
          []))
      [])
    (catch Exception _
      [])))

(defn- conditions-match?
  [rule session-mode]
  (let [conds (:conditions rule)
        wanted-mode (some-> (get conds "mode") str/lower-case keyword)]
    (if wanted-mode
      (= wanted-mode session-mode)
      true)))

(defn discover-rules
  "Return deterministic startup rules for this cwd/session mode.

   opts:
   - :cwd
   - :session-mode (keyword, optional)
   - :enabled?
   - :global-prompts-file
   - :project-prompts-file"
  [{:keys [cwd session-mode enabled? global-prompts-file project-prompts-file]
    :or   {session-mode :build}}]
  (let [enabled?*     (if (nil? enabled?) (:enabled? default-config) (boolean enabled?))
        global-file   (io/file (or global-prompts-file (:global-prompts-file default-config)))
        project-path  (or project-prompts-file (:project-prompts-file default-config))
        project-file  (if (and cwd (not (.isAbsolute (io/file project-path))))
                        (io/file cwd project-path)
                        (io/file project-path))]
    (if-not enabled?*
      []
      (let [global-rules   (safe-read-rules global-file :global)
            project-rules  (safe-read-rules project-file :project)
            merged         (vals (merge (into {} (map (juxt :id identity) global-rules))
                                        (into {} (map (juxt :id identity) project-rules))))
            eligible       (->> merged
                                (filter :enabled?)
                                (filter #(conditions-match? % session-mode)))
            by-order       (fn [r]
                             [(get phase-order (:phase r) 0)
                              (:priority r)
                              (get source-order (:source r) 0)
                              (:id r)])]
        (->> eligible
             (sort-by by-order)
             vec)))))

(defn should-run?
  "Return true when startup prompts should run for `spawn-mode`.
   spawn-mode values: :new-root | :fork-head | :fork-at-entry | :agent"
  [{:keys [spawn-mode run-on-new-root? run-on-fork-head? run-on-fork-at-entry? run-on-agent?]
    :or   {run-on-new-root?      (:run-on-new-root? default-config)
           run-on-fork-head?     (:run-on-fork-head? default-config)
           run-on-fork-at-entry? (:run-on-fork-at-entry? default-config)
           run-on-agent?      (:run-on-agent? default-config)}}]
  (case spawn-mode
    :fork-head     (boolean run-on-fork-head?)
    :fork-at-entry (boolean run-on-fork-at-entry?)
    :agent      (boolean run-on-agent?)
    ;; default/new-root
    (boolean run-on-new-root?)))

(defn applied-view
  "Projection for persisted bootstrap telemetry."
  [rules]
  (mapv (fn [r]
          {:id (:id r)
           :source (:source r)
           :phase (:phase r)
           :priority (:priority r)})
        rules))
