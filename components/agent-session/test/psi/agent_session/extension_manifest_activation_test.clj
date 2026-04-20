(ns psi.agent-session.extension-manifest-activation-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.extension-installs :as installs]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.test-support :as test-support])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-extension-manifest-activation-test-"
                                      (into-array FileAttribute []))))

(defn- manifest-file
  [root rel]
  (let [f (io/file root rel)]
    (.mkdirs (.getParentFile f))
    f))

(defn- write-local-extension!
  [root ns-sym body]
  (let [src-dir  (io/file root "src")
        rel-path (str (-> (str ns-sym)
                          (clojure.string/replace "." "/")
                          (clojure.string/replace "-" "_"))
                      ".clj")
        f        (io/file src-dir rel-path)]
    (.mkdirs (.getParentFile f))
    (spit (io/file root "deps.edn") (pr-str {:paths ["src"]}))
    (spit f body)
    f))

(deftest reload-extensions-loads-manifest-local-root-extension-test
  (let [cwd        (test-support/temp-cwd)
        home       (tmp-dir)
        ext-root   (tmp-dir)
        [ctx sid]  (test-support/create-test-session {:persist? false :cwd cwd})
        _          (write-local-extension!
                    ext-root
                    'psi.test-manifest-ext
                    "(ns psi.test-manifest-ext)\n\n(defn init [api]\n  ((:register-command api) \"manifest-hello\" {:description \"hello from manifest\" :handler (fn [_] nil)}))\n")]
    (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                  installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
      (spit (installs/project-manifest-file cwd)
            (pr-str {:deps {'psi/test-manifest-ext {:local/root (.getAbsolutePath ext-root)
                                                    :psi/init 'psi.test-manifest-ext/init}}}))
      (is (string? (:path (installs/resolve-local-root-entry
                           'psi/test-manifest-ext
                           {:dep {:local/root (.getAbsolutePath ext-root)
                                  :psi/init 'psi.test-manifest-ext/init}}))))
      (let [result (ext-rt/reload-extensions-in! ctx sid [] cwd)]
        (is (= :loaded
               (get-in result [:install-state :psi.extensions/effective :entries-by-lib 'psi/test-manifest-ext :status])))
        (is (= :applied
               (get-in result [:install-state :psi.extensions/last-apply :status])))))))

(deftest reload-extensions-keeps-git-manifest-entry-at-restart-required-test
  (let [cwd       (test-support/temp-cwd)
        home      (tmp-dir)
        [ctx sid] (test-support/create-test-session {:persist? false :cwd cwd})]
    (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                  installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
      (spit (installs/project-manifest-file cwd)
            (pr-str {:deps {'psi/test-remote-ext {:git/url "https://example.com/ext"
                                                  :git/sha "abc123"
                                                  :psi/init 'psi.test.remote-ext/init}}}))
      (let [result (ext-rt/reload-extensions-in! ctx sid [] cwd)]
        (is (= :restart-required
               (get-in result [:install-state :psi.extensions/effective :entries-by-lib 'psi/test-remote-ext :status])))
        (is (= :restart-required
               (get-in result [:install-state :psi.extensions/last-apply :status])))
        (is (some #(= :restart-required (:category %))
                  (get-in result [:install-state :psi.extensions/diagnostics])))))))
