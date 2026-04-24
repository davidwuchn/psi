(ns psi.launcher-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.launcher :as launcher]))

(deftest parse-launcher-args-test
  (testing "consumes launcher-owned flags and preserves psi arg order"
    (is (= {:cwd "/tmp/work"
            :launcher-debug? true
            :psi-args ["--tui" "--model" "gpt-5"]}
           (launcher/parse-launcher-args ["--launcher-debug"
                                          "--tui"
                                          "--cwd" "/tmp/work"
                                          "--model" "gpt-5"]))))
  (testing "unknown flags remain psi runtime args"
    (is (= {:cwd nil
            :launcher-debug? false
            :psi-args ["--rpc-edn" "--nrepl" "7777"]}
           (launcher/parse-launcher-args ["--rpc-edn" "--nrepl" "7777"]))))
  (testing "missing cwd value fails clearly"
    (let [ex (try
               (launcher/parse-launcher-args ["--cwd"])
               nil
               (catch clojure.lang.ExceptionInfo e
                 e))]
      (is (= "Missing value for --cwd" (ex-message ex)))
      (is (= "--cwd" (-> ex ex-data :arg))))))

(deftest resolve-effective-cwd-test
  (testing "defaults to process cwd"
    (is (= "/repo/project"
           (launcher/resolve-effective-cwd {:cwd nil} "/repo/project"))))
  (testing "uses absolute cwd override as-is"
    (is (= "/tmp/other"
           (launcher/resolve-effective-cwd {:cwd "/tmp/other"} "/repo/project"))))
  (testing "resolves relative cwd override against process cwd"
    (is (= "/repo/project/subdir"
           (launcher/resolve-effective-cwd {:cwd "subdir"} "/repo/project")))))

(deftest manifest-path-test
  (is (= "/home/alice/.psi/agent/extensions.edn"
         (launcher/user-manifest-path "/home/alice")))
  (is (= "/repo/project/.psi/extensions.edn"
         (launcher/project-manifest-path "/repo/project"))))

(deftest psi-self-basis-test
  (let [repo-config {:deps {'psi/main {:local/root "bases/main"}
                            'psi/app-runtime {:local/root "components/app-runtime"}
                            'org.clojure/clojure {:mvn/version "1.12.4"}}}]
    (testing "development policy absolutizes local roots"
      (is (= '{psi/main {:local/root "/repo/psi/bases/main"}
               psi/app-runtime {:local/root "/repo/psi/components/app-runtime"}
               org.clojure/clojure {:mvn/version "1.12.4"}
               nrepl/nrepl {:mvn/version "1.5.1"}}
             (:deps (with-redefs [launcher/repo-basis-config (constantly repo-config)]
                      (launcher/psi-self-basis "/repo/psi" :development))))))
    (testing "installed policy rewrites local roots to git deps/root entries"
      (is (= '{psi/main {:git/url "https://github.com/hugoduncan/psi.git"
                         :git/tag "main"
                         :deps/root "bases/main"}
               psi/app-runtime {:git/url "https://github.com/hugoduncan/psi.git"
                                :git/tag "main"
                                :deps/root "components/app-runtime"}
               org.clojure/clojure {:mvn/version "1.12.4"}
               nrepl/nrepl {:mvn/version "1.5.1"}}
             (:deps (with-redefs [launcher/repo-basis-config (constantly repo-config)]
                      (launcher/psi-self-basis "/repo/psi" :installed))))))))

(deftest build-clojure-command-test
  (is (= ["clojure" "-Sdeps" "{:deps {foo/bar {:mvn/version \"1.0.0\"}}}" "-M" "-m" "psi.main" "--tui" "--model" "gpt-5"]
         (launcher/build-clojure-command {:basis {:deps {'foo/bar {:mvn/version "1.0.0"}}}
                                          :psi-args ["--tui" "--model" "gpt-5"]}))))

(deftest launch-plan-test
  (let [basis-state {:basis {:deps {'foo/bar {:mvn/version "1.0.0"}}}
                     :manifest-info {:user-present? false
                                     :project-present? true
                                     :merged-manifest {:deps {'foo/bar {:mvn/version "1.0.0"}}}
                                     :defaulted-libs []
                                     :inferred-init-libs []}}]
    (is (= {:cwd "/repo/project"
            :launcher-root "/repo/psi"
            :launcher-debug? true
            :psi-args ["--rpc-edn"]
            :policy :development
            :basis {:deps {'foo/bar {:mvn/version "1.0.0"}}}
            :manifest-info {:user-present? false
                            :project-present? true
                            :merged-manifest {:deps {'foo/bar {:mvn/version "1.0.0"}}}
                            :defaulted-libs []
                            :inferred-init-libs []}
            :command ["clojure" "-Sdeps" "{:deps {foo/bar {:mvn/version \"1.0.0\"}}}" "-M" "-m" "psi.main" "--rpc-edn"]}
           (with-redefs [launcher/startup-basis (fn [_ _ _] basis-state)]
             (launcher/launch-plan ["--launcher-debug" "--rpc-edn"]
                                   "/repo/project"
                                   "/repo/psi"
                                   :development))))))
