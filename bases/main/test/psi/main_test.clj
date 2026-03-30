(ns psi.main-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.app-runtime :as app-runtime]
   [psi.main :as main]
   [psi.rpc :as rpc]
   [psi.tui.app :as tui-app]))

(deftest main-routes-console-mode-test
  (let [called (atom nil)
        exited (atom nil)]
    (with-redefs [main/run-console-session! (fn [args] (reset! called [:console args]))
                  main/run-rpc-session! (fn [args] (reset! called [:rpc args]))
                  main/run-tui-session! (fn [args] (reset! called [:tui args]))
                  main/exit! (fn [code] (reset! exited code))]
      (main/-main "--model" "sonnet-4.6")
      (is (= [:console ["--model" "sonnet-4.6"]] @called))
      (is (= 0 @exited)))))

(deftest main-routes-rpc-mode-test
  (let [called (atom nil)
        exited (atom nil)]
    (with-redefs [main/run-console-session! (fn [args] (reset! called [:console args]))
                  main/run-rpc-session! (fn [args] (reset! called [:rpc args]))
                  main/run-tui-session! (fn [args] (reset! called [:tui args]))
                  main/exit! (fn [code] (reset! exited code))]
      (main/-main "--rpc-edn")
      (is (= [:rpc ["--rpc-edn"]] @called))
      (is (= 0 @exited)))))

(deftest main-routes-tui-mode-test
  (let [called (atom nil)
        exited (atom nil)]
    (with-redefs [main/run-console-session! (fn [args] (reset! called [:console args]))
                  main/run-rpc-session! (fn [args] (reset! called [:rpc args]))
                  main/run-tui-session! (fn [args] (reset! called [:tui args]))
                  main/exit! (fn [code] (reset! exited code))]
      (main/-main "--tui")
      (is (= [:tui ["--tui"]] @called))
      (is (= 0 @exited)))))

(deftest run-rpc-session-delegates-to-rpc-runtime-test
  (let [captured (atom nil)]
    (with-redefs [app-runtime/start-nrepl! (fn [port] {:server port})
                  app-runtime/stop-nrepl! (fn [server] (swap! captured assoc :stopped server))
                  rpc/start-runtime! (fn [{:keys [model-key memory-runtime-opts session-config rpc-trace-file]}]
                                      (reset! captured {:model-key model-key
                                                        :memory-runtime-opts memory-runtime-opts
                                                        :session-config session-config
                                                        :rpc-trace-file rpc-trace-file}))]
      (main/run-rpc-session! ["--rpc-edn"
                              "--model" "sonnet-4.6"
                              "--memory-store" "in-memory"
                              "--llm-idle-timeout-ms" "90000"
                              "--rpc-trace-file" "/tmp/rpc.ndedn"
                              "--nrepl" "7888"])
      (is (= :sonnet-4.6 (:model-key @captured)))
      (is (= {:store-provider "in-memory"} (:memory-runtime-opts @captured)))
      (is (= {:llm-stream-idle-timeout-ms 90000} (:session-config @captured)))
      (is (= "/tmp/rpc.ndedn" (:rpc-trace-file @captured)))
      (is (= {:server 7888} (:stopped @captured))))))

(deftest run-tui-session-delegates-to-app-runtime-component-test
  (let [captured (atom nil)]
    (with-redefs [app-runtime/start-nrepl! (fn [port] {:server port})
                  app-runtime/stop-nrepl! (fn [server] (swap! captured assoc :stopped server))
                  app-runtime/start-tui-runtime! (fn [start-fn model-key memory-runtime-opts session-config]
                                                                (reset! captured {:start-fn start-fn
                                                                                  :model-key model-key
                                                                                  :memory-runtime-opts memory-runtime-opts
                                                                                  :session-config session-config}))]
      (main/run-tui-session! ["--tui"
                              "--model" "sonnet-4.6"
                              "--memory-store-fallback" "off"
                              "--llm-idle-timeout-ms" "42000"
                              "--nrepl" "7777"])
      (is (= tui-app/start! (:start-fn @captured)))
      (is (= :sonnet-4.6 (:model-key @captured)))
      (is (= {:auto-store-fallback? false} (:memory-runtime-opts @captured)))
      (is (= {:llm-stream-idle-timeout-ms 42000} (:session-config @captured)))
      (is (= {:server 7777} (:stopped @captured))))))

(deftest run-console-session-delegates-to-app-runtime-component-test
  (let [captured (atom nil)]
    (with-redefs [app-runtime/start-nrepl! (fn [port] {:server port})
                  app-runtime/stop-nrepl! (fn [server] (swap! captured assoc :stopped server))
                  app-runtime/run-session (fn [model-key memory-runtime-opts session-config]
                                            (reset! captured {:model-key model-key
                                                              :memory-runtime-opts memory-runtime-opts
                                                              :session-config session-config}))]
      (main/run-console-session! ["--model" "sonnet-4.6"
                                  "--memory-retention-snapshots" "12"
                                  "--llm-idle-timeout-ms" "30000"
                                  "--nrepl" "7001"])
      (is (= :sonnet-4.6 (:model-key @captured)))
      (is (= {:retention-snapshots 12} (:memory-runtime-opts @captured)))
      (is (= {:llm-stream-idle-timeout-ms 30000} (:session-config @captured)))
      (is (= {:server 7001} (:stopped @captured))))))

(deftest arg-parsing-helpers-test
  (testing "rpc trace file parsing"
    (is (= "/tmp/rpc.ndedn"
           (#'main/rpc-trace-file-from-args ["--rpc-trace-file" "/tmp/rpc.ndedn"])))
    (is (nil? (#'main/rpc-trace-file-from-args ["--rpc-trace-file" "   "]))))
  (testing "memory runtime opts parsing"
    (is (= {:store-provider "in-memory"
            :auto-store-fallback? false
            :history-commit-limit 99
            :retention-snapshots 12
            :retention-deltas 34}
           (#'main/memory-runtime-opts-from-args
            ["--memory-store" "in-memory"
             "--memory-store-fallback" "off"
             "--memory-history-limit" "99"
             "--memory-retention-snapshots" "12"
             "--memory-retention-deltas" "34"]))))
  (testing "session runtime config parsing"
    (is (= {:llm-stream-idle-timeout-ms 90000}
           (#'main/session-runtime-config-from-args ["--llm-idle-timeout-ms" "90000"]))))
  (testing "nrepl port parsing"
    (is (= 7888 (#'main/nrepl-port-from-args ["--nrepl" "7888"])))
    (is (= 0 (#'main/nrepl-port-from-args ["--nrepl"])))
    (is (nil? (#'main/nrepl-port-from-args [])))))
