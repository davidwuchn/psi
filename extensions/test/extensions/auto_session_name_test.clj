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

(deftest checkpoint-handler-notifies-ui-test
  (testing "checkpoint handler emits transient UI notification"
    (reset-state!)
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "auto_session_name/rename_checkpoint"]))]
        (is (nil? (handler {:session-id "s1" :turn-count 2})))
        (is (= {:text "auto-session-name: rename checkpoint for session s1 at turn 2"
                :level :info}
               (last (:notifications @state))))))))
