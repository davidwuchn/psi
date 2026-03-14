(ns extensions.work-on
  (:require
   [clojure.string :as str]))

(defonce ^:private state
  (atom {:query-fn nil
         :mutate-fn nil
         :create-session-fn nil
         :switch-session-fn nil
         :path nil}))

(def ^:private stopwords
  #{"a" "an" "the" "and" "or" "but" "in" "on" "at"
    "to" "for" "of" "with" "by" "from" "is" "are"
    "was" "were" "be" "been" "has" "have" "had"
    "do" "does" "did" "will" "would" "could" "should"
    "it" "its" "this" "that" "these" "those" "i" "we"})

(defn- query!
  [q]
  (when-let [qf (:query-fn @state)]
    (try
      (qf q)
      (catch Exception _ nil))))

(defn- mutate!
  [op params]
  (when-let [mf (:mutate-fn @state)]
    (try
      (mf op params)
      (catch Exception _ nil))))

(defn mechanical-slug
  [description]
  (let [words       (->> (str/lower-case (or description ""))
                         (re-seq #"[a-z0-9]+"))
        significant (->> words
                         (remove #(or (contains? stopwords %)
                                      (<= (count %) 1)))
                         (take 4))
        fallback    (when (empty? significant) ["work"])
        terms       (vec (or (seq significant) fallback))]
    {:raw-description description
     :terms terms
     :slug (str/join "-" terms)
     :branch-name (str/join "-" terms)}))

(defn- current-session-query []
  (query! [:psi.agent-session/session-id
           :psi.agent-session/session-name
           :psi.agent-session/session-file
           :psi.agent-session/cwd
           :psi.agent-session/system-prompt
           :psi.agent-session/host-sessions
           :psi.agent-session/git-worktree-current
           :psi.agent-session/git-worktrees]))

(defn- current-main-worktree
  []
  (let [d (current-session-query)
        worktrees (or (:psi.agent-session/git-worktrees d) [])
        current   (:psi.agent-session/git-worktree-current d)]
    (or (some #(when (= (get % :git.worktree/path)
                        (get current :git.worktree/path))
                 %)
              (filter #(not (:git.worktree/current? % false)) worktrees))
        (some #(when (and (not= (get % :git.worktree/path)
                                (get current :git.worktree/path))
                          (or (= (get % :git.worktree/branch-name) "main")
                              (= (get % :git.worktree/branch-name) "master")))
                 %)
              worktrees)
        (first worktrees))))

(defn- find-session-for-worktree
  [worktree-path]
  (let [sessions (or (:psi.agent-session/host-sessions (current-session-query)) [])]
    (some (fn [s]
            (when (= worktree-path (:psi.session-info/cwd s))
              s))
          sessions)))

(defn- switch-to-session!
  [session-id]
  (when-let [f (:switch-session-fn @state)]
    (try
      (f session-id)
      (catch Exception _ nil))))

(defn- sibling-worktree-path
  [main-path slug]
  (let [f (java.io.File. (str main-path))]
    (str (.getParentFile f) java.io.File/separator slug)))

(defn- trim-arg
  [s]
  (some-> s str/trim not-empty))

(defn- print-line
  [text]
  (println (str text)))

(defn- create-worktree-session!
  [description worktree-path _parent-session-id]
  (when-let [f (:create-session-fn @state)]
    (let [session (current-session-query)]
      (try
        (f {:session-name description
            :worktree-path worktree-path
            :system-prompt (:psi.agent-session/system-prompt session)})
        (catch Exception _ nil)))))

(defn work-on!
  [description]
  (let [description* (trim-arg description)]
    (cond
      (nil? description*)
      {:ok? false :error "usage: /work-on <description>"}

      :else
      (let [session    (current-session-query)
            current-wt (:psi.agent-session/git-worktree-current session)
            main-wt    (or (current-main-worktree)
                           current-wt)]
        (cond
          (nil? current-wt)
          {:ok? false :error "not inside a git repository"}

          (nil? main-wt)
          {:ok? false :error "main worktree not found"}

          :else
          (let [{:keys [slug branch-name]} (mechanical-slug description*)
                worktree-path (sibling-worktree-path (:git.worktree/path main-wt) slug)
                add-result    (mutate! 'git.worktree/add!
                                       {:input {:path worktree-path
                                                :branch branch-name
                                                :base_ref nil
                                                :create_branch true}})]
            (if-not (:success add-result)
              {:ok? false
               :error (str "worktree creation failed: " (:error add-result))}
              (let [sd (create-worktree-session! description*
                                                worktree-path
                                                (:psi.agent-session/session-id session))]
                {:ok? true
                 :worktree-path worktree-path
                 :branch-name branch-name
                 :session-id (:session-id sd)
                 :session-name description*}))))))))

