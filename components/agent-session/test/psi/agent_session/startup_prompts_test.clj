(ns psi.agent-session.startup-prompts-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.startup-prompts :as sp]))

(defn- tmp-dir []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "psi-startup-prompts-" (java.util.UUID/randomUUID)))]
    (.mkdirs d)
    (.getAbsolutePath d)))

(deftest discover-rules-project-overrides-global-by-id-test
  (let [dir (tmp-dir)
        global (io/file dir "global.edn")
        project (io/file dir "project.edn")]
    (spit global (pr-str [{:id "same" :phase :system-bootstrap :priority 50 :text "global"}
                          {:id "g-only" :phase :project-bootstrap :priority 200 :text "g"}]))
    (spit project (pr-str [{:id "same" :phase :system-bootstrap :priority 40 :text "project"}
                           {:id "p-only" :phase :mode-bootstrap :priority 10 :text "p"}]))
    (let [rules (sp/discover-rules {:cwd dir
                                    :global-prompts-file (.getAbsolutePath global)
                                    :project-prompts-file (.getAbsolutePath project)})
          ids   (mapv :id rules)]
      (is (= ["same" "g-only" "p-only"] ids))
      (is (= "project" (:text (first rules)))))))

(deftest discover-rules-orders-by-phase-priority-source-id-test
  (let [dir (tmp-dir)
        global (io/file dir "global.edn")
        project (io/file dir "project.edn")]
    (spit global (pr-str [{:id "b" :phase :project-bootstrap :priority 20 :text "b"}
                          {:id "a" :phase :project-bootstrap :priority 20 :text "a"}
                          {:id "s" :phase :system-bootstrap :priority 999 :text "s"}]))
    (spit project (pr-str [{:id "m" :phase :mode-bootstrap :priority 1 :text "m"}]))
    (let [ids (mapv :id (sp/discover-rules {:cwd dir
                                            :global-prompts-file (.getAbsolutePath global)
                                            :project-prompts-file (.getAbsolutePath project)}))]
      (is (= ["s" "a" "b" "m"] ids)))))

(deftest discover-rules-filters-disabled-and-mode-mismatch-test
  (let [dir (tmp-dir)
        global (io/file dir "global.edn")
        project (io/file dir "project.edn")]
    (spit global (pr-str [{:id "on" :text "on" :phase :system-bootstrap}
                          {:id "off" :text "off" :enabled? false}
                          {:id "debug-only" :text "d" :conditions {"mode" "debug"}}]))
    (spit project (pr-str []))
    (let [build-ids (mapv :id (sp/discover-rules {:cwd dir
                                                  :session-mode :build
                                                  :global-prompts-file (.getAbsolutePath global)
                                                  :project-prompts-file (.getAbsolutePath project)}))
          debug-ids (mapv :id (sp/discover-rules {:cwd dir
                                                  :session-mode :debug
                                                  :global-prompts-file (.getAbsolutePath global)
                                                  :project-prompts-file (.getAbsolutePath project)}))]
      (is (= ["on"] build-ids))
      (is (= #{"on" "debug-only"} (set debug-ids))))))

(deftest discover-rules-invalid-edn-degrades-to-empty-test
  (let [dir (tmp-dir)
        bad (io/file dir "bad.edn")]
    (spit bad "[not-edn")
    (is (= []
           (sp/discover-rules {:cwd dir
                               :global-prompts-file (.getAbsolutePath bad)
                               :project-prompts-file (.getAbsolutePath bad)})))))

(deftest should-run-startup-prompts-policy-test
  (testing "defaults: new-root true, spawned modes false"
    (is (true? (sp/should-run? {:spawn-mode :new-root})))
    (is (false? (sp/should-run? {:spawn-mode :fork-head})))
    (is (false? (sp/should-run? {:spawn-mode :fork-at-entry})))
    (is (false? (sp/should-run? {:spawn-mode :subagent}))))

  (testing "explicit overrides respected"
    (is (true? (sp/should-run? {:spawn-mode :fork-head :run-on-fork-head? true})))
    (is (true? (sp/should-run? {:spawn-mode :fork-at-entry :run-on-fork-at-entry? true})))
    (is (true? (sp/should-run? {:spawn-mode :subagent :run-on-subagent? true})))
    (is (false? (sp/should-run? {:spawn-mode :new-root :run-on-new-root? false})))))
