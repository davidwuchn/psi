(ns psi.main
  "Polylith base entry point for psi.

   Parses CLI flags, selects an interface, and delegates to the session app runtime."
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.agent-session.app-runtime :as session-app]
   [psi.tui.app :as tui-app])
  (:gen-class))

(def ^:private valid-log-levels
  #{:trace :debug :info :warn :error :fatal :report})

(def ^:private default-model-key :sonnet-4.6)

(defn- has-flag?
  [args flag]
  (some #(= flag %) args))

(defn- arg-value
  [args flag]
  (second (drop-while #(not= flag %) args)))

(defn- parse-bool-arg
  [v]
  (when (string? v)
    (let [x (str/lower-case (str/trim v))]
      (cond
        (contains? #{"1" "true" "yes" "y" "on"} x) true
        (contains? #{"0" "false" "no" "n" "off"} x) false
        :else nil))))

(defn- parse-positive-int-arg
  [v]
  (when (string? v)
    (try
      (let [n (Integer/parseInt (str/trim v))]
        (when (pos? n) n))
      (catch Exception _
        nil))))

(defn- set-log-level!
  [level-str]
  (let [kw    (keyword (str/lower-case (or level-str "info")))
        level (if (valid-log-levels kw) kw :info)]
    (timbre/set-min-level! level)))

(defn- log-level-from-args
  [args]
  (or (arg-value args "--log-level")
      "INFO"))

(defn- model-key-from-args
  [args]
  (let [env-model (System/getenv "PSI_MODEL")]
    (keyword
     (or (arg-value args "--model")
         env-model
         (name default-model-key)))))

(defn- nrepl-port-from-args
  [args]
  (when (has-flag? args "--nrepl")
    (let [after (second (drop-while #(not= "--nrepl" %) args))]
      (if (and after (re-matches #"\d+" after))
        (Integer/parseInt after)
        0))))

(defn- rpc-trace-file-from-args
  [args]
  (let [v (arg-value args "--rpc-trace-file")]
    (when-not (str/blank? v)
      v)))

(defn- memory-runtime-opts-from-args
  [args]
  (let [store-provider      (arg-value args "--memory-store")
        fallback            (parse-bool-arg (arg-value args "--memory-store-fallback"))
        history-limit       (parse-positive-int-arg (arg-value args "--memory-history-limit"))
        retention-snapshots (parse-positive-int-arg (arg-value args "--memory-retention-snapshots"))
        retention-deltas    (parse-positive-int-arg (arg-value args "--memory-retention-deltas"))]
    (cond-> {}
      (some? store-provider)      (assoc :store-provider store-provider)
      (some? fallback)            (assoc :auto-store-fallback? fallback)
      (some? history-limit)       (assoc :history-commit-limit history-limit)
      (some? retention-snapshots) (assoc :retention-snapshots retention-snapshots)
      (some? retention-deltas)    (assoc :retention-deltas retention-deltas))))

(defn- llm-idle-timeout-ms-from-env
  []
  (parse-positive-int-arg (System/getenv "PSI_LLM_IDLE_TIMEOUT_MS")))

(defn- session-runtime-config-from-args
  [args]
  (let [idle-timeout-arg-raw (arg-value args "--llm-idle-timeout-ms")
        idle-timeout-arg     (parse-positive-int-arg idle-timeout-arg-raw)
        idle-timeout-ms      (if (some? idle-timeout-arg-raw)
                               idle-timeout-arg
                               (llm-idle-timeout-ms-from-env))]
    (cond-> {}
      (some? idle-timeout-ms) (assoc :llm-stream-idle-timeout-ms idle-timeout-ms))))

(defn run-console-session!
  [args]
  (let [model-key            (model-key-from-args args)
        memory-runtime-opts  (memory-runtime-opts-from-args args)
        session-runtime-opts (session-runtime-config-from-args args)
        nrepl-port           (nrepl-port-from-args args)
        nrepl-srv            (when nrepl-port
                               (session-app/start-nrepl! nrepl-port))]
    (try
      (session-app/run-session model-key memory-runtime-opts session-runtime-opts)
      (finally
        (session-app/stop-nrepl! nrepl-srv)))))

(defn run-rpc-session!
  [args]
  (let [model-key            (model-key-from-args args)
        memory-runtime-opts  (memory-runtime-opts-from-args args)
        session-runtime-opts (session-runtime-config-from-args args)
        rpc-trace-file       (rpc-trace-file-from-args args)
        nrepl-port           (nrepl-port-from-args args)
        nrepl-srv            (when nrepl-port
                               (binding [*out* *err*]
                                 (session-app/start-nrepl! nrepl-port)))]
    (try
      (session-app/start-rpc-runtime! model-key memory-runtime-opts session-runtime-opts
                                      {:rpc-trace-file rpc-trace-file})
      (finally
        (session-app/stop-nrepl! nrepl-srv)))))

(defn run-tui-session!
  [args]
  (let [model-key            (model-key-from-args args)
        memory-runtime-opts  (memory-runtime-opts-from-args args)
        session-runtime-opts (session-runtime-config-from-args args)
        nrepl-port           (nrepl-port-from-args args)
        nrepl-srv            (when nrepl-port
                               (session-app/start-nrepl! nrepl-port))]
    (try
      (session-app/start-tui-runtime! tui-app/start! model-key memory-runtime-opts session-runtime-opts)
      (finally
        (session-app/stop-nrepl! nrepl-srv)))))

(defn- exit!
  [code]
  (System/exit code))

(defn -main
  [& args]
  (set-log-level! (log-level-from-args args))
  (let [tui?     (has-flag? args "--tui")
        rpc-edn? (has-flag? args "--rpc-edn")]
    (cond
      tui?
      (run-tui-session! args)

      rpc-edn?
      (run-rpc-session! args)

      :else
      (run-console-session! args))
    (exit! 0)))
