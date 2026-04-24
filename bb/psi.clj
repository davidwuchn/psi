#!/usr/bin/env bb

(ns psi
  (:require
   [babashka.process :as process]
   [clojure.string :as str]
   [psi.launcher :as launcher]))

(defn- default-policy
  []
  (if (= (.getAbsolutePath (java.io.File. "."))
         (.getAbsolutePath (java.io.File. (System/getProperty "user.dir"))))
    :development
    :development))

(defn- launcher-root
  []
  (-> (java.io.File. (System/getProperty "babashka.file"))
      .getParentFile
      .getParentFile
      .getAbsolutePath))

(defn- print-debug-summary!
  [{:keys [cwd psi-args command basis manifest-info policy]}]
  (binding [*out* *err*]
    (println "psi launcher")
    (println (str "  cwd: " cwd))
    (println (str "  policy: " (name policy)))
    (println (str "  user manifest present: " (:user-present? manifest-info)))
    (println (str "  project manifest present: " (:project-present? manifest-info)))
    (println (str "  merged manifest libs: " (pr-str (sort (keys (get-in manifest-info [:merged-manifest :deps]))))))
    (println (str "  psi-owned defaults: " (pr-str (sort (:defaulted-libs manifest-info)))))
    (println (str "  inferred :psi/init libs: " (pr-str (sort (:inferred-init-libs manifest-info)))))
    (println (str "  basis deps count: " (count (:deps basis))))
    (println (str "  forwarded psi args: " (pr-str psi-args)))
    (println (str "  command: " (str/join " " command)))))

(defn -main
  [& args]
  (let [plan (launcher/launch-plan args
                                   (System/getProperty "user.dir")
                                   (launcher-root)
                                   (default-policy))]
    (when (:launcher-debug? plan)
      (print-debug-summary! plan))
    @(process/process (:command plan)
                      {:inherit true
                       :dir (:cwd plan)})))

(apply -main *command-line-args*)
