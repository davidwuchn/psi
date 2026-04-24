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

(deftest build-clojure-command-test
  (is (= ["clojure" "-M" "-m" "psi.main" "--tui" "--model" "gpt-5"]
         (launcher/build-clojure-command {:psi-args ["--tui" "--model" "gpt-5"]}))))

(deftest launch-plan-test
  (is (= {:cwd "/repo/project"
          :launcher-debug? true
          :psi-args ["--rpc-edn"]
          :command ["clojure" "-M" "-m" "psi.main" "--rpc-edn"]}
         (launcher/launch-plan ["--launcher-debug" "--rpc-edn"] "/repo/project"))))
