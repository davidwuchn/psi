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

(deftest psl-job-emits-delivery-specific-status-message-test
  (testing "psl job emits explicit deferred status guidance"
    (let [sent (atom [])
          mutate-fn (fn [op params]
                      (case op
                        psi.extension/send-prompt
                        {:psi.extension/prompt-accepted? true
                         :psi.extension/prompt-delivery :deferred}
                        psi.extension/send-message
                        (do (swap! sent conj (:content params))
                            {:psi.extension/message-sent? true})
                        {}))]
      (set-psl-state! {:mutate-fn mutate-fn :query-fn (fn [_] {})})
      (let [result (invoke-private 'psl-job {:source-sha "feedbeef" :subject "⚒ Add feature"})]
        (is (= :done (:status result)))
        (is (true? (:accepted? result)))
        (is (= :deferred (:delivery result)))
        (is (some #(re-find #"queued via deferred; will auto-run when idle" (str %)) @sent)))))

  (testing "psl job emits prompt-path status when immediate"
    (let [sent (atom [])
          mutate-fn (fn [op params]
                      (case op
                        psi.extension/send-prompt
                        {:psi.extension/prompt-accepted? true
                         :psi.extension/prompt-delivery :prompt}
                        psi.extension/send-message
                        (do (swap! sent conj (:content params))
                            {:psi.extension/message-sent? true})
                        {}))]
      (set-psl-state! {:mutate-fn mutate-fn :query-fn (fn [_] {})})
      (let [result (invoke-private 'psl-job {:source-sha "feedbeef" :subject "⚒ Add feature"})]
        (is (= :done (:status result)))
        (is (= :prompt (:delivery result)))
        (is (some #(re-find #"PSL prompt queued via prompt" (str %)) @sent)))))

  (testing "psl job emits follow-up status when queued without run-fn"
    (let [sent (atom [])
          mutate-fn (fn [op params]
                      (case op
                        psi.extension/send-prompt
                        {:psi.extension/prompt-accepted? true
                         :psi.extension/prompt-delivery :follow-up}
                        psi.extension/send-message
                        (do (swap! sent conj (:content params))
                            {:psi.extension/message-sent? true})
                        {}))]
      (set-psl-state! {:mutate-fn mutate-fn :query-fn (fn [_] {})})
      (let [result (invoke-private 'psl-job {:source-sha "feedbeef" :subject "⚒ Add feature"})]
        (is (= :done (:status result)))
        (is (= :follow-up (:delivery result)))
        (is (some #(re-find #"PSL prompt queued via follow-up" (str %)) @sent))))))
