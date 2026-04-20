(ns extensions.auto-session-name-guards-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.auto-session-name :as sut]))

(defn- reset-state! []
  (reset! @#'sut/state
          {:turn-counts {}
           :helper-session-ids #{}
           :last-auto-name-by-session {}
           :turn-interval 2
           :delay-ms 250
           :log-fn nil
           :ui nil}))

(deftest stale-checkpoint-test
  (testing "checkpoint is stale when session turn count has advanced beyond it"
    (reset-state!)
    (swap! @#'sut/state assoc :turn-counts {"s1" 4})
    (is (true? (#'sut/stale-checkpoint? "s1" 2))))

  (testing "checkpoint is not stale when turn count matches"
    (reset-state!)
    (swap! @#'sut/state assoc :turn-counts {"s1" 2})
    (is (false? (#'sut/stale-checkpoint? "s1" 2)))))

(deftest manual-override-detection-test
  (testing "manual override when current name differs from last auto name"
    (reset-state!)
    (swap! @#'sut/state assoc :last-auto-name-by-session {"s1" "Fix footer rendering"})
    (is (true? (#'sut/manual-override? "s1" "Manual name"))))

  (testing "no manual override when there is no last auto name"
    (reset-state!)
    (is (false? (#'sut/manual-override? "s1" "Anything"))))

  (testing "no manual override when current name still matches last auto name"
    (reset-state!)
    (swap! @#'sut/state assoc :last-auto-name-by-session {"s1" "Fix footer rendering"})
    (is (false? (#'sut/manual-override? "s1" "Fix footer rendering")))))

(deftest remember-auto-name-test
  (testing "remember-auto-name stores last auto name by session"
    (reset-state!)
    (is (= "Fix footer rendering"
           (#'sut/remember-auto-name! "s1" "Fix footer rendering")))
    (is (= "Fix footer rendering"
           (get-in @@#'sut/state [:last-auto-name-by-session "s1"])))))
