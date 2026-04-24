(ns psi.launcher
  "Launcher-side CLI parsing and command construction for psi startup."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- blank-path?
  [x]
  (or (nil? x)
      (str/blank? x)))

(defn parse-launcher-args
  "Split launcher-owned args from psi runtime args.

   Consumes:
   - --cwd <path>
   - --launcher-debug

   Returns {:cwd string? :launcher-debug? boolean :psi-args [...] }.
   Throws ex-info on malformed launcher-owned args."
  [args]
  (loop [remaining args
         parsed    {:cwd nil
                    :launcher-debug? false
                    :psi-args []}]
    (if-let [arg (first remaining)]
      (cond
        (= "--cwd" arg)
        (let [cwd (second remaining)]
          (when (blank-path? cwd)
            (throw (ex-info "Missing value for --cwd"
                            {:arg arg
                             :args remaining})))
          (recur (nnext remaining) (assoc parsed :cwd cwd)))

        (= "--launcher-debug" arg)
        (recur (next remaining) (assoc parsed :launcher-debug? true))

        :else
        (recur (next remaining) (update parsed :psi-args conj arg)))
      parsed)))

(defn resolve-effective-cwd
  "Resolve the launcher working directory. Relative overrides are resolved
   against the launcher process cwd."
  [{:keys [cwd]} process-cwd]
  (let [candidate (or cwd process-cwd)]
    (.getAbsolutePath (io/file process-cwd candidate))))

(defn build-clojure-command
  "Build the clojure CLI argv used for launcher handoff.

   Slice 1 intentionally keeps the startup handoff minimal and repo-local:
   launch psi via the project deps.edn without relying on a user-defined alias."
  [{:keys [psi-args]}]
  (into ["clojure" "-M" "-m" "psi.main"] psi-args))

(defn launch-plan
  "Build a pure launcher execution plan from raw args and process cwd."
  [args process-cwd]
  (let [parsed (parse-launcher-args args)]
    {:cwd             (resolve-effective-cwd parsed process-cwd)
     :launcher-debug? (:launcher-debug? parsed)
     :psi-args        (:psi-args parsed)
     :command         (build-clojure-command parsed)}))
