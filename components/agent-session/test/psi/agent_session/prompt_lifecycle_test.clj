(ns psi.agent-session.prompt-lifecycle-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- journal-messages
  [ctx session-id]
  (let [journal (ss/get-state-value-in ctx (ss/state-path :journal session-id))]
    (->> journal
         (filter #(= :message (:kind %)))
         (mapv #(get-in % [:data :message])))))

(deftest prompt-record-response-appends-assistant-once-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        assistant-msg    {:role "assistant"
                          :content [{:type :text :text "done"}]
                          :stop-reason :stop
                          :timestamp (java.time.Instant/now)}
        execution-result {:execution-result/turn-id "turn-1"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message assistant-msg
                          :execution-result/turn-outcome :turn.outcome/stop
                          :execution-result/tool-calls []
                          :execution-result/stop-reason :stop}]
    (dispatch/clear-event-log!)
    (dispatch/dispatch! ctx :session/prompt-record-response
                        {:session-id session-id
                         :execution-result execution-result}
                        {:origin :core})
    (let [msgs (journal-messages ctx session-id)]
      (is (= 1 (count msgs)))
      (is (= "assistant" (:role (first msgs))))
      (is (= "done" (get-in (first msgs) [:content 0 :text]))))))

(deftest prompt-record-response-routes-tool-use-to-continue-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        assistant-msg    {:role "assistant"
                          :content [{:type :tool-call :id "tc-1" :name "read" :arguments "{}"}]
                          :stop-reason :stop
                          :timestamp (java.time.Instant/now)}
        execution-result {:execution-result/turn-id "turn-tool"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message assistant-msg
                          :execution-result/turn-outcome :turn.outcome/tool-use
                          :execution-result/tool-calls [{:id "tc-1" :name "read" :arguments "{}"}]
                          :execution-result/stop-reason :stop}]
    (dispatch/clear-event-log!)
    (with-redefs [psi.agent-session.prompt-chain/run-prompt-tools! (fn [_ctx _sid _res _pq]
                                                                     {:continued? true :tool-call-count 1})
                  psi.agent-session.prompt-request/build-prepared-request (fn [_ctx sid {:keys [turn-id]}]
                                                                           {:prepared-request/id turn-id
                                                                            :prepared-request/session-id sid
                                                                            :prepared-request/system-prompt "sys"
                                                                            :prepared-request/messages []
                                                                            :prepared-request/tools []
                                                                            :prepared-request/session-snapshot {:cache-breakpoints #{}}
                                                                            :prepared-request/model {:provider "stub" :id "stub"}
                                                                            :prepared-request/ai-options {}
                                                                            :prepared-request/provider-conversation {:system-prompt "sys"
                                                                                                                     :messages []
                                                                                                                     :tools []}})
                  psi.agent-session.prompt-runtime/execute-prepared-request! (fn [_ai-ctx _ctx sid _agent-ctx prepared _pq]
                                                                               {:execution-result/turn-id (:prepared-request/id prepared)
                                                                                :execution-result/session-id sid
                                                                                :execution-result/assistant-message {:role "assistant"
                                                                                                                    :content [{:type :text :text "after tool"}]
                                                                                                                    :stop-reason :stop
                                                                                                                    :timestamp (java.time.Instant/now)}
                                                                                :execution-result/turn-outcome :turn.outcome/stop
                                                                                :execution-result/tool-calls []
                                                                                :execution-result/stop-reason :stop})]
      (dispatch/dispatch! ctx :session/prompt-record-response
                          {:session-id session-id
                           :execution-result execution-result}
                          {:origin :core})
      (let [entries (dispatch/event-log-entries)]
        (is (some #(= :session/prompt-continue (:event-type %)) entries))
        (is (some #(= :session/prompt-prepare-request (:event-type %)) entries))
        (is (some #(= :session/prompt-record-response (:event-type %)) entries))
        (let [msgs (journal-messages ctx session-id)]
          (is (= 2 (count msgs)))
          (is (= "assistant" (:role (first msgs))))
          (is (= "assistant" (:role (second msgs)))))))))
