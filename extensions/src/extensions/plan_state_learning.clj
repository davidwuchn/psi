(ns extensions.plan-state-learning
  "Automate PLAN/STATE/LEARNING maintenance after non-PSL commits.

   Trigger:
   - extension event: git_head_changed

   Behavior:
   - skip when latest commit subject contains [psi:psl-auto]
   - phase 1: append trace lines to PLAN.md + STATE.md, commit if changed
   - phase 2: append trace line to LEARNING.md, commit if changed
   - emit visible transcript messages via psi.extension/send-message"
  (:require
   [clojure.string :as str]))

(def ^:private marker "[psi:psl-auto]")
(def ^:private custom-type "plan-state-learning")

(defn- now-iso []
  (str (java.time.Instant/now)))

(defn- squish
  [s]
  (-> (str (or s ""))
      (str/replace #"\s+" " ")
      (str/trim)))

(defn- shell-quote
  [s]
  (str "'" (str/replace (str (or s "")) "'" "'\"'\"'") "'"))

(defn- tool-content
  [tool-result]
  (str (or (:psi.extension.tool/content tool-result) "")))

(defn- bash!
  [mutate-fn command]
  (mutate-fn 'psi.extension.tool/bash {:command command :timeout 30}))

(defn- send-message!
  [mutate-fn text]
  (mutate-fn 'psi.extension/send-message
             {:role "assistant"
              :content text
              :custom-type custom-type}))

(defn- latest-commit
  [mutate-fn]
  (let [result (bash! mutate-fn "git log -1 --pretty=format:%H%n%s")
        out    (tool-content result)
        [sha subject] (str/split-lines out)]
    {:sha (squish sha)
     :subject (squish subject)}))

(defn- append-trace-line!
  [mutate-fn path line source-sha]
  (let [file-q (shell-quote path)
        sha-q  (shell-quote (str source-sha))
        line-q (shell-quote line)
        command (str
                 "if [ -f " file-q " ] && grep -Fq " sha-q " " file-q "; then "
                 "  echo __PSL_NO_CHANGE__; "
                 "else "
                 "  printf '\n%s\n' " line-q " >> " file-q "; "
                 "  echo __PSL_CHANGED__; "
                 "fi")
        out (tool-content (bash! mutate-fn command))]
    (str/includes? out "__PSL_CHANGED__")))

(defn- commit-files!
  [mutate-fn files commit-message]
  (let [joined (str/join " " (map shell-quote files))
        msg-q  (shell-quote commit-message)
        command (str
                 "git add " joined " && "
                 "if git diff --cached --quiet -- " joined "; then "
                 "  echo __PSL_NO_COMMIT__; "
                 "else "
                 "  git commit -m " msg-q " >/dev/null && "
                 "  echo __PSL_COMMIT__ $(git rev-parse HEAD); "
                 "fi")
        out (tool-content (bash! mutate-fn command))]
    (when-let [[_ sha] (re-find #"__PSL_COMMIT__\s+([0-9a-f]{7,40})" out)]
      sha)))

(defn- handle-git-head-changed
  [mutate-fn {:keys [head]}]
  (let [{:keys [sha subject]} (latest-commit mutate-fn)
        source-sha (or (not-empty sha) head "unknown")
        source7    (subs source-sha 0 (min 7 (count source-sha)))]
    (if (str/includes? subject marker)
      (do
        (send-message! mutate-fn
                       (str "PSL skipped for " source7 " (self commit marker " marker ")."))
        {:skip? true :reason :self-commit :source-sha source-sha :subject subject})
      (let [subject* (squish subject)
            ts       (now-iso)
            phase1-line (str "- Δ psl source=" source-sha " at=" ts " :: " subject*)
            phase2-line (str "- λ psl source=" source-sha " at=" ts " :: " subject*)
            _        (send-message! mutate-fn (str "PSL sync start for " source7 "."))
            _        (append-trace-line! mutate-fn "PLAN.md" phase1-line source-sha)
            _        (append-trace-line! mutate-fn "STATE.md" phase1-line source-sha)
            phase1-sha (commit-files! mutate-fn
                                      ["PLAN.md" "STATE.md"]
                                      (str "◈ Δ Auto-update PLAN/STATE " marker " source=" source7))
            _        (send-message! mutate-fn
                                    (if phase1-sha
                                      (str "PSL phase1 committed PLAN/STATE at " (subs phase1-sha 0 (min 7 (count phase1-sha))) ".")
                                      "PSL phase1: no PLAN/STATE changes to commit."))
            _        (append-trace-line! mutate-fn "LEARNING.md" phase2-line source-sha)
            phase2-sha (commit-files! mutate-fn
                                      ["LEARNING.md"]
                                      (str "◈ λ Auto-update LEARNING " marker " source=" source7))
            _        (send-message! mutate-fn
                                    (if phase2-sha
                                      (str "PSL phase2 committed LEARNING at " (subs phase2-sha 0 (min 7 (count phase2-sha))) ".")
                                      "PSL phase2: no LEARNING changes to commit."))]
        {:skip? false
         :source-sha source-sha
         :subject subject
         :phase1-sha phase1-sha
         :phase2-sha phase2-sha}))))

(defn init
  [api]
  (let [mutate-fn (:mutate api)]
    (mutate-fn 'psi.extension/register-handler
               {:event-name "git_head_changed"
                :handler-fn (fn [ev]
                              (try
                                (handle-git-head-changed mutate-fn ev)
                                (catch Exception e
                                  (send-message! mutate-fn
                                                 (str "PSL error: " (ex-message e)))
                                  {:error (ex-message e)})))})
    (send-message! mutate-fn "PSL extension loaded.")))
