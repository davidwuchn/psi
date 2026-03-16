(ns extensions.work-on-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.work-on :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]
   [psi.history.git :as git]))

(def ^:private session-query
  [:psi.agent-session/session-id
   :psi.agent-session/session-name
   :psi.agent-session/session-file
   :psi.agent-session/cwd
   :psi.agent-session/system-prompt
   :psi.agent-session/host-sessions
   :psi.agent-session/git-worktree-current
   :psi.agent-session/git-worktrees])

(defn- with-session-query
  [result-map]
  (fn [q]
    (cond
      (= session-query q) result-map
      (= [:psi.agent-session/git-default-branch] q)
      {:psi.agent-session/git-default-branch {:branch "main" :source :fallback}}
      :else {})))

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
      (is (= #{"work-on" "work-done" "work-rebase" "work-status"}
             (set (keys (:commands @state)))))
      (is (= 1 (count (get-in @state [:handlers "session_switch"])))))))

(deftest work-on-command-happy-path-test
  (testing "/work-on creates worktree and a distinct session and prints deterministic summary"
    (let [created-session (atom nil)
          mutate-calls    (atom [])
          printed         (atom nil)
          created-op      (atom nil)
          created-params  (atom nil)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :psi.agent-session/cwd "/repo/main"
                                             :psi.agent-session/system-prompt "prompt"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "s-main"
                                                                                :psi.session-info/cwd "/repo/main"
                                                                                :psi.session-info/name "main"}]
                                             :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/main"
                                                                                      :git.worktree/branch-name "main"}
                                             :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                :git.worktree/branch-name "main"
                                                                                :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.worktree/add! {:success true
                                                                  :path "/repo/fix-footer-not-displayed"
                                                                  :branch "fix-footer-not-displayed"
                                                                  :head "abc123"}
                                               psi.extension/create-session {:psi.agent-session/session-id "s-created"
                                                                             :psi.agent-session/session-name (:session-name params)
                                                                             :psi.agent-session/cwd (:worktree-path params)}
                                               nil))})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        (let [handler (get-in @state [:commands "work-on" :handler])]
          (handler "Fix footer not displayed")
          (let [[op params] (second @mutate-calls)]
            (reset! created-op op)
            (reset! created-params params)
            (reset! created-session {:session-id "s-created"
                                     :session-name (:session-name params)
                                     :worktree-path (:worktree-path params)
                                     :system-prompt (:system-prompt params)}))
          (is (= 'git.worktree/add! (ffirst @mutate-calls)))
          (is (= "/repo/fix-footer-not-displayed"
                 (get-in (second (first @mutate-calls)) [:input :path])))
          (is (= "fix-footer-not-displayed"
                 (get-in (second (first @mutate-calls)) [:input :branch])))
          (is (= 'psi.extension/create-session @created-op))
          (is (= "s-created" (:session-id @created-session)))
          (is (= "/repo/fix-footer-not-displayed" (:worktree-path @created-session)))
          (is (= "Fix footer not displayed" (:session-name @created-session)))
          (is (nil? (:system-prompt @created-session)))
          (is (re-find #"Working in `/repo/fix-footer-not-displayed` on branch `fix-footer-not-displayed`"
                       @printed)))))))

