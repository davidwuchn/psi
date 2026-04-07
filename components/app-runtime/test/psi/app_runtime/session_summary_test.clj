(ns psi.app-runtime.session-summary-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.runtime :as runtime]
   [psi.app-runtime.session-summary :as summary]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context opts)
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest session-summary-builds-shared-model-and-status-fragments-test
  (testing "session summary exposes shared header/status fragments"
    (let [[ctx sid] (create-session-context)
          _         (dispatch/dispatch! ctx :session/set-model
                                        {:session-id sid
                                         :model {:provider "openai"
                                                 :id "gpt-5.3-codex"
                                                 :reasoning true}}
                                        {:origin :core})
          _         (dispatch/dispatch! ctx :session/set-thinking-level
                                        {:session-id sid :level :high}
                                        {:origin :core})
          _         (runtime/journal-user-message-in! ctx sid "Investigate failures" nil)
          model     (summary/session-summary ctx sid)]
      (is (= sid (:session-id model)))
      (is (= "Investigate failures" (:session-display-name model)))
      (is (= "(openai) gpt-5.3-codex • thinking high"
             (:header-model-label model)))
      (is (= (str "session: " sid " phase:idle streaming:no compacting:no pending:0 retry:0")
             (:status-session-line model))))))
