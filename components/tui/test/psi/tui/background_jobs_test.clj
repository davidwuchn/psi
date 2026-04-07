(ns psi.tui.background-jobs-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.tui.app :as app]))

(defn- init-state-with-message
  [message]
  (let [init-fn (app/make-init "test-model" nil nil nil {:dispatch-fn (constantly nil)})
        [state _] (init-fn)]
    (assoc state :messages [message] :width 120)))

(deftest background-job-terminal-message-renders-as-plain-assistant-text-test
  (testing "TUI currently renders background-job-terminal messages as plain assistant text"
    (let [state (init-state-with-message
                 {:role :assistant
                  :custom-type "background-job-terminal"
                  :text "── Background job ──────────────────────\n{:job-id \"job-1\" :status :completed}\n───────────────────────────────────────"})
          out (app/view state)]
      (is (str/includes? out "Background job"))
      (is (str/includes? out "job-1")))))
