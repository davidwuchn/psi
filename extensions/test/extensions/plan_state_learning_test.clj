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

(deftest handler-sends-agent-prompt-for-normal-commit-test
  (testing "normal commit sends a user-style prompt to the agent"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/plan_state_learning.clj"})
          calls (atom [])]
      (with-redefs [sut/bash! (fn [_ cmd]
                                (swap! calls conj cmd)
                                (if (= cmd "git log -1 --pretty=format:%H%n%s")
                                  (git-log-response "feedbeef" "⚒ Add feature")
                                  {:psi.extension.tool/content "" :psi.extension.tool/is-error false}))
                    sut/send-message! (fn [mutate-fn text]
                                        (mutate-fn 'psi.extension/send-message
                                                   {:role "assistant"
                                                    :content text
                                                    :custom-type "plan-state-learning"}))]
        (sut/init api)
        (let [handler (first (get-in @state [:handlers "git_head_changed"]))
              result  (handler {:head "feedbeef" :previous-head "aaa111" :cwd "/tmp/repo"})
              messages (:messages @state)
              texts   (map :content messages)
              prompt-msg (some #(when (= "extension-prompt" (:custom-type %)) %) messages)]
          (is (false? (:skip? result)))
          (is (true? (:prompt-accepted? result)))
          (is (= :prompt (:prompt-delivery result)))
          (is (some #(re-find #"PSL sync start" (str %)) texts))
          (is (some #(re-find #"PSL prompt queued via prompt" (str %)) texts))
          (is (some? prompt-msg))
          (is (re-find #"Update PLAN.md and STATE.md" (str (:content prompt-msg))))
          (is (= ["git log -1 --pretty=format:%H%n%s"] @calls)))))))