(deftest work-on-command-reuses-existing-worktree-test
  (testing "/work-on creates a worktree from an existing branch when the slug branch already exists"
    (let [printed      (atom nil)
          create-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :psi.agent-session/cwd "/repo/main"
                                             :psi.agent-session/system-prompt "prompt"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "s-main"
                                                                                :psi.session-info/cwd "/repo/main"
                                                                                :psi.session-info/name "main"}]
                                             :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/main"
                                                                                      :git.worktree/branch-name "main"}
                                             :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                :git.worktree/branch-name "main"
                                                                                :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.worktree/add! (let [input (:input params)]
                                                                   (swap! create-calls conj input)
                                                                   (if (:create-branch input)
                                                                     {:success false
                                                                      :error "branch already exists"}
                                                                     {:success true
                                                                      :path (:path input)
                                                                      :branch (:branch input)
                                                                      :head "abc123"}))
                                               psi.extension/create-session {:psi.agent-session/session-id "s-branch-existing"
                                                                             :psi.agent-session/session-name (:session-name params)
                                                                             :psi.agent-session/cwd (:worktree-path params)}
                                               nil))})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        ((get-in @state [:commands "work-on" :handler]) "fix repeated thinking output")
        (is (= [{:path "/repo/fix-repeated-thinking-output"
                 :branch "fix-repeated-thinking-output"
                 :base_ref nil
                 :create-branch true}
                {:path "/repo/fix-repeated-thinking-output"
                 :branch "fix-repeated-thinking-output"
                 :base_ref nil
                 :create-branch false}]
               @create-calls))
        (is (re-find #"Working in `/repo/fix-repeated-thinking-output` on branch `fix-repeated-thinking-output`"
                     @printed)))))

  (testing "/work-on reuses an existing worktree and switches to its existing session"
    (let [printed  (atom nil)
          switched (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :psi.agent-session/cwd "/repo/main"
                                             :psi.agent-session/system-prompt "prompt"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "s-main"
                                                                                :psi.session-info/cwd "/repo/main"
                                                                                :psi.session-info/name "main"}
                                                                               {:psi.session-info/id "s-existing"
                                                                                :psi.session-info/cwd "/repo/fix-repeated-thinking-output"
                                                                                :psi.session-info/name "Fix repeated thinking output in emacs"}]
                                             :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/main"
                                                                                      :git.worktree/branch-name "main"}
                                             :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                :git.worktree/branch-name "main"
                                                                                :git.worktree/current? true}
                                                                               {:git.worktree/path "/repo/fix-repeated-thinking-output"
                                                                                :git.worktree/branch-name "fix-repeated-thinking-output"}]})
                                :mutate-fn (fn [op _params]
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.worktree/add! {:success false
                                                                  :error "worktree path already exists"}
                                               psi.extension/switch-session (do (swap! switched conj "s-existing")
                                                                                {:psi.agent-session/session-id "s-existing"})
                                               nil))})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        ((get-in @state [:commands "work-on" :handler]) "fix repeated thinking output in emacs")
        (is (= ["s-existing"] @switched))
        (is (re-find #"Working in `/repo/fix-repeated-thinking-output` on branch `fix-repeated-thinking-output`"
                     @printed))))))

(deftest work-on-command-usage-error-test
  (testing "/work-on without description prints usage"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"})
          printed (atom nil)]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        ((get-in @state [:commands "work-on" :handler]) "   ")
        (is (= "usage: /work-on <description>" @printed))))))

