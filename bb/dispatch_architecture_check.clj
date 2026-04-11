(ns bb.dispatch-architecture-check
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]))

(def ^:private schema-file
  "components/agent-session/src/psi/agent_session/dispatch_schema.clj")

(def ^:private effects-file
  "components/agent-session/src/psi/agent_session/dispatch_effects.clj")

(def ^:private emitted-effect-roots
  ["components/agent-session/src"])

(def ^:private handler-root
  "components/agent-session/src/psi/agent_session/dispatch_handlers")

(def ^:private state-write-roots
  ["components/agent-session/src/psi/agent_session"])

(def ^:private allowlisted-state-write-files
  #{"components/agent-session/src/psi/agent_session/session.clj"
    "components/agent-session/src/psi/agent_session/session_lifecycle.clj"
    "components/agent-session/src/psi/agent_session/session_state.clj"
    "components/agent-session/src/psi/agent_session/persistence.clj"})

(def ^:private handler-side-effect-patterns
  [{:label "direct agent/*-in! side effect"
    :regex #"(agent/[A-Za-z0-9*+!?'\\-]+-in!)"}
   {:label "direct statechart send"
    :regex #"(sc/send-event!)"}
   {:label "direct extension dispatch"
    :regex #"(ext/dispatch-in)"}
   {:label "direct journal append"
    :regex #"(session/journal-append-in!)"}
   {:label "direct canonical state write"
    :regex #"(\((?:swap!|reset!)\s+\(:state\*\s+ctx\))"}])

(def ^:private state-write-patterns
  [{:label "direct canonical state swap"
    :regex #"(\(swap!\s+\(:state\*\s+ctx)"}
   {:label "direct canonical state reset"
    :regex #"(\(reset!\s+\(:state\*\s+ctx)"}
   {:label "direct canonical state swap via helper"
    :regex #"(\(swap!\s+\(state\*\s+ctx)"}
   {:label "direct canonical state reset via helper"
    :regex #"(\(reset!\s+\(state\*\s+ctx)"}])

(defn- clj-file? [^java.io.File f]
  (and (.isFile f)
       (str/ends-with? (.getName f) ".clj")))

(defn- files-under [root]
  (let [dir (io/file root)]
    (if (.exists dir)
      (->> (file-seq dir)
           (filter clj-file?)
           (mapv #(.getPath ^java.io.File %)))
      [])))

(defn- file-lines [path]
  (->> (slurp path)
       str/split-lines
       vec))

(defn- re-seq-group-1 [re s]
  (map second (re-seq re s)))

(defn- kw-sort [xs]
  (sort-by str xs))

(defn- format-kw-list [xs]
  (->> xs kw-sort (map str) (str/join ", ")))

(defn- effect-types-from-schema []
  (->> (slurp schema-file)
       (re-seq-group-1 #":effect/type \[:= (:[^\]\s]+)\]")
       (map keyword)
       set))

(defn- effect-types-from-executors []
  (->> (slurp effects-file)
       (re-seq-group-1 #"\(defmethod execute-effect! (:[^\s\]]+)")
       (remove #(= % ":default"))
       (map keyword)
       set))

(defn- emitted-effect-index []
  (let [files (->> emitted-effect-roots (mapcat files-under) (remove #{schema-file effects-file}) distinct sort)]
    (reduce (fn [idx path]
              (let [content (slurp path)
                    effects (->> (re-seq-group-1 #":effect/type (:[^\s\}\]]+)" content)
                                 (map keyword)
                                 set)]
                (reduce (fn [m effect-type]
                          (update m effect-type (fnil conj []) path))
                        idx
                        effects)))
            {}
            files)))

(defn- find-line-matches [path patterns]
  (->> (file-lines path)
       (map-indexed (fn [idx line]
                      (keep (fn [{:keys [label regex]}]
                              (when-let [[_ match] (re-find regex line)]
                                {:path path
                                 :line (inc idx)
                                 :label label
                                 :match match
                                 :text (str/trim line)}))
                            patterns)))
       (apply concat)
       vec))

(defn- handler-side-effect-matches []
  (->> (files-under handler-root)
       (mapcat #(find-line-matches % handler-side-effect-patterns))
       vec))

(defn- state-write-boundary-matches []
  (->> state-write-roots
       (mapcat files-under)
       (remove allowlisted-state-write-files)
       (mapcat #(find-line-matches % state-write-patterns))
       vec))

(defn- print-section [title]
  (println)
  (println title))

(defn- print-lines [lines]
  (doseq [line lines]
    (println line)))

(defn- parity-report []
  (let [schema-types   (effect-types-from-schema)
        executor-types (effect-types-from-executors)
        emitted-index  (emitted-effect-index)
        emitted-types  (set (keys emitted-index))
        emitted-missing-schema   (set/difference emitted-types schema-types)
        emitted-missing-executor (set/difference emitted-types executor-types)
        schema-missing-executor  (set/difference schema-types executor-types)
        executor-missing-schema  (set/difference executor-types schema-types)]
    {:schema-types schema-types
     :executor-types executor-types
     :emitted-index emitted-index
     :emitted-types emitted-types
     :emitted-missing-schema emitted-missing-schema
     :emitted-missing-executor emitted-missing-executor
     :schema-missing-executor schema-missing-executor
     :executor-missing-schema executor-missing-schema}))

(defn- print-parity-report [{:keys [schema-types executor-types emitted-types emitted-index
                                    emitted-missing-schema emitted-missing-executor
                                    schema-missing-executor executor-missing-schema]}]
  (let [failures (concat
                  (for [effect-type (kw-sort emitted-missing-schema)]
                    (str "  - emitted without schema: " effect-type
                         " @ " (str/join ", " (sort (get emitted-index effect-type)))))
                  (for [effect-type (kw-sort emitted-missing-executor)]
                    (str "  - emitted without executor: " effect-type
                         " @ " (str/join ", " (sort (get emitted-index effect-type)))))
                  (for [effect-type (kw-sort schema-missing-executor)]
                    (str "  - schema without executor: " effect-type))
                  (for [effect-type (kw-sort executor-missing-schema)]
                    (str "  - executor without schema: " effect-type)))]
    (print-section "Effect parity")
    (println (format "  schema=%d executor=%d emitted=%d"
                     (count schema-types)
                     (count executor-types)
                     (count emitted-types)))
    (if (seq failures)
      (do
        (println "  FAIL")
        (print-lines failures)
        false)
      (do
        (println "  PASS")
        true))))

(defn- print-advisory-report [title matches]
  (print-section title)
  (if (seq matches)
    (do
      (println (format "  WARN (%d)" (count matches)))
      (doseq [{:keys [path line label match]} matches]
        (println (str "  - " path ":" line " " label " → " match))))
    (println "  PASS")))

(defn run!
  "Run the psi dispatch architecture check.

   Current semantics:
   - effect parity failures exit non-zero
   - handler side-effect and state-write checks are advisory"
  [_args]
  (let [parity                (parity-report)
        parity-ok?            (print-parity-report parity)
        handler-warnings      (handler-side-effect-matches)
        state-write-warnings  (state-write-boundary-matches)
        warning-count         (+ (count handler-warnings) (count state-write-warnings))
        failure-count         (count (concat (:emitted-missing-schema parity)
                                             (:emitted-missing-executor parity)
                                             (:schema-missing-executor parity)
                                             (:executor-missing-schema parity)))]
    (print-advisory-report "Dispatch handler side-effect candidates (advisory)" handler-warnings)
    (print-advisory-report "Canonical state write boundary (advisory)" state-write-warnings)
    (print-section "Summary")
    (println (format "  failures=%d warnings=%d" failure-count warning-count))
    (println "  advisory checks: handler purity, state write boundary")
    (if parity-ok? 0 1)))

(defn -main [& args]
  (System/exit (run! args)))
