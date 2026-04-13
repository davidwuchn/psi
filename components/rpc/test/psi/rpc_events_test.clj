(ns psi.rpc-events-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.session-state :as ss]
   [psi.rpc.events :as rpc.events]
   [psi.rpc-test-support :as support]))

(deftest footer-updated-payload-uses-default-footer-projection-values-test
  (testing "footer payload mirrors default footer path/stats/status composition"
    (let [home    (System/getProperty "user.home")
          cwd     (str home "/projects/hugoduncan/psi/psi-main")
          [ctx session-id] (support/create-session-context {:cwd cwd})
          payload (with-redefs [session/query-in
                                (fn [_ctx sid q]
                                  (is (= session-id sid))
                                  (is (= @#'rpc.events/footer-query q))
                                  {:psi.agent-session/cwd cwd
                                   :psi.agent-session/git-branch "master"
                                   :psi.agent-session/session-name "xhig"
                                   :psi.agent-session/session-display-name "xhig"
                                   :psi.agent-session/usage-input 172000
                                   :psi.agent-session/usage-output 17000
                                   :psi.agent-session/usage-cache-read 5200000
                                   :psi.agent-session/usage-cache-write 1200
                                   :psi.agent-session/usage-cost-total 1.444
                                   :psi.agent-session/context-fraction 0.319
                                   :psi.agent-session/context-window 272000
                                   :psi.agent-session/auto-compaction-enabled true
                                   :psi.agent-session/model-provider "openai-codex"
                                   :psi.agent-session/model-id "gpt-5.3-codex"
                                   :psi.agent-session/model-reasoning true
                                   :psi.agent-session/thinking-level :xhigh
                                   :psi.agent-session/effective-reasoning-effort "high"
                                   :psi.ui/statuses [{:extension-id "b" :text "TS+ESL,Prett"}
                                                     {:extension-id "a" :text "Clojure-LSP\nclojure-lsp"}]})]
                    (rpc.events/footer-updated-payload ctx session-id))]
      (is (= "~/projects/hugoduncan/psi/psi-main (master) • xhig"
             (:path-line payload)))
      (is (= ["↑172k" "↓17k" "CR5.2M" "CW1.2k" "$1.444" "31.9%/272k (auto)"]
             (:usage-parts payload)))
      (is (= "(openai-codex) gpt-5.3-codex • thinking high"
             (:model-text payload)))
      (is (str/includes? (:stats-line payload) "↑172k"))
      (is (str/includes? (:stats-line payload) "↓17k"))
      (is (str/includes? (:stats-line payload) "CR5.2M"))
      (is (str/includes? (:stats-line payload) "CW1.2k"))
      (is (str/includes? (:stats-line payload) "$1.444"))
      (is (str/includes? (:stats-line payload) "31.9%/272k (auto)"))
      (is (str/includes? (:stats-line payload) "(openai-codex) gpt-5.3-codex • thinking high"))
      (is (= "Clojure-LSP clojure-lsp TS+ESL,Prett"
             (:status-line payload))))))

(deftest footer-updated-payload-prefers-session-display-name-test
  (testing "footer payload uses derived display name when explicit session name is absent"
    (let [home    (System/getProperty "user.home")
          cwd     (str home "/projects/hugoduncan/psi/psi-main")
          [ctx session-id] (support/create-session-context {:cwd cwd})
          payload (with-redefs [session/query-in
                                (fn [_ctx sid q]
                                  (is (= session-id sid))
                                  (is (= @#'rpc.events/footer-query q))
                                  {:psi.agent-session/cwd cwd
                                   :psi.agent-session/git-branch "master"
                                   :psi.agent-session/session-name nil
                                   :psi.agent-session/session-display-name "Investigate failing tests"
                                   :psi.agent-session/usage-input 0
                                   :psi.agent-session/usage-output 0
                                   :psi.agent-session/usage-cache-read 0
                                   :psi.agent-session/usage-cache-write 0
                                   :psi.agent-session/usage-cost-total 0.0
                                   :psi.agent-session/context-fraction nil
                                   :psi.agent-session/context-window 272000
                                   :psi.agent-session/auto-compaction-enabled false
                                   :psi.agent-session/model-provider "openai-codex"
                                   :psi.agent-session/model-id "gpt-5.3-codex"
                                   :psi.agent-session/model-reasoning true
                                   :psi.agent-session/thinking-level :high
                                   :psi.agent-session/effective-reasoning-effort "high"
                                   :psi.ui/statuses []})]
                    (rpc.events/footer-updated-payload ctx session-id))]
      (is (= "~/projects/hugoduncan/psi/psi-main (master) • Investigate failing tests"
             (:path-line payload)))
      (is (= ["?/272k"]
             (:usage-parts payload)))
      (is (= "(openai-codex) gpt-5.3-codex • thinking high"
             (:model-text payload))))))

(deftest session-updated-payload-includes-model-metadata-test
  (testing "session payload includes model metadata for frontend header projection"
    (let [[ctx sid] (support/create-session-context)
          _         (dispatch/dispatch! ctx :session/set-model
                                        {:session-id sid
                                         :model {:provider "openai"
                                                 :id "gpt-5.3-codex"
                                                 :reasoning true}}
                                        {:origin :core})
          _         (dispatch/dispatch! ctx :session/set-thinking-level
                                        {:session-id sid :level :xhigh}
                                        {:origin :core})
          _         (ss/apply-root-state-update-in! ctx
                                                    (ss/session-update sid #(assoc %
                                                                                   :retry-attempt 2
                                                                                   :steering-messages [{:content "a"}]
                                                                                   :follow-up-messages [{:content "b"}])))
          payload   (rpc.events/session-updated-payload ctx sid)]
      (is (= sid (:session-id payload)))
      (is (= "openai" (:model-provider payload)))
      (is (= "gpt-5.3-codex" (:model-id payload)))
      (is (= true (:model-reasoning payload)))
      (is (= "xhigh" (:thinking-level payload)))
      (is (= "high" (:effective-reasoning-effort payload)))
      (is (= "(openai) gpt-5.3-codex • thinking high" (:header-model-label payload)))
      (is (= (str "session: " sid " phase:idle streaming:no compacting:no pending:2 retry:2")
             (:status-session-line payload)))
      (is (= 2 (:pending-message-count payload)))
      (is (= 2 (:retry-attempt payload))))))

(deftest session-updated-payload-includes-derived-session-display-name-test
  (testing "session payload includes derived display name from latest non-command user message"
    (let [[ctx sid] (support/create-session-context)
          _         (runtime/journal-user-message-in! ctx sid "Investigate failing tests in RPC footer" nil)
          _         (runtime/journal-user-message-in! ctx sid "/tree" nil)
          payload   (rpc.events/session-updated-payload ctx sid)]
      (is (= "Investigate failing tests in RPC footer"
             (:session-display-name payload))))))

(deftest progress-event-thinking-delta-maps-to-rpc-thinking-topic-test
  (let [{:keys [event data]}
        (rpc.events/progress-event->rpc-event {:event-kind :thinking-delta :text "plan"})]
    (is (= "assistant/thinking-delta" event))
    (is (= "plan" (:text data)))))

(deftest footer-updated-payload-includes-model-and-thinking-when-session-reasoning-enabled-test
  (testing "footer payload includes model/thinking details from active session query"
    (let [[ctx session-id] (support/create-session-context)
          _                (dispatch/dispatch! ctx :session/set-model
                                               {:session-id session-id
                                                :model {:provider "openai"
                                                        :id "gpt-5.3-codex"
                                                        :reasoning true}}
                                               {:origin :core})
          _                (dispatch/dispatch! ctx :session/set-thinking-level
                                               {:session-id session-id :level :high}
                                               {:origin :core})
          _                (dispatch/dispatch! ctx :session/update-context-usage
                                               {:session-id session-id :tokens 4000 :window 100000}
                                               {:origin :core})
          _                (ss/journal-append-in! ctx session-id
                                                  {:kind :message
                                                   :session-id session-id
                                                   :data {:message {:role "assistant"
                                                                    :usage {:input-tokens 111
                                                                            :output-tokens 22}}}})
          payload          (rpc.events/footer-updated-payload ctx session-id)
          stats-line       (:stats-line payload)]
      (is (= ["↑111" "↓22" "4.0%/100k"]
             (:usage-parts payload)))
      (is (= "(openai) gpt-5.3-codex • thinking high"
             (:model-text payload)))
      (is (string? stats-line))
      (is (str/includes? stats-line "↑111"))
      (is (str/includes? stats-line "↓22"))
      (is (str/includes? stats-line "4.0%/100k"))
      (is (str/includes? stats-line "(openai) gpt-5.3-codex • thinking high")))))

