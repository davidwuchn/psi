(ns extensions.agent-chain-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.agent-chain :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable :refer [with-user-dir]]))

(defn- temp-dir
  []
  (.toFile (java.nio.file.Files/createTempDirectory
            "psi-agent-chain-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest init-registers-surface-test
  (testing "agent chain registers workflow type, tool, commands, and session handler"
    (let [tmp (temp-dir)]
      (try
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent_chain.clj"})]
            (sut/init api)
            (is (contains? (:workflow-types @state) :agent-chain-run))
            (is (contains? (:tools @state) "run_chain"))
            (is (= #{"chain" "chain-list" "chain-reload"}
                   (set (keys (:commands @state)))))
            (is (= 1 (count (get-in @state [:handlers "session_switch"]))))
            (is (contains? (:widgets @state) "agent-chain"))))
        (finally
          (.delete tmp))))))

(deftest run-chain-requires-active-chain-test
  (testing "run_chain returns a helpful error when no chain is active"
    (let [tmp (temp-dir)]
      (try
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent_chain.clj"})]
            (sut/init api)
            (let [execute (get-in @state [:tools "run_chain" :execute])
                  updates (atom [])]
              (is (= {:content "No chain active. Use /chain to select one."
                      :is-error true}
                     (execute {"task" "summarize this"})))
              (is (= {:content "No chain active. Use /chain to select one."
                      :is-error true}
                     (execute {"task" "summarize this"}
                              {:on-update #(swap! updates conj %)})))
              (is (empty? @updates)))))
        (finally
          (.delete tmp))))))
