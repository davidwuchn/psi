#!/usr/bin/env bb

(ns psi
  (:require
   [babashka.process :as process]
   [clojure.string :as str]
   [psi.launcher :as launcher]))

(defn- print-debug-summary!
  [{:keys [cwd psi-args command]}]
  (binding [*out* *err*]
    (println "psi launcher")
    (println (str "  cwd: " cwd))
    (println (str "  forwarded psi args: " (pr-str psi-args)))
    (println (str "  command: " (str/join " " command)))))

(defn -main
  [& args]
  (let [plan (launcher/launch-plan args (System/getProperty "user.dir"))]
    (when (:launcher-debug? plan)
      (print-debug-summary! plan))
    @(process/process (:command plan)
                      {:inherit true
                       :dir (:cwd plan)})))

(apply -main *command-line-args*)