(defn work-merge!
  []
  (let [session      (current-session-query)
        current-wt   (:psi.agent-session/git-worktree-current session)
        current-path (:git.worktree/path current-wt)
        current-branch (:git.worktree/branch-name current-wt)
        main-wt      (current-main-worktree)]
    (cond
      (or (nil? current-wt) (nil? current-path))
      {:ok? false :error "not inside a git repository"}

      (or (nil? main-wt)
          (= current-path (:git.worktree/path main-wt)))
      {:ok? false :error "already on main worktree; nothing to merge"}

      :else
      (let [merge-result  (mutate! 'git.branch/merge!
                                   {:input {:branch current-branch
                                            :strategy :ff_only}})]
        (cond
          (not (:merged merge-result))
          {:ok? false
           :error (if (and (:error merge-result)
                           (str/includes? (:error merge-result) "not fast-forwardable"))
                    "branch is not fast-forwardable onto main; rebase first with /work-rebase"
                    (or (:error merge-result) "merge failed"))}

          :else
          (let [remove-result   (mutate! 'git.worktree/remove!
                                         {:input {:path current-path
                                                  :force false}})
                delete-result   (mutate! 'git.branch/delete!
                                         {:input {:branch current-branch
                                                  :force false}})
                main-path       (:git.worktree/path main-wt)
                main-session    (or (find-session-for-worktree main-path)
                                    (when main-path
                                      {:psi.session-info/id
                                       (:psi.agent-session/session-id
                                        (create-worktree-session!
                                         (or (:git.worktree/branch-name main-wt) "main")
                                         main-path
                                         nil))}))
                _               (switch-to-session! (:psi.session-info/id main-session))]
            {:ok? true
             :branch current-branch
             :into-branch (or (:branch (mutate! 'git.branch/default {}))
                              (:git.worktree/branch-name main-wt))
             :worktree-removed (:success remove-result)
             :branch-deleted (:deleted delete-result)
             :main-session-id (:psi.session-info/id main-session)}))))))

(defn work-rebase!
  []
  (let [session        (current-session-query)
        current-wt     (:psi.agent-session/git-worktree-current session)
        current-path    (:git.worktree/path current-wt)
        current-branch  (:git.worktree/branch-name current-wt)
        main-wt         (current-main-worktree)
        default-branch  (or (:branch (mutate! 'git.branch/default {}))
                            (:git.worktree/branch-name main-wt)
                            "main")]
    (cond
      (or (nil? current-wt) (nil? current-path))
      {:ok? false :error "not inside a git repository"}

      (or (nil? main-wt)
          (= current-path (:git.worktree/path main-wt)))
      {:ok? false :error "already on main worktree; nothing to rebase"}

      :else
      (let [result (mutate! 'git.branch/rebase!
                            {:input {:onto default-branch
                                     :branch current-branch}})]
        {:ok? (:success result)
         :branch current-branch
         :onto default-branch
         :error (:error result)}))))

(defn work-status-text
  []
  (let [session   (current-session-query)
        current   (:psi.agent-session/git-worktree-current session)
        worktrees (or (:psi.agent-session/git-worktrees session) [])
        linked    (remove #(= (:git.worktree/path %) (:git.worktree/path (current-main-worktree))) worktrees)]
    (str "Active worktrees:\n"
         (if (seq linked)
           (str/join "\n"
                     (map (fn [wt]
                            (str "- " (:git.worktree/path wt)
                                 " [" (or (:git.worktree/branch-name wt) "detached") "]"
                                 (when (= (:git.worktree/path wt) (:git.worktree/path current))
                                   " (current)")))
                          linked))
           "(none)"))))

(defn- handle-work-on-command
  [args]
  (let [result (work-on! args)]
    (if (:ok? result)
      (print-line (str "Working in `" (:worktree-path result)
                       "` on branch `" (:branch-name result) "`"))
      (print-line (:error result)))))

(defn- handle-work-merge-command
  [_args]
  (let [result (work-merge!)]
    (if (:ok? result)
      (print-line (str "Merged `" (:branch result)
                       "` into `" (:into-branch result)
                       "`, removed worktree, kept session transcript"))
      (print-line (:error result)))))

(defn- handle-work-rebase-command
  [_args]
  (let [result (work-rebase!)]
    (if (:ok? result)
      (print-line (str "Rebased `" (:branch result) "` onto `" (:onto result) "`"))
      (print-line (:error result)))))

(defn- handle-work-status-command
  [_args]
  (print-line (work-status-text)))

(defn init
  [api]
  (swap! state assoc
         :query-fn (:query api)
         :mutate-fn (:mutate api)
         :create-session-fn (:create-session api)
         :switch-session-fn (:switch-session api)
         :path (:path api))
  ((:register-command api) "work-on"
   {:description "Create a sibling git worktree + branch and continue there"
    :handler handle-work-on-command})
  ((:register-command api) "work-merge"
   {:description "Merge current worktree branch into main and clean up"
    :handler handle-work-merge-command})
  ((:register-command api) "work-rebase"
   {:description "Rebase current worktree branch onto main"
    :handler handle-work-rebase-command})
  ((:register-command api) "work-status"
   {:description "Show active worktree overview"
    :handler handle-work-status-command}))
