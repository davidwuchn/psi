(ns psi.extension-install-startup-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.extension-installs :as installs]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.test-support :as test-support]
   [psi.app-runtime :as app-runtime]))

(defn- manifest-file [root rel]
  (let [f (io/file root rel)]
    (.mkdirs (.getParentFile f))
    f))

(defn- write-local-extension! [root ns-sym body]
  (let [src-dir  (io/file root "src")
        rel-path (str (-> (str ns-sym)
                          (str/replace "." "/")
                          (str/replace "-" "_"))
                      ".clj")
        f        (io/file src-dir rel-path)]
    (.mkdirs (.getParentFile f))
    (spit (io/file root "deps.edn") (pr-str {:paths ["src"]}))
    (spit f body)
    f))

(deftest startup-persists-install-state-and-loads-manifest-extension-paths-test
  (testing "bootstrap startup computes install state and loads manifest-backed local-root extensions"
    (let [cwd             (test-support/temp-cwd)
          home            (test-support/temp-cwd)
          ext-root        (test-support/temp-cwd)
          _               (write-local-extension! ext-root
                                                 'psi.test.startup-ext
                                                 "(ns psi.test.startup-ext)\n\n(defn init [api]\n  ((:register-command api) \"startup-hello\" {:description \"hello from startup manifest\" :handler (fn [_] nil)}))\n")
          {:keys [ctx summary]} (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                                             installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
                                (spit (installs/project-manifest-file cwd)
                                      (pr-str {:deps {'psi/test-startup-ext {:local/root ext-root
                                                                             :psi/init 'psi.test.startup-ext/init}}}))
                                (app-runtime/bootstrap-runtime-session! {:provider :anthropic :id "claude-sonnet-4-6" :supports-reasoning true} {:cwd cwd}))]
      (is (= 1 (:extension-loaded-count summary)))
      (is (some #(re-find #"startup_ext.clj" %)
                (map str (ext/extensions-in (:extension-registry ctx)))))
      (is (= :configured
             (get-in (installs/extension-installs-state-in ctx)
                     [:psi.extensions/effective :entries-by-lib 'psi/test-startup-ext :status]))))))

(deftest reload-extension-installs-applies-manifest-local-root-in-project-nrepl-friendly-runtime-test
  (testing "reload/apply still loads manifest-backed local-root extensions after startup state persistence"
    (let [cwd        (test-support/temp-cwd)
          home       (test-support/temp-cwd)
          ext-root   (test-support/temp-cwd)
          _          (write-local-extension! ext-root
                                            'psi.test.reload-ext
                                            "(ns psi.test.reload-ext)\n\n(defn init [api]\n  ((:register-command api) \"reload-hello\" {:description \"hello from reload manifest\" :handler (fn [_] nil)}))\n")
          [ctx sid]  (test-support/create-test-session {:persist? false :cwd cwd})]
      (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                    installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
        (spit (installs/project-manifest-file cwd)
              (pr-str {:deps {'psi/test-reload-ext {:local/root ext-root
                                                   :psi/init 'psi.test.reload-ext/init}}}))
        (installs/persist-install-state-in! ctx (installs/compute-install-state cwd))
        (let [result (ext-rt/reload-extensions-in! ctx sid [] cwd)]
          (is (= :loaded
                 (get-in result [:install-state :psi.extensions/effective :entries-by-lib 'psi/test-reload-ext :status])))
          (is (= :applied
                 (get-in result [:install-state :psi.extensions/last-apply :status]))))))))
