(ns extensions.auto-session-name-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.auto-session-name :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(defn- reset-state! []
  (reset! @#'sut/state
          {:turn-counts {}
           :turn-interval 2
           :delay-ms 250
           :log-fn nil
           :ui nil}))

(deftest init-registers-handlers-and-load-notification-test
  (testing "init registers handlers and emits load notification"
    (reset-state!)
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"})]
      (sut/init api)
      (is (= 1 (count (get-in @state [:handlers "session_turn_finished"]))))
      (is (= 1 (count (get-in @state [:handlers "auto_session_name/rename_checkpoint"]))))
      (is (= [{:text "auto-session-name loaded" :level :info}]
             (:notifications @state))))))

(deftest turn-finished-schedules-every-second-turn-test
  (testing "every second completed turn schedules a delayed checkpoint event"
    (reset-state!)
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "s1" :turn-id "t1"})))
        (is (= [] (:scheduled-events @state)))
        (is (nil? (handler {:session-id "s1" :turn-id "t2"})))
        (is (= [{:ext-path "/test/auto_session_name.clj"
                 :delay-ms 250
                 :event-name "auto_session_name/rename_checkpoint"
                 :payload {:session-id "s1"
                           :turn-count 2}}]
               (:scheduled-events @state)))))))

(deftest checkpoint-handler-renames-session-on-valid-helper-result-test
  (testing "checkpoint handler queries source session, runs helper child session, and renames original session"
    (reset-state!)
    (let [calls          (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"
                                :query-fn (fn [req]
                                            (swap! calls conj [:query req])
                                            (if (= {:session-id "s1"
                                                    :query [:psi.agent-session/message-history]}
                                                   req)
                                              {:psi.agent-session/message-history
                                               [{:role "user" :content [{:type :text :text "Fix footer rendering"}]}
                                                {:role "assistant" :content [{:type :text :text "I will inspect the selector path."}]}]}
                                              {}))
                                :mutate-fn (fn [op params]
                                             (swap! calls conj [:mutate op params])
                                             (case op
                                               psi.extension/create-child-session {:psi.agent-session/session-id "child-1"}
                                               psi.extension/run-agent-loop-in-session {:psi.agent-session/agent-run-ok? true
                                                                                        :psi.agent-session/agent-run-text "Fix footer rendering"}
                                               psi.extension/set-session-name {:psi.agent-session/session-name (:name params)}
                                               psi.extension/schedule-event {:psi.extension/scheduled? true}
                                               {}))})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "auto_session_name/rename_checkpoint"]))]
        (is (nil? (handler {:session-id "s1" :turn-count 2})))
        (is (= ['psi.extension/create-child-session
                'psi.extension/run-agent-loop-in-session
                'psi.extension/set-session-name]
               (->> @calls
                    (keep (fn [[kind op _]] (when (= kind :mutate) op)))
                    (remove #{'psi.extension/schedule-event})
                    vec)))
        (is (true? (contains? (:helper-session-ids @@#'sut/state) "child-1")))))))

(deftest checkpoint-handler-invalid-helper-result-does-not-rename-test
  (testing "invalid helper result does not rename and falls back to notification"
    (reset-state!)
    (let [calls          (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"
                                :query-fn (fn [req]
                                            (if (= {:session-id "s1"
                                                    :query [:psi.agent-session/message-history]}
                                                   req)
                                              {:psi.agent-session/message-history
                                               [{:role "user" :content [{:type :text :text "Fix footer rendering"}]}]}
                                              {}))
                                :mutate-fn (fn [op params]
                                             (swap! calls conj [op params])
                                             (case op
                                               psi.extension/create-child-session {:psi.agent-session/session-id "child-1"}
                                               psi.extension/run-agent-loop-in-session {:psi.agent-session/agent-run-ok? true
                                                                                        :psi.agent-session/agent-run-text ""}
                                               psi.extension/schedule-event {:psi.extension/scheduled? true}
                                               {}))})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "auto_session_name/rename_checkpoint"]))]
        (is (nil? (handler {:session-id "s1" :turn-count 2})))
        (is (not-any? #(= 'psi.extension/set-session-name (first %)) @calls))
        (is (= {:text "auto-session-name: rename checkpoint for session s1 at turn 2"
                :level :info}
               (last (:notifications @state))))))))

(deftest helper-session-turn-finished-is-ignored-test
  (testing "turn-finished events for helper child sessions do not schedule nested checkpoints"
    (reset-state!)
    (swap! @#'sut/state assoc :helper-session-ids #{"child-1"})
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"})]
      (sut/init api)
      (swap! @#'sut/state assoc :helper-session-ids #{"child-1"})
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "child-1" :turn-id "t1"})))
        (is (= [] (:scheduled-events @state)))))))
