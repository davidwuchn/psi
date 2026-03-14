(ns extensions.work-on-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.work-on :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest mechanical-slug-test
  (testing "slug is mechanical and limited to four significant words"
    (is (= {:raw-description "Fix the footer not displayed after tree session switch"
            :terms ["fix" "footer" "not" "displayed"]
            :slug "fix-footer-not-displayed"
            :branch-name "fix-footer-not-displayed"}
           (sut/mechanical-slug "Fix the footer not displayed after tree session switch"))))

  (testing "all-stopword input falls back to work"
    (is (= "work"
           (:slug (sut/mechanical-slug "the and of to"))))))

(deftest init-registers-work-commands-test
  (testing "extension registers /work-* commands"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"})]
      (sut/init api)
      (is (= #{"work-on" "work-merge" "work-rebase" "work-status"}
             (set (keys (:commands @state))))))))

(deftest work-on-command-happy-path-test
  (testing "/work-on creates worktree and session and prints deterministic summary"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (fn [q]
                                            (case q
                                              [:psi.agent-session/session-id
                                               :psi.agent-session/session-name
                                               :psi.agent-session/session-file
                                               :psi.agent-session/cwd
                                               :psi.agent-session/system-prompt
                                               :psi.agent-session/host-sessions
                                               :psi.agent-session/git-worktree-current
                                               :psi.agent-session/git-worktrees]
                                              {:psi.agent-session/session-id "s-main"
                                               :psi.agent-session/cwd "/repo/main"
                                               :psi.agent-session/system-prompt "prompt"
                                               :psi.agent-session/host-sessions []
                                               :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/main"
                                                                                        :git.worktree/branch-name "main"}
                                               :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                 :git.worktree/branch-name "main"
                                                                                 :git.worktree/current? true}]}
                                              {}))})
          global-ctx (atom {:session-id "s-created"
                            :session-name nil
                            :worktree-path "/repo/fix-footer-not-displayed"
                            :parent-session-id nil})
          mutate-calls (atom [])
          printed (atom nil)]
      (with-redefs [sut/mutate! (fn [op params]
                                  (swap! mutate-calls conj [op params])
                                  (case op
                                    git.worktree/add! {:success true
                                                       :path "/repo/fix-footer-not-displayed"
                                                       :branch "fix-footer-not-displayed"
                                                       :head "abc123"}
                                    nil))
                    sut/create-worktree-session! (fn [description worktree-path parent-session-id]
                                                   (swap! global-ctx assoc
                                                          :session-name description
                                                          :worktree-path worktree-path
                                                          :parent-session-id parent-session-id)
                                                   @global-ctx)
                    println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        (let [handler (get-in @state [:commands "work-on" :handler])]
          (handler "Fix footer not displayed")
          (is (= 'git.worktree/add! (ffirst @mutate-calls)))
          (is (= "/repo/fix-footer-not-displayed"
                 (get-in (second (first @mutate-calls)) [:input :path])))
          (is (= "fix-footer-not-displayed"
                 (get-in (second (first @mutate-calls)) [:input :branch])))
          (is (= "/repo/fix-footer-not-displayed" (:worktree-path @global-ctx)))
          (is (= "Fix footer not displayed" (:session-name @global-ctx)))
          (is (re-find #"Working in `/repo/fix-footer-not-displayed` on branch `fix-footer-not-displayed`"
                       @printed)))))))

(deftest work-on-command-usage-error-test
  (testing "/work-on without description prints usage"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"})
          printed (atom nil)]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        ((get-in @state [:commands "work-on" :handler]) "   ")
        (is (= "usage: /work-on <description>" @printed))))))

(deftest work-merge-and-rebase-commands-test
  (testing "/work-merge and /work-rebase use git mutations and print deterministic text"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"})
          printed (atom [])]
      (with-redefs [sut/query! (fn [q]
                                 (case q
                                   [:psi.agent-session/session-id
                                    :psi.agent-session/session-name
                                    :psi.agent-session/session-file
                                    :psi.agent-session/cwd
                                    :psi.agent-session/system-prompt
                                    :psi.agent-session/host-sessions
                                    :psi.agent-session/git-worktree-current
                                    :psi.agent-session/git-worktrees]
                                   {:psi.agent-session/session-id "s2"
                                    :psi.agent-session/host-sessions [{:psi.session-info/id "main-s"
                                                                      :psi.session-info/cwd "/repo/main"}]
                                    :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/feature-x"
                                                                             :git.worktree/branch-name "feature-x"}
                                    :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                      :git.worktree/branch-name "main"}
                                                                     {:git.worktree/path "/repo/feature-x"
                                                                      :git.worktree/branch-name "feature-x"
                                                                      :git.worktree/current? true}]}
                                   {}))
                    sut/mutate! (fn [op params]
                                  (case op
                                    git.branch/default {:branch "main"}
                                    git.branch/merge! {:merged true}
                                    git.worktree/remove! {:success true}
                                    git.branch/delete! {:deleted true}
                                    git.branch/rebase! {:success true}
                                    nil))
                    println (fn [& xs] (swap! printed conj (apply str xs)))]
        (sut/init api)
        ((get-in @state [:commands "work-merge" :handler]) "")
        ((get-in @state [:commands "work-rebase" :handler]) "")
        (is (re-find #"Merged `feature-x` into `main`" (first @printed)))
        (is (re-find #"Rebased `feature-x` onto `main`" (second @printed)))))))
