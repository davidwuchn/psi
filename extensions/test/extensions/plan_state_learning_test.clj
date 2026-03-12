(ns extensions.plan-state-learning-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.plan-state-learning :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(defn- git-log-response
  [sha subject]
  {:psi.extension.tool/content (str sha "\n" subject)
   :psi.extension.tool/is-error false})

(defn- invoke-private
  [sym & args]
  (apply (var-get (ns-resolve 'extensions.plan-state-learning sym)) args))

(defn- set-psl-state!
  [m]
  (reset! (var-get (ns-resolve 'extensions.plan-state-learning 'state)) m))

(deftest init-registers-git-head-changed-handler-test
  (testing "extension registers git_head_changed handler"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/plan_state_learning.clj"})]
      (sut/init api)
      (is (= 1 (count (get-in @state [:handlers "git_head_changed"]))))
      (is (contains? (:workflow-types @state) :psl)))))

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

(deftest psl-job-runs-subagent-in-forked-sync-mode-test
  (testing "psl job uses subagent tool-plan chain with fork_session=true"
    (let [sent     (atom [])
          captured (atom nil)
          mutate-fn (fn [op params]
                      (case op
                        psi.extension.tool/chain
                        (do
                          (reset! captured params)
                          {:psi.extension.tool-plan/succeeded? true
                           :psi.extension.tool-plan/results [{:id "psl-subagent"
                                                              :result {:content "ok" :is-error false}}]})
                        psi.extension/send-message
                        (do (swap! sent conj (:content params))
                            {:psi.extension/message-sent? true})
                        {}))]
      (set-psl-state! {:mutate-fn mutate-fn :query-fn (fn [_] {})})
      (let [result (invoke-private 'psl-job {:source-sha "feedbeef" :subject "⚒ Add feature"})
            step   (first (:steps @captured))
            args   (:args step)]
        (is (= :done (:status result)))
        (is (true? (:accepted? result)))
        (is (= :subagent (:delivery result)))
        (is (= "subagent" (:tool step)))
        (is (= "create" (get args "action")))
        (is (= "sync" (get args "mode")))
        (is (= true (get args "fork_session")))
        (is (some #(re-find #"PSL subagent run completed" (str %)) @sent)))))

  (testing "psl job emits failure message when subagent plan fails"
    (let [sent      (atom [])
          mutate-fn (fn [op params]
                      (case op
                        psi.extension.tool/chain
                        {:psi.extension.tool-plan/succeeded? false
                         :psi.extension.tool-plan/results [{:id "psl-subagent"
                                                            :result {:content "boom" :is-error true}}]}
                        psi.extension/send-message
                        (do (swap! sent conj (:content params))
                            {:psi.extension/message-sent? true})
                        {}))]
      (set-psl-state! {:mutate-fn mutate-fn :query-fn (fn [_] {})})
      (let [result (invoke-private 'psl-job {:source-sha "feedbeef" :subject "⚒ Add feature"})]
        (is (= :done (:status result)))
        (is (false? (:accepted? result)))
        (is (= :subagent (:delivery result)))
        (is (some #(re-find #"PSL subagent run failed" (str %)) @sent))))))
