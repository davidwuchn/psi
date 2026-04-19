(ns psi.agent-session.project-nrepl-commands-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.project-nrepl-runtime :as project-nrepl-runtime]
   [psi.agent-session.test-support :as test-support]))

(deftest project-nrepl-command-dispatch-test
  (testing "/project-repl returns formatted status"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          result (commands/dispatch-in ctx session-id "/project-repl" {})]
      (is (= :text (:type result)))
      (is (re-find #"Project nREPL" (:message result)))))

  (testing "/project-repl eval routes through project nREPL eval helper"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :session-defaults {:worktree-path (System/getProperty "user.dir")}})]
      (with-redefs [psi.agent-session.project-nrepl-eval/eval-instance-in! (fn [_ctx worktree-path code]
                                                                             {:status :success
                                                                              :worktree-path worktree-path
                                                                              :input code
                                                                              :value "3"})]
        (let [result (commands/dispatch-in ctx session-id "/project-repl eval (+ 1 2)" {})]
          (is (= :text (:type result)))
          (is (re-find #"Project nREPL eval success" (:message result)))
          (is (re-find #"3" (:message result)))))))

  (testing "/project-repl interrupt reports unavailable clearly"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :session-defaults {:worktree-path (System/getProperty "user.dir")}})]
      (with-redefs [psi.agent-session.project-nrepl-eval/interrupt-instance-in! (fn [_ctx worktree-path]
                                                                                   {:status :unavailable
                                                                                    :reason :no-active-eval
                                                                                    :worktree-path worktree-path})]
        (let [result (commands/dispatch-in ctx session-id "/project-repl interrupt" {})]
          (is (= :text (:type result)))
          (is (re-find #"unavailable" (:message result)))
          (is (re-find #"no-active-eval" (:message result))))))))
