(ns psi.tui.background-jobs-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.tui.app :as app]
   [psi.tui.ansi :as ansi]))

(defn- init-state-with-ui-snapshot
  [ui-snapshot]
  (let [init-fn (app/make-init "test-model" nil (fn [] ui-snapshot) nil {:dispatch-fn (constantly nil)})
        [state _] (init-fn)]
    (assoc state :width 120)))

(deftest background-jobs-widget-renders-from-canonical-ui-projection-test
  (testing "TUI renders background jobs from the projected widget instead of transcript text"
    (let [state (init-state-with-ui-snapshot
                 {:active-dialog nil
                  :widgets [{:placement :below-editor
                             :extension-id "psi-background-jobs"
                             :widget-id "background-jobs"
                             :content [{:text "job-1  [running]  agent-chain"
                                        :action {:type :command :command "/job job-1"}}
                                       {:text "job-2  [pending-cancel]  agent-run"
                                        :action {:type :command :command "/job job-2"}}]}]
                  :visible-notifications []
                  :tools-expanded? false})
          plain (ansi/strip-ansi (app/view state))]
      (is (str/includes? plain "job-1  [running]  agent-chain"))
      (is (str/includes? plain "job-2  [pending-cancel]  agent-run"))
      (is (not (str/includes? plain "Background job ─"))))))