(deftest work-done-and-rebase-commands-test
  (testing "/work-done fast-forwards onto the cached default branch, switches sessions, and /work-rebase uses git mutations"
    (let [printed      (atom [])
          switched     (atom [])
          merge-params (atom nil)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
                                            {:psi.agent-session/session-id "s2"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "main-s"
                                                                                :psi.session-info/cwd "/repo/main"
                                                                                :psi.session-info/name "main"}]
                                             :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/feature-x"
                                                                                      :git.worktree/branch-name "feature-x"}
                                             :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                :git.worktree/branch-name "main"}
                                                                               {:git.worktree/path "/repo/feature-x"
                                                                                :git.worktree/branch-name "feature-x"
                                                                                :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.branch/merge! (do (reset! merge-params params)
                                                                     {:merged true})
                                               git.worktree/remove! {:success true}
                                               git.branch/delete! {:deleted true}
                                               git.branch/rebase! {:success true}
                                               psi.extension/switch-session (do (swap! switched conj (:session-id params))
                                                                                {:psi.agent-session/session-id (:session-id params)})
                                               nil))})]
      (with-redefs [println (fn [& xs] (swap! printed conj (apply str xs)))
                    git/branch-tip-merged-into-current? (fn [ctx branch]
                                                          (cond
                                                            (= "/repo/feature-x" (:repo-dir ctx))
                                                            (= branch "main")

                                                            (= "/repo/main" (:repo-dir ctx))
                                                            (= branch "feature-x")

                                                            :else false))
                    git/current-branch (fn [_ctx] "main")
                    git/current-commit (fn [_ctx] "main-sha")]
        (sut/init api)
        ((get-in @state [:commands "work-done" :handler]) "")
        ((get-in @state [:commands "work-rebase" :handler]) "")
        (is (= ["main-s"] @switched))
        (is (= "/repo/main" (get-in @merge-params [:git/context :cwd]))
            "merge must execute in the main worktree context")
        (is (re-find #"Fast-forwarded `feature-x` into `main`" (first @printed)))
        (is (re-find #"Rebased `feature-x` onto `main`" (second @printed))))))

  (testing "/work-done creates a main-worktree session when none exists"
    (let [created  (atom [])
          switched (atom [])
          printed  (atom nil)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
                                            {:psi.agent-session/session-id "s-feature"
                                             :psi.agent-session/system-prompt "prompt"
                                             :psi.agent-session/host-sessions []
                                             :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/feature-x"
                                                                                      :git.worktree/branch-name "feature-x"}
                                             :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                :git.worktree/branch-name "main"}
                                                                               {:git.worktree/path "/repo/feature-x"
                                                                                :git.worktree/branch-name "feature-x"
                                                                                :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.branch/merge! {:merged true}
                                               git.worktree/remove! {:success true}
                                               git.branch/delete! {:deleted true}
                                               psi.extension/create-session (let [sd {:psi.agent-session/session-id "s-main-created"
                                                                                      :psi.agent-session/session-name (:session-name params)
                                                                                      :psi.agent-session/cwd (:worktree-path params)}]
                                                                              (swap! created conj sd)
                                                                              sd)
                                               psi.extension/switch-session (do (swap! switched conj (:session-id params))
                                                                                {:psi.agent-session/session-id (:session-id params)})
                                               nil))})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))
                    git/branch-tip-merged-into-current? (fn [ctx branch]
                                                          (cond
                                                            (= "/repo/feature-x" (:repo-dir ctx))
                                                            (= branch "main")

                                                            (= "/repo/main" (:repo-dir ctx))
                                                            (= branch "feature-x")

                                                            :else false))
                    git/current-branch (fn [_ctx] "main")
                    git/current-commit (fn [_ctx] "main-sha")]
        (sut/init api)
        ((get-in @state [:commands "work-done" :handler]) "")
        (is (= [{:psi.agent-session/session-id "s-main-created"
                 :psi.agent-session/session-name "main"
                 :psi.agent-session/cwd "/repo/main"}]
               @created))
        (is (= ["s-main-created"] @switched))
        (is (re-find #"Fast-forwarded `feature-x` into `main`" @printed))))))

(deftest work-done-auto-rebase-and-failure-messages-test
  (testing "/work-done auto-rebases with a forked sync subagent when ff is not yet possible"
    (let [printed      (atom nil)
          chain-calls  (atom [])
          remove-calls (atom 0)
          ff-state     (atom :before)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
                                            {:psi.agent-session/session-id "s-feature"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "main-s"
                                                                                :psi.session-info/cwd "/repo/main"
                                                                                :psi.session-info/name "main"}]
                                             :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/feature-x"
                                                                                      :git.worktree/branch-name "feature-x"}
                                             :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                :git.worktree/branch-name "main"}
                                                                               {:git.worktree/path "/repo/feature-x"
                                                                                :git.worktree/branch-name "feature-x"
                                                                                :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               psi.extension.tool/chain (do
                                                                          (swap! chain-calls conj params)
                                                                          (reset! ff-state :after)
                                                                          {:psi.extension.tool-plan/succeeded? true
                                                                           :psi.extension.tool-plan/results [{:id "work-done-rebase"
                                                                                                              :result {:content "rebase ok"
                                                                                                                       :is-error false}}]})
                                               git.branch/merge! {:merged true}
                                               git.worktree/remove! (do (swap! remove-calls inc)
                                                                        {:success true})
                                               git.branch/delete! {:deleted true}
                                               psi.extension/switch-session {:psi.agent-session/session-id "main-s"}
                                               nil))})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))
                    git/branch-tip-merged-into-current? (fn [ctx branch]
                                                          (cond
                                                            (= "/repo/feature-x" (:repo-dir ctx))
                                                            (and (= branch "main") (= :after @ff-state))

                                                            (= "/repo/main" (:repo-dir ctx))
                                                            (= branch "feature-x")

                                                            :else false))
                    git/current-branch (fn [_ctx] "main")
                    git/current-commit (fn [_ctx] "main-sha")]
        (sut/init api)
        ((get-in @state [:commands "work-done" :handler]) "")
        (is (= 1 (count @chain-calls)))
        (is (= "subagent" (get-in (first @chain-calls) [:steps 0 :tool])))
        (is (= "create" (get-in (first @chain-calls) [:steps 0 :args "action"])))
        (is (= "sync" (get-in (first @chain-calls) [:steps 0 :args "mode"])))
        (is (= true (get-in (first @chain-calls) [:steps 0 :args "fork_session"])))
        (is (= 1 @remove-calls))
        (is (re-find #"after automatic rebase" @printed)))))

  (testing "/work-done stops with an informative message when automatic rebase fails"
    (let [printed      (atom nil)
          remove-calls (atom 0)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
                                            {:psi.agent-session/session-id "s-feature"
                                             :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/feature-x"
                                                                                      :git.worktree/branch-name "feature-x"}
                                             :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                :git.worktree/branch-name "main"}
                                                                               {:git.worktree/path "/repo/feature-x"
                                                                                :git.worktree/branch-name "feature-x"
                                                                                :git.worktree/current? true}]})
                                :mutate-fn (fn [op _params]
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               psi.extension.tool/chain {:psi.extension.tool-plan/succeeded? false
                                                                         :psi.extension.tool-plan/error "subagent failed"
                                                                         :psi.extension.tool-plan/results [{:id "work-done-rebase"
                                                                                                            :result {:content "rebase conflict"
                                                                                                                     :is-error true}}]}
                                               git.worktree/remove! (do (swap! remove-calls inc)
                                                                        {:success true})
                                               nil))})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))
                    git/branch-tip-merged-into-current? (fn [_ctx _branch] false)]
        (sut/init api)
        ((get-in @state [:commands "work-done" :handler]) "")
        (is (= 0 @remove-calls))
        (is (= "automatic rebase onto `main` failed: subagent failed"
               @printed)))))

  (testing "/work-done preserves the worktree when merge verification fails"
    (let [printed      (atom nil)
          remove-calls (atom 0)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
                                            {:psi.agent-session/session-id "s-feature"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "main-s"
                                                                                :psi.session-info/cwd "/repo/main"
                                                                                :psi.session-info/name "main"}]
                                             :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/feature-x"
                                                                                      :git.worktree/branch-name "feature-x"}
                                             :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                :git.worktree/branch-name "main"}
                                                                               {:git.worktree/path "/repo/feature-x"
                                                                                :git.worktree/branch-name "feature-x"
                                                                                :git.worktree/current? true}]})
                                :mutate-fn (fn [op _params]
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.branch/merge! {:merged true}
                                               git.worktree/remove! (do (swap! remove-calls inc)
                                                                        {:success true})
                                               git.branch/delete! {:deleted true}
                                               nil))})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))
                    git/branch-tip-merged-into-current? (fn [ctx branch]
                                                          (cond
                                                            (= "/repo/feature-x" (:repo-dir ctx))
                                                            (= branch "main")

                                                            (= "/repo/main" (:repo-dir ctx))
                                                            false

                                                            :else false))
                    git/current-branch (fn [_ctx] "main")
                    git/current-commit (let [calls (atom -1)]
                                         (fn [_ctx]
                                           (case (swap! calls inc)
                                             0 "before-sha"
                                             1 "after-sha"
                                             "after-sha")))]
        (sut/init api)
        ((get-in @state [:commands "work-done" :handler]) "")
        (is (= 0 @remove-calls) "worktree removal must not run when merge is not verified")
        (is (re-find #"merge did not update main; worktree preserved for safety" @printed))
        (is (re-find #"source=feature-x" @printed))
        (is (re-find #"merge-reported=true" @printed))
        (is (re-find #"before-branch=main" @printed))
        (is (re-find #"after-branch=main" @printed))
        (is (re-find #"before-head=before-sha" @printed))
        (is (re-find #"after-head=after-sha" @printed))
        (is (re-find #"head-changed=true" @printed))
        (is (re-find #"verification=branch tip not ancestor of target HEAD" @printed)))))

  (testing "/work-done rejects execution on the main worktree"
    (let [printed (atom nil)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/main"
                                                                                      :git.worktree/branch-name "main"
                                                                                      :git.worktree/current? true}
                                             :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                :git.worktree/branch-name "main"
                                                                                :git.worktree/current? true}]})})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        ((get-in @state [:commands "work-done" :handler]) "")
        (is (= "already on main worktree; nothing to do"
               @printed))))))

(deftest work-main-worktree-guards-and-status-test
  (testing "/work-rebase rejects execution on the main worktree"
    (let [printed (atom nil)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/main"
                                                                                      :git.worktree/branch-name "main"
                                                                                      :git.worktree/current? true}
                                             :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                :git.worktree/branch-name "main"
                                                                                :git.worktree/current? true}]})})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        ((get-in @state [:commands "work-rebase" :handler]) "")
        (is (= "already on main worktree; nothing to rebase"
               @printed)))))

  (testing "/work-status renders linked worktrees and marks the current linked worktree"
    (let [printed (atom nil)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
                                            {:psi.agent-session/session-id "s-feature"
                                             :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/feature-x"
                                                                                      :git.worktree/branch-name "feature-x"
                                                                                      :git.worktree/current? true}
                                             :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                :git.worktree/branch-name "main"}
                                                                               {:git.worktree/path "/repo/feature-x"
                                                                                :git.worktree/branch-name "feature-x"
                                                                                :git.worktree/current? true}
                                                                               {:git.worktree/path "/repo/bug-y"
                                                                                :git.worktree/branch-name "bug-y"}]})})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        ((get-in @state [:commands "work-status" :handler]) "")
        (is (re-find #"Active worktrees:" @printed))
        (is (re-find #"- /repo/feature-x \[feature-x\] \(current\)" @printed))
        (is (re-find #"- /repo/bug-y \[bug-y\]" @printed))
        (is (not (re-find #"- /repo/main \[main\]" @printed))))))

  (testing "/work-status renders none when no linked worktrees exist"
    (let [printed (atom nil)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :psi.agent-session/git-worktree-current {:git.worktree/path "/repo/main"
                                                                                      :git.worktree/branch-name "main"
                                                                                      :git.worktree/current? true}
                                             :psi.agent-session/git-worktrees [{:git.worktree/path "/repo/main"
                                                                                :git.worktree/branch-name "main"
                                                                                :git.worktree/current? true}]})})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        ((get-in @state [:commands "work-status" :handler]) "")
        (is (= "Active worktrees:\n(none)" @printed))))))