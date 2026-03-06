(ns extensions.plan-state-learning-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.plan-state-learning :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(defn- git-log-response
  [sha subject]
  {:psi.extension.tool/content (str sha "\n" subject)
   :psi.extension.tool/is-error false})

(deftest init-registers-git-head-changed-handler-test
  (testing "extension registers git_head_changed handler"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/plan_state_learning.clj"})]
      (sut/init api)
      (is (= 1 (count (get-in @state [:handlers "git_head_changed"]))))
      (is (seq (:messages @state))))))

(deftest handler-skips-self-marker-commits-test
  (testing "handler emits skip message when marker is present"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/plan_state_learning.clj"})
          calls (atom [])]
      (with-redefs [sut/bash! (fn [_ cmd]
                                (swap! calls conj cmd)
                                (if (= cmd "git log -1 --pretty=format:%H%n%s")
                                  (git-log-response "abc1234" "◈ Δ Auto-update PLAN/STATE [psi:psl-auto]")
                                  {:psi.extension.tool/content "" :psi.extension.tool/is-error false}))
                    sut/send-message! (fn [mutate-fn text]
                                        (mutate-fn 'psi.extension/send-message
                                                   {:role "assistant"
                                                    :content text
                                                    :custom-type "plan-state-learning"}))]
        (sut/init api)
        (let [handler (first (get-in @state [:handlers "git_head_changed"]))
              result  (handler {:head "abc1234" :previous-head "aaa111" :cwd "/tmp/repo"})
              texts   (map :content (:messages @state))]
          (is (true? (:skip? result)))
          (is (some #(re-find #"PSL skipped" (str %)) texts))
          (is (= ["git log -1 --pretty=format:%H%n%s"] @calls)))))))

(deftest handler-runs-two-phase-flow-for-normal-commit-test
  (testing "normal commit triggers phase1 and phase2 commands"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/plan_state_learning.clj"})
          calls (atom [])]
      (with-redefs [sut/bash! (fn [_ cmd]
                                (swap! calls conj cmd)
                                (cond
                                  (= cmd "git log -1 --pretty=format:%H%n%s")
                                  (git-log-response "feedbeef" "⚒ Add feature")

                                  (re-find #"git commit -m '◈ Δ Auto-update PLAN/STATE" cmd)
                                  {:psi.extension.tool/content "__PSL_COMMIT__ 1111111"
                                   :psi.extension.tool/is-error false}

                                  (re-find #"git commit -m '◈ λ Auto-update LEARNING" cmd)
                                  {:psi.extension.tool/content "__PSL_COMMIT__ 2222222"
                                   :psi.extension.tool/is-error false}

                                  :else
                                  {:psi.extension.tool/content "__PSL_CHANGED__"
                                   :psi.extension.tool/is-error false}))
                    sut/send-message! (fn [mutate-fn text]
                                        (mutate-fn 'psi.extension/send-message
                                                   {:role "assistant"
                                                    :content text
                                                    :custom-type "plan-state-learning"}))]
        (sut/init api)
        (let [handler (first (get-in @state [:handlers "git_head_changed"]))
              result  (handler {:head "feedbeef" :previous-head "aaa111" :cwd "/tmp/repo"})
              texts   (map :content (:messages @state))]
          (is (false? (:skip? result)))
          (is (= "1111111" (:phase1-sha result)))
          (is (= "2222222" (:phase2-sha result)))
          (is (some #(re-find #"PSL sync start" (str %)) texts))
          (is (some #(re-find #"phase1 committed" (str %)) texts))
          (is (some #(re-find #"phase2 committed" (str %)) texts))
          (is (= 6 (count @calls)))
          (is (= "git log -1 --pretty=format:%H%n%s" (first @calls))))))))
