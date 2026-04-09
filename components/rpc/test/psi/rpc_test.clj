(ns psi.rpc-test
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.ai.models :as ai-models]
   [psi.agent-core.core :as agent]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.persistence :as persist]
   [psi.rpc :as rpc]
   [psi.rpc.events :as rpc.events]
   [psi.rpc.transport :as rpc.transport]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tools :as tools]
   [psi.agent-session.prompt-runtime :as prompt-runtime]
   [psi.memory.core :as memory]
   [psi.memory.store :as store]))

(defn- assistant-msg->execution-result
  "Convert an old-style assistant message map (as returned by run-agent-loop-fn mocks)
   into the shaped execution-result map expected by the new prompt lifecycle."
  [session-id assistant-msg]
  (let [tool-calls (vec (filter #(= :tool-call (:type %)) (or (:content assistant-msg) [])))]
    {:execution-result/turn-id             (str (java.util.UUID/randomUUID))
     :execution-result/session-id          session-id
     :execution-result/prepared-request-id nil
     :execution-result/assistant-message   assistant-msg
     :execution-result/usage              (:usage assistant-msg)
     :execution-result/provider-captures  {:request-captures [] :response-captures []}
     :execution-result/turn-outcome       (cond
                                            (= :error (:stop-reason assistant-msg)) :turn.outcome/error
                                            (seq tool-calls) :turn.outcome/tool-use
                                            :else :turn.outcome/stop)
     :execution-result/tool-calls         tool-calls
     :execution-result/error-message      (:error-message assistant-msg)
     :execution-result/http-status        (:http-status assistant-msg)
     :execution-result/stop-reason        (or (:stop-reason assistant-msg) :stop)}))

(defn- run-loop
  "Run a stdio loop. When the state atom contains :run-agent-loop-fn, it is
   automatically bridged to the new prompt lifecycle by installing an
   execute-prepared-request-fn override on the ctx so existing mocks work."
  ([input handler]
   (run-loop input handler (atom {}) 0))
  ([input handler state]
   (run-loop input handler state 0))
  ([input handler state wait-ms]
   (let [out (java.io.StringWriter.)
         err (java.io.StringWriter.)]
     (rpc/run-stdio-loop! {:in              (java.io.StringReader. input)
                           :out             out
                           :err             err
                           :state           state
                           :request-handler handler})
     (when (pos? wait-ms)
       (Thread/sleep wait-ms))
     {:out-lines (->> (str/split-lines (str out))
                      (remove str/blank?)
                      vec)
      :err-text  (str err)
      :state     @state})))

(defn- parse-frames [lines]
  (mapv edn/read-string lines))

(defn- enqueue-active-dialog!
  "Directly place a dialog into the active slot of canonical ui-state.
   Bypasses the dispatch layer so tests can control dialog-id precisely."
  [ctx dialog]
  (ss/update-state-value-in! ctx (ss/state-path :ui-state)
                             #(assoc-in % [:dialog-queue :active] dialog)))

(defn- active-dialog-in
  "Read the active dialog from canonical ui-state."
  [ctx]
  (get-in (ss/get-state-value-in ctx (ss/state-path :ui-state))
          [:dialog-queue :active]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context opts)
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- bridge-execute-fn
  "Build an execute-prepared-request-fn that delegates to an old-style
   run-agent-loop-fn mock, adapting between the new execution-result shape
   and the old assistant-message return shape."
  [run-agent-loop-fn]
  (fn [_ai-ctx ctx session-id agent-ctx prepared-request progress-queue]
    (let [result (run-agent-loop-fn nil ctx session-id agent-ctx
                                    (:prepared-request/model prepared-request)
                                    (:prepared-request/messages prepared-request)
                                    {:progress-queue progress-queue})]
      (assistant-msg->execution-result session-id result))))

(defn- make-handler
  "Build an RPC request handler. When state contains :run-agent-loop-fn, it is
   bridged onto the ctx as :execute-prepared-request-fn so the new prompt
   lifecycle uses the mock instead of real AI streaming."
  [ctx state]
  (let [bridge-fn (:run-agent-loop-fn @state)
        ctx*      (if bridge-fn
                    (assoc ctx :execute-prepared-request-fn (bridge-execute-fn bridge-fn))
                    ctx)]
    (rpc/make-session-request-handler
     ctx*
     (select-keys @state [:rpc-ai-model
                          :on-new-session!
                          :sync-on-git-head-change?]))))

(defn- stream-body
  [s]
  (java.io.ByteArrayInputStream. (.getBytes s "UTF-8")))

(def ^:private openai-chatgpt-test-token
  "aaa.eyJodHRwczovL2FwaS5vcGVuYWkuY29tL2F1dGgiOnsiY2hhdGdwdF9hY2NvdW50X2lkIjoiYWNjX3Rlc3QifX0.bbb")

(deftest footer-updated-payload-uses-default-footer-projection-values-test
  (testing "footer payload mirrors default footer path/stats/status composition"
    (let [home    (System/getProperty "user.home")
          cwd     (str home "/projects/hugoduncan/psi/psi-main")
          [ctx _] (create-session-context {:cwd cwd})
          payload (with-redefs [session/query-in
                                (fn [_ctx q]
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
                    (rpc.events/footer-updated-payload ctx))]
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
          [ctx _] (create-session-context {:cwd cwd})
          payload (with-redefs [session/query-in
                                (fn [_ctx q]
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
                    (rpc.events/footer-updated-payload ctx))]
      (is (= "~/projects/hugoduncan/psi/psi-main (master) • Investigate failing tests"
             (:path-line payload)))
      (is (= ["?/272k"]
             (:usage-parts payload)))
      (is (= "(openai-codex) gpt-5.3-codex • thinking high"
             (:model-text payload))))))

(deftest session-updated-payload-includes-model-metadata-test
  (testing "session payload includes model metadata for frontend header projection"
    (let [[ctx sid] (create-session-context)
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
    (let [[ctx sid] (create-session-context)
          _         (runtime/journal-user-message-in! ctx sid "Investigate failing tests in RPC footer" nil)
          _         (runtime/journal-user-message-in! ctx sid "/tree" nil)
          payload   (rpc.events/session-updated-payload ctx sid)]
      (is (= "Investigate failing tests in RPC footer"
             (:session-display-name payload))))))

(deftest run-stdio-loop-validates-request-envelopes-test
  (testing "returns canonical protocol/transport errors for invalid input frames"
    (let [{:keys [out-lines]}
          (run-loop (str "\n"
                         "{:kind :request :op \"ping\"}\n"
                         "{:id \"1\" :kind :response :op \"ping\"}\n"
                         "{:id \"2\" :kind :request :op \"ping\" :x 1}\n"
                         "not-edn\n")
                    (fn [_ _ _] nil))
          frames (parse-frames out-lines)]
      (is (= 5 (count frames)))
      (is (= ["transport/invalid-frame"
              "protocol/invalid-envelope"
              "protocol/invalid-envelope"
              "protocol/invalid-envelope"
              "protocol/invalid-envelope"]
             (mapv :error-code frames)))
      (is (every? #(= :error (:kind %)) frames)))))

(deftest run-stdio-loop-emits-canonical-response-frame-test
  (testing "writer strips non-canonical keys from response frames"
    (let [{:keys [out-lines]}
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"42\" :kind :request :op \"ping\"}\n")
                    (fn [request _emit _state]
                      (assoc (rpc.transport/response-frame (:id request) (:op request) true {:pong true})
                             :extra "drop-me")))
          frame (-> out-lines parse-frames second)]
      (is (= {:id "42"
              :kind :response
              :op "ping"
              :ok true
              :data {:pong true}}
             frame))
      (is (not (contains? frame :extra))))))

(deftest run-stdio-loop-routes-handler-stdout-to-stderr-test
  (testing "non-protocol output from request handler is redirected to stderr"
    (let [{:keys [out-lines err-text]}
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"10\" :kind :request :op \"ping\"}\n")
                    (fn [request _ _]
                      (println "diagnostic line")
                      (rpc.transport/response-frame (:id request) (:op request) true {:pong true})))
          frame (-> out-lines parse-frames second)]
      (is (= :response (:kind frame)))
      (is (str/includes? err-text "diagnostic line"))
      (is (= 2 (count out-lines))))))

(deftest rpc-start-runtime-redirects-bootstrap-stdout-to-stderr-test
  (testing "rpc runtime startup keeps bootstrap stdout off the protocol stream"
    (let [out            (java.io.StringWriter.)
          err            (java.io.StringWriter.)
          session-state* (atom nil)]
      (binding [*in*  (java.io.StringReader. "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n")
                *out* out
                *err* err]
        (rpc/start-runtime!
         {:model-key :claude-sonnet
          :memory-runtime-opts {}
          :session-config {}
          :rpc-trace-file nil
          :session-state* session-state*
          :nrepl-runtime (atom nil)
          :resolve-model (fn [_]
                           {:provider :anthropic :id "stub" :name "Stub" :supports-reasoning true})
          :session-ctx-factory (fn [_ai-model _session-config]
                                 (println "BOOTSTRAP BANNER")
                                 (let [ctx (session/create-context {})
                                       sd  (session/new-session-in! ctx nil {})]
                                   {:ctx ctx :oauth-ctx nil :session-id (:session-id sd)}))
          :bootstrap-fn! (fn [_ctx _session-id _ai-model _memory-runtime-opts]
                           (println "BOOTSTRAP TRANSCRIPT")
                           nil)
          :on-new-session! (fn [_source-session-id]
                             {:messages [] :tool-calls {} :tool-order []})}))
      (let [out-lines (->> (str/split-lines (str out)) (remove str/blank?) vec)
            frames    (parse-frames out-lines)]
        (is (not (str/includes? (str out) "BOOTSTRAP BANNER")))
        (is (not (str/includes? (str out) "BOOTSTRAP TRANSCRIPT")))
        (is (str/includes? (str err) "BOOTSTRAP BANNER"))
        (is (str/includes? (str err) "BOOTSTRAP TRANSCRIPT"))
        (is (= 1 (count frames)))
        (is (= :response (:kind (first frames))))
        (is (= "handshake" (:op (first frames))))))))

(deftest run-stdio-loop-trace-fn-captures-inbound-and-outbound-test
  (let [traces  (atom [])
        input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"p1\" :kind :request :op \"ping\"}\n")
        out     (java.io.StringWriter.)
        err     (java.io.StringWriter.)
        handler (fn [request _emit _state]
                  (rpc.transport/response-frame (:id request) (:op request) true {:pong true}))]
    (rpc/run-stdio-loop! {:in (java.io.StringReader. input)
                          :out out
                          :err err
                          :state (atom {})
                          :request-handler handler
                          :trace-fn #(swap! traces conj %)})
    (let [in-frames  (filter #(= :in (:dir %)) @traces)
          out-frames (filter #(= :out (:dir %)) @traces)]
      (is (= 2 (count in-frames)))
      (is (every? map? (map :frame in-frames)))
      (is (= [:request :request]
             (mapv #(get-in % [:frame :kind]) in-frames)))
      (is (every? string? (map :raw in-frames)))
      (is (= 2 (count out-frames)))
      (is (= [:response :response]
             (mapv #(get-in % [:frame :kind]) out-frames)))
      (is (every? string? (map :raw out-frames))))))

(deftest run-stdio-loop-enforces-handshake-gate-test
  (testing "non-handshake requests are rejected before ready"
    (let [{:keys [out-lines]}
          (run-loop "{:id \"1\" :kind :request :op \"ping\"}\n"
                    (fn [_ _ _]
                      (rpc.transport/response-frame "1" "ping" true {:pong true})))
          frame (-> out-lines parse-frames first)]
      (is (= :error (:kind frame)))
      (is (= "transport/not-ready" (:error-code frame)))
      (is (= "1" (:id frame)))
      (is (= "ping" (:op frame))))))

(deftest run-stdio-loop-handshake-compatibility-test
  (testing "unsupported major protocol is rejected and transport remains not-ready"
    (let [{:keys [out-lines]}
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"2.0\"}}}\n"
                         "{:id \"p1\" :kind :request :op \"ping\"}\n")
                    (fn [_ _ _]
                      (rpc.transport/response-frame "p1" "ping" true {:pong true})))
          [h p] (parse-frames out-lines)]
      (is (= :error (:kind h)))
      (is (= "protocol/unsupported-version" (:error-code h)))
      (is (= :error (:kind p)))
      (is (= "transport/not-ready" (:error-code p)))))

  (testing "supported major protocol sets ready and allows non-handshake ops"
    (let [{:keys [out-lines]}
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"p1\" :kind :request :op \"ping\"}\n")
                    (fn [request _ _]
                      (rpc.transport/response-frame (:id request) (:op request) true {:pong true})))
          [h p] (parse-frames out-lines)]
      (is (= :response (:kind h)))
      (is (= "handshake" (:op h)))
      (is (= :response (:kind p)))
      (is (= "ping" (:op p))))))

(deftest run-stdio-loop-pending-lifecycle-test
  (testing "accepted request adds pending and terminal response clears it"
    (let [state (atom {:max-pending-requests 2})]
      (run-loop (str "{:id \"h\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"r1\" :kind :request :op \"echo\"}\n")
                (fn [request _emit _state]
                  (if (= "echo" (:op request))
                    (rpc.transport/response-frame (:id request) "echo" true {:ok true})
                    (rpc.transport/response-frame (:id request) (:op request) true {})))
                state)
      (is (= {} (:pending @state)))
      (is (= true (:ready? @state)))))

  (testing "max pending guard returns canonical error"
    (let [state (atom {:max-pending-requests 1 :ready? true :pending {"existing" "op"}})
          {:keys [out-lines]} (run-loop "{:id \"r2\" :kind :request :op \"echo\"}\n"
                                        (fn [_ _ _] nil)
                                        state)
          frame (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "transport/max-pending-exceeded" (:error-code frame)))
      (is (= "r2" (:id frame)))))

  (testing "duplicate request id is rejected with request/invalid-id"
    (let [state (atom {:ready? true :pending {"dup" "existing-op"}})
          {:keys [out-lines]} (run-loop "{:id \"dup\" :kind :request :op \"echo\"}\n"
                                        (fn [_ _ _] nil)
                                        state)
          frame (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "request/invalid-id" (:error-code frame)))
      (is (= "dup" (:id frame))))))

(deftest session-request-handler-query-eql-and-op-mapping-test
  (testing "query_eql routes to session/query-in and returns canonical result envelope"
    (let [[ctx _] (create-session-context)
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"q1\" :kind :request :op \"query_eql\" :params {:query \"[:psi.graph/domain-coverage :psi.memory/status]\"}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :response (:kind frame)))
      (is (= "query_eql" (:op frame)))
      (is (= true (:ok frame)))
      (is (map? (get-in frame [:data :result])))
      (is (contains? (get-in frame [:data :result]) :psi.graph/domain-coverage))
      (is (contains? (get-in frame [:data :result]) :psi.memory/status))))

  (testing "query_eql invalid query EDN returns request/invalid-query"
    (let [[ctx _] (create-session-context)
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"q2\" :kind :request :op \"query_eql\" :params {:query \"not-edn\"}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "query_eql" (:op frame)))
      (is (= "request/invalid-query" (:error-code frame)))))

  (testing "query_eql non-vector query returns request/invalid-query"
    (let [[ctx _] (create-session-context)
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"q3\" :kind :request :op \"query_eql\" :params {:query \"{:a 1}\"}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "request/invalid-query" (:error-code frame)))))

  (testing "unknown op returns request/op-not-supported with supported ops"
    (let [[ctx _] (create-session-context)
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"u1\" :kind :request :op \"nope\"}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)
          supported (get-in frame [:data :supported-ops])]
      (is (= :error (:kind frame)))
      (is (= "request/op-not-supported" (:error-code frame)))
      (is (vector? supported))
      (is (some #(= "prompt_while_streaming" %) supported))
      (is (some #(= "resolve_dialog" %) supported))
      (is (some #(= "cancel_dialog" %) supported))
      (is (some #(= "list_background_jobs" %) supported))
      (is (some #(= "inspect_background_job" %) supported))
      (is (some #(= "cancel_background_job" %) supported))))

  (testing "subscribe/unsubscribe update shared state and return subscribed topics"
    (let [[ctx _] (create-session-context)
          state   (atom {:ready? true :pending {} :subscribed-topics #{}})
          handler (make-handler ctx state)
          {:keys [out-lines state]}
          (run-loop (str "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"tool/start\"]}}\n"
                         "{:id \"s2\" :kind :request :op \"unsubscribe\" :params {:topics [\"tool/start\"]}}\n")
                    handler
                    state)
          [f1 f2] (parse-frames out-lines)]
      (is (= :response (:kind f1)))
      (is (= ["assistant/delta" "tool/start"] (get-in f1 [:data :subscribed])))
      (is (= :response (:kind f2)))
      (is (= ["assistant/delta"] (get-in f2 [:data :subscribed])))
      (is (= #{"assistant/delta"} (:subscribed-topics state)))))

  (testing "background job list/inspect/cancel ops route through session job store"
    (let [[ctx thread-id] (create-session-context)
          _         (dispatch/dispatch! ctx :session/update-background-jobs-state
                                        {:update-fn (fn [store]
                                                      (:state (bg-jobs/start-background-job
                                                               store
                                                               {:tool-call-id "tc-rpc-bg-1"
                                                                :thread-id thread-id
                                                                :tool-name "agent-chain"
                                                                :job-id "job-rpc-1"})))}
                                        {:origin :core})
          state     (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop (str "{:id \"jb1\" :kind :request :op \"list_background_jobs\"}\n"
                         "{:id \"jb2\" :kind :request :op \"inspect_background_job\" :params {:job-id \"job-rpc-1\"}}\n"
                         "{:id \"jb3\" :kind :request :op \"cancel_background_job\" :params {:job-id \"job-rpc-1\"}}\n")
                    handler
                    state)
          [f1 f2 f3] (parse-frames out-lines)]
      (is (= :response (:kind f1)))
      (is (= "list_background_jobs" (:op f1)))
      (is (= "job-rpc-1" (get-in f1 [:data :jobs 0 :job-id])))
      (is (= "job-rpc-1  [running]  agent-chain"
             (get-in f1 [:data :jobs 0 :summary :list-line])))

      (is (= :response (:kind f2)))
      (is (= "inspect_background_job" (:op f2)))
      (is (= "job-rpc-1" (get-in f2 [:data :job :job-id])))
      (is (= "running" (get-in f2 [:data :job :summary :status-label])))

      (is (= :response (:kind f3)))
      (is (= "cancel_background_job" (:op f3)))
      (is (true? (get-in f3 [:data :accepted])))
      (is (= :pending-cancel (get-in f3 [:data :job :status])))
      (is (= "pending-cancel" (get-in f3 [:data :job :summary :status-label]))))))

(deftest progress-event-thinking-delta-maps-to-rpc-thinking-topic-test
  (let [{:keys [event data]}
        (rpc.events/progress-event->rpc-event {:event-kind :thinking-delta :text "plan"})]
    (is (= "assistant/thinking-delta" event))
    (is (= "plan" (:text data)))))

(deftest rpc-subscribe-ui-topics-emits-initial-widget-snapshot-test
  (testing "subscribe ui/widgets-updated emits current widget projection immediately"
    (let [[ctx _] (create-session-context)
          _       (dispatch/dispatch! ctx :session/ui-set-widget
                                      {:extension-id "ext.demo" :widget-id "w-1"
                                       :placement :above-editor :content ["hello widget"]}
                                      {:origin :test})
          state   (atom {:ready? true :pending {} :subscribed-topics #{}})
          handler (make-handler ctx state)
          {:keys [out-lines state]}
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"ui/widgets-updated\"]}}\n")
                    handler
                    state
                    100)
          frames      (parse-frames out-lines)
          widget-evt  (some #(when (= "ui/widgets-updated" (:event %)) %) frames)]
      (is (some? widget-evt))
      (is (= "w-1" (get-in widget-evt [:data :widgets 0 :widget-id])))
      (is (= ["hello widget"] (get-in widget-evt [:data :widgets 0 :content])))
      (when-let [f (:ui-watch-loop state)]
        (future-cancel f)))))

(deftest rpc-ui-snapshot-delegates-to-canonical-extension-ui-projection-test
  (let [snapshot (rpc.events/ui-snapshot
                  (first (create-session-context)))]
    (is (map? snapshot))))

(deftest rpc-subscribe-ui-topics-emits-canonical-widget-and-status-order-test
  (testing "subscribe emits backend-owned widget/status order from canonical projection"
    (let [[ctx _] (create-session-context)
          _       (dispatch/dispatch! ctx :session/ui-set-widget
                                      {:extension-id "ext.b" :widget-id "w-2"
                                       :placement :below-editor :content ["B2"]}
                                      {:origin :test})
          _       (dispatch/dispatch! ctx :session/ui-set-widget
                                      {:extension-id "ext.a" :widget-id "w-3"
                                       :placement :above-editor :content ["A3"]}
                                      {:origin :test})
          _       (dispatch/dispatch! ctx :session/ui-set-widget
                                      {:extension-id "ext.b" :widget-id "w-1"
                                       :placement :above-editor :content ["B1"]}
                                      {:origin :test})
          _       (dispatch/dispatch! ctx :session/ui-set-status
                                      {:extension-id "ext-z" :text "Zeta"}
                                      {:origin :test})
          _       (dispatch/dispatch! ctx :session/ui-set-status
                                      {:extension-id "ext-a" :text "Alpha"}
                                      {:origin :test})
          state   (atom {:ready? true :pending {} :subscribed-topics #{}})
          handler (make-handler ctx state)
          {:keys [out-lines state]}
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"ui/widgets-updated\" \"ui/status-updated\"]}}\n")
                    handler
                    state
                    100)
          frames      (parse-frames out-lines)
          widget-evt  (some #(when (= "ui/widgets-updated" (:event %)) %) frames)
          status-evt  (some #(when (= "ui/status-updated" (:event %)) %) frames)]
      (is (= [["ext.a" "w-3"]
              ["ext.b" "w-1"]
              ["ext.b" "w-2"]]
             (mapv (juxt :extension-id :widget-id)
                   (get-in widget-evt [:data :widgets]))))
      (is (= ["ext-a" "ext-z"]
             (mapv :extension-id (get-in status-evt [:data :statuses]))))
      (when-let [f (:ui-watch-loop state)]
        (future-cancel f)))))

(deftest rpc-ui-watch-loop-streams-widget-updates-without-prompt-test
  (testing "after subscribe, ui widget updates stream without a prompt request"
    (let [[ctx _]     (create-session-context)
          state       (atom {:ready? true :pending {} :subscribed-topics #{}})
          handler (make-handler ctx state)
          in-reader   (java.io.PipedReader.)
          in-writer   (java.io.PipedWriter. in-reader)
          out-writer  (java.io.StringWriter.)
          err-writer  (java.io.StringWriter.)
          write-line! (fn [line]
                        (.write in-writer (str line "\n"))
                        (.flush in-writer))
          loop-future (future
                        (rpc/run-stdio-loop! {:in              in-reader
                                              :out             out-writer
                                              :err             err-writer
                                              :state           state
                                              :request-handler handler}))]
      (try
        (write-line! "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}")
        (write-line! "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"ui/widgets-updated\"]}}")
        (Thread/sleep 100)

        (dispatch/dispatch! ctx :session/ui-set-widget
                            {:extension-id "ext.demo" :widget-id "w-2"
                             :placement :above-editor :content ["live update"]}
                            {:origin :test})
        (Thread/sleep 180)

        (.close in-writer)
        (deref loop-future 500 nil)

        (let [frames        (parse-frames (->> (str/split-lines (str out-writer))
                                               (remove str/blank?)
                                               vec))
              widget-events (filter #(= "ui/widgets-updated" (:event %)) frames)
              latest        (last widget-events)]
          (is (seq widget-events))
          (is (= "w-2" (get-in latest [:data :widgets 0 :widget-id])))
          (is (= ["live update"] (get-in latest [:data :widgets 0 :content]))))

        (finally
          (when-let [f (:ui-watch-loop @state)]
            (future-cancel f))
          (future-cancel loop-future)
          (try (.close in-writer) (catch Exception _ nil))
          (try (.close in-reader) (catch Exception _ nil)))))))

(deftest rpc-external-message-event-streams-without-prompt-test
  (testing "after subscribe, external-message queue events emit assistant/message"
    (let [event-queue (java.util.concurrent.LinkedBlockingQueue.)
          [ctx _]     (create-session-context {:event-queue event-queue})
          state       (atom {:ready? true :pending {} :subscribed-topics #{}})
          handler (make-handler ctx state)
          in-reader   (java.io.PipedReader.)
          in-writer   (java.io.PipedWriter. in-reader)
          out-writer  (java.io.StringWriter.)
          err-writer  (java.io.StringWriter.)
          write-line! (fn [line]
                        (.write in-writer (str line "\n"))
                        (.flush in-writer))
          loop-future (future
                        (rpc/run-stdio-loop! {:in              in-reader
                                              :out             out-writer
                                              :err             err-writer
                                              :state           state
                                              :request-handler handler}))]
      (try
        (write-line! "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}")
        (write-line! "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\" \"session/updated\" \"footer/updated\"]}}")
        (Thread/sleep 100)

        (.offer ^java.util.concurrent.LinkedBlockingQueue event-queue
                {:type    :external-message
                 :message {:role "assistant"
                           :content {:kind :structured
                                     :blocks [{:kind :text :text "Agent result ready"}]}
                           :custom-type "agent-result"}})
        (Thread/sleep 180)

        (.close in-writer)
        (deref loop-future 500 nil)

        (let [frames        (parse-frames (->> (str/split-lines (str out-writer))
                                               (remove str/blank?)
                                               vec))
              events        (filter #(= :event (:kind %)) frames)
              topics        (set (map :event events))
              assistant-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (some? assistant-evt))
          (is (= "assistant" (get-in assistant-evt [:data :role])))
          (is (= "Agent result ready"
                 (get-in assistant-evt [:data :text])))
          (is (= "Agent result ready"
                 (get-in assistant-evt [:data :content :blocks 0 :text])))
          (is (= "agent-result" (get-in assistant-evt [:data :custom-type])))
          (is (contains? topics "session/updated"))
          (is (contains? topics "footer/updated")))

        (finally
          (when-let [f (:ui-watch-loop @state)]
            (future-cancel f))
          (when-let [f (:external-event-loop @state)]
            (future-cancel f))
          (future-cancel loop-future)
          (try (.close in-writer) (catch Exception _ nil))
          (try (.close in-reader) (catch Exception _ nil)))))))

(deftest session-request-handler-prompt-while-streaming-op-test
  (testing "behavior steer routes to session/steer-in!"
    (let [[ctx _]  (create-session-context)
          state    (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          steers   (atom [])
          follows  (atom [])]
      (with-redefs [session/steer-in! (fn
                                        ([_ text] (swap! steers conj text))
                                        ([_ _ text] (swap! steers conj text)))
                    session/follow-up-in! (fn
                                            ([_ text] (swap! follows conj text))
                                            ([_ _ text] (swap! follows conj text)))]
        (let [{:keys [out-lines]}
              (run-loop "{:id \"ps1\" :kind :request :op \"prompt_while_streaming\" :params {:message \"hello\" :behavior \"steer\"}}\n"
                        handler
                        state)
              frame (-> out-lines first edn/read-string)]
          (is (= :response (:kind frame)))
          (is (= "prompt_while_streaming" (:op frame)))
          (is (= true (:ok frame)))
          (is (= {:accepted true :behavior "steer"} (:data frame)))
          (is (= ["hello"] @steers))
          (is (empty? @follows))))))

  (testing "behavior queue routes to session/follow-up-in!"
    (let [[ctx _]  (create-session-context)
          state    (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          steers   (atom [])
          follows  (atom [])]
      (with-redefs [session/steer-in! (fn
                                        ([_ text] (swap! steers conj text))
                                        ([_ _ text] (swap! steers conj text)))
                    session/follow-up-in! (fn
                                            ([_ text] (swap! follows conj text))
                                            ([_ _ text] (swap! follows conj text)))]
        (let [{:keys [out-lines]}
              (run-loop "{:id \"ps2\" :kind :request :op \"prompt_while_streaming\" :params {:message \"next\" :behavior \"queue\"}}\n"
                        handler
                        state)
              frame (-> out-lines first edn/read-string)]
          (is (= :response (:kind frame)))
          (is (= "prompt_while_streaming" (:op frame)))
          (is (= true (:ok frame)))
          (is (= {:accepted true :behavior "queue"} (:data frame)))
          (is (empty? @steers))
          (is (= ["next"] @follows))))))

  (testing "missing behavior defaults to steer"
    (let [[ctx _]  (create-session-context)
          state    (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          steers   (atom [])
          follows  (atom [])]
      (with-redefs [session/steer-in! (fn
                                        ([_ text] (swap! steers conj text))
                                        ([_ _ text] (swap! steers conj text)))
                    session/follow-up-in! (fn
                                            ([_ text] (swap! follows conj text))
                                            ([_ _ text] (swap! follows conj text)))]
        (let [{:keys [out-lines]}
              (run-loop "{:id \"ps3\" :kind :request :op \"prompt_while_streaming\" :params {:message \"default\"}}\n"
                        handler
                        state)
              frame (-> out-lines first edn/read-string)]
          (is (= :response (:kind frame)))
          (is (= true (:ok frame)))
          (is (= {:accepted true :behavior "steer"} (:data frame)))
          (is (= ["default"] @steers))
          (is (empty? @follows))))))

  (testing "invalid behavior returns request/invalid-params"
    (let [[ctx _] (create-session-context)
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"ps4\" :kind :request :op \"prompt_while_streaming\" :params {:message \"x\" :behavior \"bad\"}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "prompt_while_streaming" (:op frame)))
      (is (= "request/invalid-params" (:error-code frame))))))

(deftest rpc-login-ops-test
  (testing "login_begin/login_complete use shared oauth context and persist credentials"
    (let [oauth-ctx (oauth/create-null-context)
          [ctx _]  (create-session-context {:oauth-ctx oauth-ctx})
          state    (atom {:ready? true
                          :pending {}
                          :rpc-ai-model {:provider :anthropic :id "stub" :supports-reasoning true}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop (str "{:id \"b1\" :kind :request :op \"login_begin\"}\n"
                         "{:id \"c1\" :kind :request :op \"login_complete\" :params {:input \"auth-code-123\"}}\n")
                    handler
                    state)
          [begin-frame complete-frame] (parse-frames out-lines)]
      (is (= :response (:kind begin-frame)))
      (is (= "login_begin" (:op begin-frame)))
      (is (= true (:ok begin-frame)))
      (is (= "anthropic" (get-in begin-frame [:data :provider :id])))
      (is (string? (get-in begin-frame [:data :url])))
      (is (= true (get-in begin-frame [:data :pending-login])))

      (is (= :response (:kind complete-frame)))
      (is (= "login_complete" (:op complete-frame)))
      (is (= true (:ok complete-frame)))
      (is (= "anthropic" (get-in complete-frame [:data :provider :id])))
      (is (= true (get-in complete-frame [:data :logged-in])))
      (is (oauth/has-auth? oauth-ctx :anthropic))
      (let [oauth-state (sa/oauth-projection-in ctx)]
        (is (= "anthropic" (:last-login-provider oauth-state)))
        (is (nil? (:pending-login oauth-state)))
        (is (instance? java.time.Instant (:last-login-at oauth-state))))))

  (testing "login_begin supports explicit provider override"
    (let [[ctx _] (create-session-context {:oauth-ctx (oauth/create-null-context)})
          state   (atom {:ready? true
                         :pending {}
                         :rpc-ai-model {:provider :anthropic :id "stub" :supports-reasoning true}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"b2\" :kind :request :op \"login_begin\" :params {:provider \"openai\"}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :response (:kind frame)))
      (is (= "login_begin" (:op frame)))
      (is (= "openai" (get-in frame [:data :provider :id])))
      (is (= :openai (get-in (sa/oauth-projection-in ctx) [:pending-login :provider-id])))))

  (testing "login_complete without pending login returns deterministic error"
    (let [[ctx _] (create-session-context {:oauth-ctx (oauth/create-null-context)})
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"c2\" :kind :request :op \"login_complete\" :params {:input \"x\"}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "login_complete" (:op frame)))
      (is (= "request/no-pending-login" (:error-code frame)))))

  (testing "login_begin validates provider param type"
    (let [[ctx _] (create-session-context {:oauth-ctx (oauth/create-null-context)})
          state   (atom {:ready? true
                         :pending {}
                         :rpc-ai-model {:provider :anthropic :id "stub" :supports-reasoning true}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"b3\" :kind :request :op \"login_begin\" :params {:provider 42}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "login_begin" (:op frame)))
      (is (= "request/invalid-params" (:error-code frame)))))

  (testing "login_begin requires provider when no session/rpc model is configured"
    (let [[ctx _] (create-session-context {:oauth-ctx (oauth/create-null-context)})
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"b4\" :kind :request :op \"login_begin\"}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "login_begin" (:op frame)))
      (is (= "request/invalid-params" (:error-code frame))))))

(deftest rpc-handshake-server-info-test
  (testing "handshake emits server-info with protocol/session metadata"
    (let [[ctx sid] (create-session-context)
          out     (java.io.StringWriter.)
          err     (java.io.StringWriter.)
          state   (atom {:focus-session-id sid
                         :subscribed-topics #{"context/updated"}})
          handler (make-handler ctx state)
          _       (rpc/run-stdio-loop! {:in (java.io.StringReader. "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n")
                                        :out out
                                        :err err
                                        :state state
                                        :request-handler handler
                                        :handshake-server-info-fn (fn [_state] (rpc.events/session->handshake-server-info ctx sid))})
          frame   (->> (str/split-lines (str out))
                       (remove str/blank?)
                       parse-frames
                       first)
          info    (get-in frame [:data :server-info])]
      (is (= :response (:kind frame)))
      (is (= "handshake" (:op frame)))
      (is (= "1.0" (:protocol-version info)))
      (is (contains? info :session-id))
      (is (= ["eql-graph" "eql-memory"] (:features info)))))

  (testing "handshake includes runtime ui-type when provided by bootstrap/runtime state"
    (let [[ctx sid] (create-session-context)
          out     (java.io.StringWriter.)
          err     (java.io.StringWriter.)
          state   (atom {:focus-session-id sid
                         :subscribed-topics #{"context/updated"}})
          handler (make-handler ctx state)
          _       (rpc/run-stdio-loop! {:in (java.io.StringReader. "{:id \"h2\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n")
                                        :out out
                                        :err err
                                        :state state
                                        :request-handler handler
                                        :handshake-server-info-fn (fn [_state] (assoc (rpc.events/session->handshake-server-info ctx sid)
                                                                                      :ui-type :emacs))})
          frame   (->> (str/split-lines (str out))
                       (remove str/blank?)
                       parse-frames
                       first)
          info    (get-in frame [:data :server-info])]
      (is (= :response (:kind frame)))
      (is (= "handshake" (:op frame)))
      (is (= :emacs (:ui-type info))))))

(deftest rpc-dialog-response-ops-test
  (testing "resolve_dialog succeeds with active dialog and matching id"
    (let [[ctx _] (create-session-context)
          _       (enqueue-active-dialog! ctx {:id "d1" :kind :confirm :title "Confirm" :promise (promise)})
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"r1\" :kind :request :op \"resolve_dialog\" :params {:dialog-id \"d1\" :result true}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :response (:kind frame)))
      (is (= "resolve_dialog" (:op frame)))
      (is (= true (:ok frame)))
      (is (= {:accepted true} (:data frame)))
      (is (nil? (active-dialog-in ctx)))))

  (testing "cancel_dialog succeeds with active dialog and matching id"
    (let [[ctx _] (create-session-context)
          _       (enqueue-active-dialog! ctx {:id "d2" :kind :input :title "Input" :promise (promise)})
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"c1\" :kind :request :op \"cancel_dialog\" :params {:dialog-id \"d2\"}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :response (:kind frame)))
      (is (= "cancel_dialog" (:op frame)))
      (is (= true (:ok frame)))
      (is (= {:accepted true} (:data frame)))
      (is (nil? (active-dialog-in ctx)))))

  (testing "resolve_dialog invalid params are deterministic"
    (let [[ctx _] (create-session-context)
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"r2\" :kind :request :op \"resolve_dialog\" :params {:dialog-id \"d1\" :result 42}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "resolve_dialog" (:op frame)))
      (is (= "request/invalid-params" (:error-code frame)))))

  (testing "resolve_dialog no active dialog returns deterministic error"
    (let [[ctx _] (create-session-context)
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"r3\" :kind :request :op \"resolve_dialog\" :params {:dialog-id \"d1\" :result true}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "resolve_dialog" (:op frame)))
      (is (= "request/no-active-dialog" (:error-code frame)))))

  (testing "cancel_dialog no active dialog returns deterministic error"
    (let [[ctx _] (create-session-context)
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"c2\" :kind :request :op \"cancel_dialog\" :params {:dialog-id \"d1\"}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "cancel_dialog" (:op frame)))
      (is (= "request/no-active-dialog" (:error-code frame)))))

  (testing "dialog-id mismatch returns deterministic error"
    (let [[ctx _] (create-session-context)
          _       (enqueue-active-dialog! ctx {:id "d-real" :kind :confirm :title "Confirm" :promise (promise)})
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"r4\" :kind :request :op \"resolve_dialog\" :params {:dialog-id \"d-wrong\" :result true}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "resolve_dialog" (:op frame)))
      (is (= "request/dialog-id-mismatch" (:error-code frame))))))

(deftest rpc-prompt-streams-events-and-interleaves-test
  (testing "prompt emits canonical events that interleave with accepted response"
    (let [[ctx _] (create-session-context)
          _   (dispatch/dispatch! ctx :session/ui-set-status {:extension-id "ext.demo" :text "ready"} {:origin :test})
          state (atom {:ready? true
                       :pending {}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :run-agent-loop-fn (fn [_ai-ctx _ctx _session-id _agent-ctx _ai-model _new-messages {:keys [progress-queue]}]
                                            (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                    {:event-kind :text-delta :text "Hello" :type :agent-event})
                                            (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                    {:event-kind :thinking-delta :text "thinking..." :type :agent-event})
                                            (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                    {:event-kind :tool-start :tool-id "tc-1" :tool-name "read" :type :agent-event})
                                            (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                    {:event-kind :tool-result
                                                     :tool-id "tc-1"
                                                     :tool-name "read"
                                                     :content [{:type :text :text "done"}]
                                                     :result-text "done"
                                                     :details nil
                                                     :is-error false
                                                     :type :agent-event})
                                            {:role "assistant"
                                             :content [{:type :text :text "Hello final"}]
                                             :stop-reason :stop
                                             :usage {:total-tokens 3}})})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"p1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"assistant/thinking-delta\" \"assistant/message\" \"tool/start\" \"tool/result\" \"session/updated\" \"footer/updated\"]}}\n"
                       "{:id \"r1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          {:keys [out-lines]} (run-loop input handler state 250)
          frames (->> out-lines
                      (keep (fn [line]
                              (try
                                (edn/read-string line)
                                (catch Throwable _ nil))))
                      vec)
          response-index (first (keep-indexed (fn [i f] (when (and (= :response (:kind f)) (= "prompt" (:op f))) i)) frames))
          event-indexes  (keep-indexed (fn [i f] (when (= :event (:kind f)) i)) frames)
          seqs           (->> frames (filter #(= :event (:kind %))) (map :seq) (remove nil?))
          topics         (->> frames (filter #(= :event (:kind %))) (map :event) set)]
      (is (number? response-index))
      (is (seq event-indexes))
      (is (some #(< response-index %) event-indexes))
      (is (contains? topics "assistant/delta"))
      (is (contains? topics "assistant/thinking-delta"))
      (is (contains? topics "assistant/message"))
      (is (contains? topics "tool/start"))
      (is (contains? topics "tool/result"))
      (is (contains? topics "session/updated"))
      (is (contains? topics "footer/updated"))
      (is (= seqs (sort seqs)))
      (is (every? #(contains? % :data) (filter #(= :event (:kind %)) frames)))
      (is (contains? #{:response :event} (:kind (last frames)))))))

(deftest rpc-prompt-footer-updated-tolerates-keyword-sentinel-values-test
  (testing "prompt completion does not fail when footer query returns keyword sentinels"
    (let [[ctx _] (create-session-context)
          state (atom {:ready? true
                       :pending {}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :run-agent-loop-fn (fn [_ai-ctx _ctx _session-id _agent-ctx _ai-model _new-messages {:keys [progress-queue]}]
                                            (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                    {:event-kind :text-delta :text "Hello" :type :agent-event})
                                            {:role "assistant"
                                             :content [{:type :text :text "Hello final"}]
                                             :stop-reason :stop
                                             :usage {:total-tokens 3}})})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"p1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"assistant/message\" \"session/updated\" \"footer/updated\" \"error\"]}}\n"
                       "{:id \"r1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          footer-data {:psi.agent-session/cwd "/repo/project"
                       :psi.agent-session/git-branch :pathom/unknown
                       :psi.agent-session/session-display-name :pathom/unknown
                       :psi.agent-session/context-window 400000
                       :psi.agent-session/model-provider :pathom/unknown
                       :psi.agent-session/model-id "stub"
                       :psi.agent-session/model-reasoning false
                       :psi.agent-session/thinking-level :off
                       :psi.ui/statuses :pathom/unknown}
          orig-query-in session/query-in
          {:keys [out-lines]}
          (with-redefs [session/query-in
                        (fn
                          ([ctx q]
                           (if (= @#'rpc.events/footer-query q)
                             footer-data
                             (orig-query-in ctx q)))
                          ([ctx x y]
                           (if (or (= @#'rpc.events/footer-query x)
                                   (= @#'rpc.events/footer-query y))
                             footer-data
                             (orig-query-in ctx x y)))
                          ([ctx session-id q extra-entity]
                           (if (= @#'rpc.events/footer-query q)
                             footer-data
                             (orig-query-in ctx session-id q extra-entity))))]
            (run-loop input handler state 250))
          frames         (parse-frames out-lines)
          prompt-frame   (some #(when (and (= :response (:kind %))
                                           (= "prompt" (:op %))) %) frames)
          assistant-evt  (some #(when (= "assistant/message" (:event %)) %) frames)
          footer-events  (filterv #(= "footer/updated" (:event %)) frames)
          runtime-failed (filterv #(= "runtime/failed"
                                      (or (:error-code %)
                                          (get-in % [:data :error-code])))
                                  frames)]
      (is (some? prompt-frame))
      (is (true? (get-in prompt-frame [:data :accepted])))
      (is (some? assistant-evt))
      (is (seq footer-events))
      (is (= "/repo/project"
             (get-in (last footer-events) [:data :path-line])))
      (is (empty? runtime-failed)))))

(deftest rpc-thinking-delta-after-tool-start-begins-fresh-segment-test
  (testing "post-tool thinking delta can start a fresh cumulative segment"
    (let [[ctx _] (create-session-context)
          state (atom {:ready? true
                       :pending {}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :run-agent-loop-fn (fn [_ai-ctx _ctx _session-id _agent-ctx _ai-model _new-messages {:keys [progress-queue]}]
                                            (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                    {:event-kind :thinking-delta :text "plan-1" :type :agent-event})
                                            (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                    {:event-kind :tool-start :tool-id "tc-1" :tool-name "read" :type :agent-event})
                                            (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                    {:event-kind :thinking-delta :text "plan-2" :type :agent-event})
                                            {:role "assistant"
                                             :content [{:type :text :text "done"}]
                                             :stop-reason :stop
                                             :usage {:total-tokens 3}})})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"p1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/thinking-delta\" \"tool/start\" \"assistant/message\"]}}\n"
                       "{:id \"r1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          {:keys [out-lines]} (run-loop input handler state 250)
          frames (parse-frames out-lines)
          thinking-events (->> frames
                               (filter #(and (= :event (:kind %))
                                             (= "assistant/thinking-delta" (:event %)))))
          tool-start-index (first (keep-indexed (fn [i f]
                                                  (when (and (= :event (:kind f))
                                                             (= "tool/start" (:event f)))
                                                    i))
                                                frames))
          second-thinking-index (first (keep-indexed (fn [i f]
                                                       (when (and (= :event (:kind f))
                                                                  (= "assistant/thinking-delta" (:event f))
                                                                  (= "plan-2" (get-in f [:data :text])))
                                                         i))
                                                     frames))]
      (is (= ["plan-1" "plan-2"] (mapv #(get-in % [:data :text]) thinking-events)))
      (is (number? tool-start-index))
      (is (number? second-thinking-index))
      (is (< tool-start-index second-thinking-index)))))

(deftest rpc-openai-codex-prompt-emits-tool-events-with-final-args-test
  (testing "openai codex tool args from response.output_item.done flow through RPC tool events"
    (let [[ctx session-id]   (create-session-context)
          _         (dispatch/dispatch! ctx :session/set-active-tools {:session-id session-id :tool-maps [tools/bash-tool]} {:origin :core})
          state     (atom {:ready? true
                           :pending {}
                           :sync-on-git-head-change? false
                           :rpc-ai-model (ai-models/get-model :gpt-5.3-codex)})
          handler (make-handler ctx state)
          requests  (atom [])
          call-n    (atom 0)
          first-sse (str
                     "data: " (json/generate-string
                               {:type "response.output_item.added"
                                :output_index 0
                                :item {:type "function_call"
                                       :id "fc_1"
                                       :call_id "call_1"
                                       :name "bash"
                                       :arguments ""}}) "\n\n"
                     "data: " (json/generate-string
                               {:type "response.output_item.done"
                                :output_index 0
                                :item {:type "function_call"
                                       :id "fc_1"
                                       :call_id "call_1"
                                       :name "bash"
                                       :arguments "{\"command\":\"pwd\"}"}}) "\n\n"
                     "data: " (json/generate-string
                               {:type "response.completed"
                                :response {:status "completed"}}) "\n\n")
          second-sse (str
                      "data: " (json/generate-string
                                {:type "response.output_item.added"
                                 :item {:type "message"
                                        :id "msg_2"
                                        :role "assistant"
                                        :status "in_progress"
                                        :content []}}) "\n\n"
                      "data: " (json/generate-string
                                {:type "response.output_text.delta"
                                 :delta "Final response"}) "\n\n"
                      "data: " (json/generate-string
                                {:type "response.completed"
                                 :response {:status "completed"}}) "\n\n")
          input     (str
                     "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"tool/start\" \"tool/executing\" \"tool/result\" \"assistant/message\"]}}\n"
                     "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"run pwd\"}}\n")
          {:keys [out-lines]}
          (with-redefs [runtime/resolve-api-key-in (fn [_ctx _session-id _model] openai-chatgpt-test-token)
                        http/post (fn [url req]
                                    (swap! requests conj {:url url :req req})
                                    (let [n (swap! call-n inc)]
                                      {:body (stream-body (if (= 1 n) first-sse second-sse))}))]
            (run-loop input handler state 900))
          frames         (parse-frames out-lines)
          events         (filter #(= :event (:kind %)) frames)
          prompt-frame   (some #(when (and (= :response (:kind %))
                                           (= "prompt" (:op %))) %) frames)
          tool-start-evt (some #(when (= "tool/start" (:event %)) %) events)
          tool-exec-evt  (some #(when (= "tool/executing" (:event %)) %) events)
          tool-result-evt (some #(when (= "tool/result" (:event %)) %) events)
          assistant-evt  (some #(when (= "assistant/message" (:event %)) %) events)]
      (is (some? prompt-frame))
      (is (true? (get-in prompt-frame [:data :accepted])))

      (is (= 2 (count @requests)))
      (is (every? #(= "https://chatgpt.com/backend-api/codex/responses" (:url %)) @requests))
      (is (= (str "Bearer " openai-chatgpt-test-token)
             (get-in (first @requests) [:req :headers "Authorization"])))
      (is (= "acc_test"
             (get-in (first @requests) [:req :headers "chatgpt-account-id"])))
      (let [body (json/parse-string (get-in (first @requests) [:req :body]) true)]
        (is (= "gpt-5.3-codex" (:model body)))
        (is (= true (:stream body)))
        (is (= "bash" (get-in body [:tools 0 :name]))))

      (is (= "call_1|fc_1" (get-in tool-start-evt [:data :tool-id])))
      (is (= "bash" (get-in tool-start-evt [:data :tool-name])))
      (is (= {"command" "pwd"}
             (get-in tool-exec-evt [:data :parsed-args])))
      (is (false? (get-in tool-result-evt [:data :is-error])))
      (is (string? (get-in tool-result-evt [:data :result-text])))
      (is (not (str/blank? (get-in tool-result-evt [:data :result-text]))))
      (is (= "assistant" (get-in assistant-evt [:data :role])))
      (is (some #(= "Final response" (:text %))
                (get-in assistant-evt [:data :content]))))))

(deftest rpc-openai-chat-completions-tool-id-late-still-executes-test
  (testing "openai chat completions executes tool calls when streamed id arrives late"
    (let [[ctx session-id]   (create-session-context)
          _         (dispatch/dispatch! ctx :session/set-active-tools {:session-id session-id :tool-maps [tools/bash-tool]} {:origin :core})
          state     (atom {:ready? true
                           :pending {}
                           :sync-on-git-head-change? false
                           :rpc-ai-model (ai-models/get-model :gpt-5)})
          handler (make-handler ctx state)
          requests  (atom [])
          call-n    (atom 0)
          first-sse (str
                     "data: " (json/generate-string
                               {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                     "data: " (json/generate-string
                               {:choices [{:delta {:tool_calls [{:index 0
                                                                 :function {:name "bash"
                                                                            :arguments "{\"command\":\"pwd\"}"}}]}}]}) "\n\n"
                     "data: " (json/generate-string
                               {:choices [{:delta {:tool_calls [{:index 0
                                                                 :id "call_late"
                                                                 :function {:name "bash"}}]}}]}) "\n\n"
                     "data: " (json/generate-string
                               {:choices [{:finish_reason "tool_calls"}]
                                :usage {:prompt_tokens 2
                                        :completion_tokens 2
                                        :total_tokens 4}}) "\n\n")
          second-sse (str
                      "data: " (json/generate-string
                                {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                      "data: " (json/generate-string
                                {:choices [{:delta {:content "Final response"}}]}) "\n\n"
                      "data: " (json/generate-string
                                {:choices [{:finish_reason "stop"}]
                                 :usage {:prompt_tokens 2
                                         :completion_tokens 2
                                         :total_tokens 4}}) "\n\n")
          input     (str
                     "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"tool/start\" \"tool/executing\" \"tool/result\" \"assistant/message\"]}}\n"
                     "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"run pwd\"}}\n")
          {:keys [out-lines]}
          (with-redefs [runtime/resolve-api-key-in (fn [_ctx _session-id _model] "sk-test")
                        http/post (fn [url req]
                                    (swap! requests conj {:url url :req req})
                                    (let [n (swap! call-n inc)]
                                      {:body (stream-body (if (= 1 n) first-sse second-sse))}))]
            (run-loop input handler state 900))
          frames          (parse-frames out-lines)
          events          (filter #(= :event (:kind %)) frames)
          prompt-frame    (some #(when (and (= :response (:kind %))
                                            (= "prompt" (:op %))) %) frames)
          tool-start-evt  (some #(when (= "tool/start" (:event %)) %) events)
          tool-exec-evt   (some #(when (= "tool/executing" (:event %)) %) events)
          tool-result-evt (some #(when (= "tool/result" (:event %)) %) events)
          assistant-evt   (some #(when (= "assistant/message" (:event %)) %) events)]
      (is (some? prompt-frame))
      (is (true? (get-in prompt-frame [:data :accepted])))

      (is (= 2 (count @requests)))
      (is (= "https://api.openai.com/v1/chat/completions"
             (:url (first @requests))))
      (let [body (json/parse-string (get-in (first @requests) [:req :body]) true)]
        (is (= "gpt-5" (:model body)))
        (is (= true (:stream body)))
        (is (= "bash" (get-in body [:tools 0 :function :name]))))

      (is (= "call_late" (get-in tool-start-evt [:data :tool-id])))
      (is (= "bash" (get-in tool-start-evt [:data :tool-name])))
      (is (= {"command" "pwd"}
             (get-in tool-exec-evt [:data :parsed-args])))
      (is (false? (get-in tool-result-evt [:data :is-error])))
      (is (= "assistant" (get-in assistant-evt [:data :role])))
      (is (some #(= "Final response" (:text %))
                (get-in assistant-evt [:data :content]))))))

(deftest rpc-openai-chat-completions-cumulative-args-executes-once-test
  (testing "openai chat completions cumulative tool args execute with full parsed payload"
    (let [[ctx session-id]   (create-session-context)
          _         (dispatch/dispatch! ctx :session/set-active-tools {:session-id session-id :tool-maps [tools/bash-tool]} {:origin :core})
          state     (atom {:ready? true
                           :pending {}
                           :sync-on-git-head-change? false
                           :rpc-ai-model (ai-models/get-model :gpt-5)})
          handler (make-handler ctx state)
          call-n    (atom 0)
          first-sse (str
                     "data: " (json/generate-string
                               {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                     "data: " (json/generate-string
                               {:choices [{:delta {:tool_calls [{:index 0
                                                                 :id "call_1"
                                                                 :function {:name "bash"
                                                                            :arguments "{\"command\""}}]}}]}) "\n\n"
                     "data: " (json/generate-string
                               {:choices [{:delta {:tool_calls [{:index 0
                                                                 :function {:arguments "{\"command\":\"pwd\"}"}}]}}]}) "\n\n"
                     "data: " (json/generate-string
                               {:choices [{:finish_reason "tool_calls"}]
                                :usage {:prompt_tokens 2
                                        :completion_tokens 2
                                        :total_tokens 4}}) "\n\n")
          second-sse (str
                      "data: " (json/generate-string
                                {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                      "data: " (json/generate-string
                                {:choices [{:delta {:content "Final response"}}]}) "\n\n"
                      "data: " (json/generate-string
                                {:choices [{:finish_reason "stop"}]
                                 :usage {:prompt_tokens 2
                                         :completion_tokens 2
                                         :total_tokens 4}}) "\n\n")
          input     (str
                     "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"tool/start\" \"tool/executing\" \"tool/result\" \"assistant/message\"]}}\n"
                     "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"run pwd\"}}\n")
          {:keys [out-lines]}
          (with-redefs [runtime/resolve-api-key-in (fn [_ctx _session-id _model] "sk-test")
                        http/post (fn [_url _req]
                                    (let [n (swap! call-n inc)]
                                      {:body (stream-body (if (= 1 n) first-sse second-sse))}))]
            (run-loop input handler state 900))
          frames          (parse-frames out-lines)
          events          (filter #(= :event (:kind %)) frames)
          tool-start-evts (filter #(= "tool/start" (:event %)) events)
          tool-exec-evt   (some #(when (= "tool/executing" (:event %)) %) events)
          assistant-evt   (some #(when (= "assistant/message" (:event %)) %) events)]
      (is (= 1 (count tool-start-evts)))
      (is (= {"command" "pwd"}
             (get-in tool-exec-evt [:data :parsed-args])))
      (is (= "assistant" (get-in assistant-evt [:data :role])))
      (is (some #(= "Final response" (:text %))
                (get-in assistant-evt [:data :content]))))))

(deftest rpc-session-resume-and-rehydrate-events-test
  (testing "new_session emits session/resumed and session/rehydrated canonical events"
    (let [[ctx session-id]    (create-session-context)
          state               (atom {:ready?            true
                                     :pending           {}
                                     :subscribed-topics #{"session/resumed" "session/rehydrated"}})
          handler             (make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"n1\" :kind :request :op \"new_session\" :params {:session-id \"" session-id "\"}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames              (parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          event-topics        (set (map :event events))]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (some #(contains? (:data %) :session-id) events))
      (is (some #(contains? (:data %) :messages) events))))

  (testing "command /new emits session/resumed and session/rehydrated canonical events"
    (let [[ctx _]             (create-session-context)
          state               (atom {:ready?            true
                                     :pending           {}
                                     :subscribed-topics #{"session/resumed" "session/rehydrated" "command-result"}})
          handler             (make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/new\"}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames              (parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          event-topics        (set (map :event events))
          command-result      (some #(when (= "command-result" (:event %)) %) events)]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (some? command-result))
      (is (= "new_session" (get-in command-result [:data :type])))))

  (testing "command /resume <path> emits session/resumed and session/rehydrated canonical events"
    (let [cwd                 (str (System/getProperty "java.io.tmpdir") "/psi-rpc-resume-" (java.util.UUID/randomUUID))
          _                   (.mkdirs (java.io.File. cwd))
          [ctx session-id]    (create-session-context {:cwd cwd})
          sd1                 (session/new-session-in! ctx nil {})
          session-id          (:session-id sd1)
          path1               (:session-file sd1)
          _                   (persist/flush-journal! (java.io.File. path1)
                                                      session-id
                                                      cwd
                                                      nil
                                                      nil
                                                      [(persist/message-entry {:role "user" :content "hi"})
                                                       (persist/message-entry {:role "assistant" :content [{:type :text :text "there"}]})])
          state               (atom {:ready?            true
                                     :pending           {}
                                     :subscribed-topics #{"session/resumed" "session/rehydrated" "command-result"}})
          handler             (make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/resume " path1 "\"}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames              (parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          event-topics        (set (map :event events))]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (= path1 (get-in (some #(when (= "session/resumed" (:event %)) %) events)
                           [:data :session-file])))
      (is (= [{:role "user" :content "hi"}
              {:role "assistant" :content [{:type :text :text "there"}]}]
             (get-in (some #(when (= "session/rehydrated" (:event %)) %) events)
                     [:data :messages])))))

  (testing "command /tree <session-id> emits session/resumed and session/rehydrated canonical events"
    (let [cwd                 (str (System/getProperty "java.io.tmpdir") "/psi-rpc-tree-" (java.util.UUID/randomUUID))
          _                   (.mkdirs (java.io.File. cwd))
          [ctx session-id]    (create-session-context {:cwd cwd})
          sd1                 (session/new-session-in! ctx nil {})
          sid1                (:session-id sd1)
          path1               (:session-file sd1)
          _                   (persist/flush-journal! (java.io.File. path1)
                                                      sid1
                                                      cwd
                                                      nil
                                                      nil
                                                      [(persist/message-entry {:role "assistant" :content [{:type :text :text "root"}]})])
          _                   (session/new-session-in! ctx sid1 {})
          state               (atom {:ready?            true
                                     :pending           {}
                                     :subscribed-topics #{"session/resumed" "session/rehydrated" "command-result"}})
          handler             (make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/tree " sid1 "\"}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames              (parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          event-topics        (set (map :event events))]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (= sid1 (get-in (some #(when (= "session/resumed" (:event %)) %) events)
                          [:data :session-id])))
      ;; Tree direct-switch emits canonical rehydrate event; exact message replay is
      ;; covered by the /resume regression tests.
      (is (vector? (get-in (some #(when (= "session/rehydrated" (:event %)) %) events)
                           [:data :messages])))))

  (testing "command /tree emits frontend selector payload with backend-owned order"
    (let [cwd                 (str (System/getProperty "java.io.tmpdir") "/psi-rpc-tree-picker-" (java.util.UUID/randomUUID))
          _                   (.mkdirs (java.io.File. cwd))
          [ctx session-id]    (create-session-context {:cwd cwd})
          child-sd            (session/new-session-in! ctx session-id {})
          child-id            (:session-id child-sd)
          _                   (ss/update-state-value-in! ctx
                                                         (ss/state-path :session-data child-id)
                                                         assoc
                                                         :parent-session-id session-id)
          _                   (session/set-session-name-in! ctx session-id "main")
          _                   (session/set-session-name-in! ctx child-id "child")
          entry               (persist/message-entry {:role    "user"
                                                      :content [{:type :text :text "Branch from this prompt"}]})
          _                   (ss/journal-append-in! ctx session-id entry)
          state               (atom {:ready?            true
                                     :pending           {}
                                     :focus-session-id  session-id
                                     :subscribed-topics #{"ui/frontend-action-requested"}})
          handler             (make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/tree\"}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames              (parse-frames out-lines)
          action-evt          (some #(when (= "ui/frontend-action-requested" (:event %)) %) frames)
          selector           (get-in action-evt [:data :ui/action])
          items              (mapv :ui.item/meta (:ui/items selector))
          fork-slot          (some #(when (= :fork-point (:item/kind %)) %) items)
          main-idx           (first (keep-indexed (fn [i item]
                                                    (when (and (= :session (:item/kind item))
                                                               (= session-id (:item/session-id item)))
                                                      i))
                                                  items))
          fork-idx           (first (keep-indexed (fn [i item]
                                                    (when (= (:id entry) (:item/entry-id item)) i))
                                                  items))]
      (is (some? action-evt))
      (is (= "select-session" (get-in action-evt [:data :action-name])))
      (is (= :select-session (:ui/action-name selector)))
      (is (vector? items))
      (is (some? fork-slot))
      (is (= (:id entry) (:item/entry-id fork-slot)))
      (is (= "Branch from this prompt" (:item/display-name fork-slot)))
      (is (some? main-idx))
      (is (some? fork-idx))
      (is (< main-idx fork-idx)
          "backend payload must place current-session fork points after their containing session")))

  (testing "switch_session and get_messages derive transcript from ctx journal when agent messages drift"
    (let [[ctx _]             (create-session-context {:persist? false})
          sd1                 (session/new-session-in! ctx nil {})
          sid1                (:session-id sd1)
          canonical-messages  [{:role "user" :content "who are you?"}
                               {:role "assistant" :content [{:type :text :text "I am psi."}]}]
          _                   (doseq [m canonical-messages]
                                (ss/journal-append-in! ctx sid1 (persist/message-entry m)))
          ;; Simulate the regression: the loaded session's canonical journal is
          ;; correct, but agent-core's cached transcript has drifted.
          _                   (agent/replace-messages-in! (ss/agent-ctx-in ctx sid1)
                                                          [{:role    "assistant"
                                                            :content [{:type :text :text "stale in-memory tail"}]}])
          state               (atom {:ready?            true
                                     :pending           {}
                                     :subscribed-topics #{"session/resumed" "session/rehydrated"}})
          handler             (make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"s1\" :kind :request :op \"switch_session\" :params {:session-id \"" sid1 "\"}}\n"
                                   "{:id \"g1\" :kind :request :op \"get_messages\" :params {:session-id \"" sid1 "\"}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames              (parse-frames out-lines)
          rehydrate-event     (some #(when (and (= :event (:kind %))
                                                (= "session/rehydrated" (:event %))) %)
                                    frames)
          get-messages-resp   (some #(when (and (= :response (:kind %))
                                                (= "get_messages" (:op %))) %)
                                    frames)]
      (is (= canonical-messages
             (get-in rehydrate-event [:data :messages])))
      (is (= canonical-messages
             (get-in get-messages-resp [:data :messages]))))))

(deftest rpc-multi-session-context-routing-test
  (testing "list_sessions returns context snapshot with active-session-id"
    (let [cwd                (str (System/getProperty "java.io.tmpdir") "/psi-rpc-context-" (java.util.UUID/randomUUID))
          _                  (.mkdirs (java.io.File. cwd))
          [ctx _] (create-session-context {:cwd cwd})
          state              (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          input   (str "{:id \"n1\" :kind :request :op \"new_session\"}\n"
                       "{:id \"l1\" :kind :request :op \"list_sessions\"}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames  (parse-frames out-lines)
          new-resp  (some #(when (and (= :response (:kind %))
                                      (= "new_session" (:op %))) %)
                          frames)
          list-resp (some #(when (and (= :response (:kind %))
                                      (= "list_sessions" (:op %))) %)
                          frames)
          sid1    (get-in new-resp [:data :session-id])
          sessions (get-in list-resp [:data :sessions])]
      (is (string? sid1))
      (is (= sid1 (get-in list-resp [:data :active-session-id])))
      ;; Runtime context index tracks real registered sessions only; the pre-new seed
      ;; session is not retained once the first real session is created.
      (is (some #(= sid1 (:session-id %)) sessions))))

  (testing "switch_session accepts :session-id and restores that runtime session"
    (let [cwd                (str (System/getProperty "java.io.tmpdir") "/psi-rpc-switch-" (java.util.UUID/randomUUID))
          _                  (.mkdirs (java.io.File. cwd))
          [ctx session-id] (create-session-context {:cwd cwd})
          sd1                (session/new-session-in! ctx nil {})
          sid1               (:session-id sd1)
          path1              (:session-file sd1)
          _                  (persist/flush-journal! (java.io.File. path1)
                                                     sid1
                                                     cwd
                                                     nil
                                                     nil
                                                     [(persist/thinking-level-entry :off)])
          sd2                (session/new-session-in! ctx sid1 {})
          sid2               (:session-id sd2)
          _                  (session/new-session-in! ctx sid2 {})
          state              (atom {:ready? true
                                    :pending {}
                                    :subscribed-topics #{"session/resumed"}})
          handler (make-handler ctx state)
          input   (str "{:id \"s1\" :kind :request :op \"switch_session\" :params {:session-id \"" sid1 "\"}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames    (parse-frames out-lines)
          switch-r  (some #(when (and (= :response (:kind %))
                                      (= "switch_session" (:op %))) %)
                          frames)
          resumed-e (some #(when (= "session/resumed" (:event %)) %)
                          frames)]
      (is (not= sid1 sid2))
      (is (string? path1))
      (is (.exists (java.io.File. path1)))
      (is (= sid1 (get-in switch-r [:data :session-id])))
      (is (= sid1 (get-in resumed-e [:data :session-id])))))

  (testing "targetable ops accept :session-id and route to that session"
    (let [cwd                (str (System/getProperty "java.io.tmpdir") "/psi-rpc-target-" (java.util.UUID/randomUUID))
          _                  (.mkdirs (java.io.File. cwd))
          [ctx session-id] (create-session-context {:cwd cwd})
          sd1                (session/new-session-in! ctx nil {})
          sid1               (:session-id sd1)
          path1              (:session-file sd1)
          _                  (persist/flush-journal! (java.io.File. path1)
                                                     sid1
                                                     cwd
                                                     nil
                                                     nil
                                                     [(persist/thinking-level-entry :off)])
          sd2                (session/new-session-in! ctx sid1 {})
          sid2               (:session-id sd2)
          _                  (session/new-session-in! ctx sid2 {})
          state              (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          input          (str "{:id \"m1\" :kind :request :op \"set_session_name\" :params {:session-id \"" sid1 "\" :name \"alpha\"}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frame          (-> out-lines first edn/read-string)
          sd1            (ss/get-state-value-in ctx (ss/state-path :session-data sid1))]
      (is (not= sid1 sid2))
      (is (= :response (:kind frame)))
      (is (= "set_session_name" (:op frame)))
      (is (= "alpha" (get-in frame [:data :session-name])))
      ;; Targeted op updates only the addressed session entry; there is no ambient
      ;; current-session concept on the shared ctx anymore.
      (is (= "alpha" (:session-name sd1)))))

  (testing "targetable op rejects invalid :session-id param"
    (let [[ctx _] (create-session-context)
          state   (atom {:ready? true :pending {}})
          handler (make-handler ctx state)
          {:keys [out-lines]}
          (run-loop "{:id \"g1\" :kind :request :op \"get_state\" :params {:session-id \"\"}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "get_state" (:op frame)))
      (is (= "request/invalid-params" (:error-code frame)))))

  ;; Route-lock tests removed — route-lock machinery eliminated in multi-session migration.
  ;; Sessions are now concurrently resident in the atom; no single-slot contention.
  #_(testing "targetable op rejects cross-session routing while prompt is in-flight when lock enforcement is enabled"
      (let [cwd     (str (System/getProperty "java.io.tmpdir") "/psi-rpc-routing-lock-" (java.util.UUID/randomUUID))
            _       (.mkdirs (java.io.File. cwd))
            [ctx session-id] (create-session-context {:cwd cwd})
            _                  (session/new-session-in! ctx nil {})
            sid1               (-> ctx ss/list-context-sessions-in last :session-id)
            path1   (:session-file (ss/get-session-data-in ctx sid1))
            _       (persist/flush-journal! (java.io.File. path1)
                                            sid1
                                            cwd
                                            nil
                                            nil
                                            [(persist/thinking-level-entry :off)])
            _       (session/new-session-in! ctx sid1 {})
            sid2    (-> ctx ss/list-context-sessions-in last :session-id)
            gate    (promise)
            release (promise)
            state   (atom {:ready? true
                           :pending {}
                           :enforce-session-route-lock? true
                           :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                           :run-agent-loop-fn (fn [& _]
                                                (deliver gate true)
                                                @release
                                                {:role "assistant" :content [{:type :text :text "ok"}]})})
            handler (make-handler ctx state)
            in-reader   (java.io.PipedReader.)
            in-writer   (java.io.PipedWriter. in-reader)
            out-writer  (java.io.StringWriter.)
            err-writer  (java.io.StringWriter.)
            write-line! (fn [line]
                          (.write in-writer (str line "\n"))
                          (.flush in-writer))
            loop-future (future
                          (rpc/run-stdio-loop! {:in              in-reader
                                                :out             out-writer
                                                :err             err-writer
                                                :state           state
                                                :request-handler handler}))]
        (try
          (write-line! "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}")
          (write-line! (str "{:id \"p1\" :kind :request :op \"prompt\" :params {:session-id \"" sid1 "\" :message \"hold\"}}"))
          (deref gate 1000 nil)
          (write-line! (str "{:id \"g2\" :kind :request :op \"get_state\" :params {:session-id \"" sid2 "\"}}"))
          (Thread/sleep 150)
          (deliver release true)
          (Thread/sleep 250)
          (.close in-writer)
          (deref loop-future 1000 nil)
          (let [frames (->> (str/split-lines (str out-writer))
                            (remove str/blank?)
                            parse-frames)
                conflict (some #(when (and (= :error (:kind %))
                                           (= "g2" (:id %))) %)
                               frames)]
            (is (some? conflict))
            (is (= "request/session-routing-conflict" (:error-code conflict)))
            (is (= sid1 (get-in conflict [:data :inflight-session-id])))
            (is (= sid2 (get-in conflict [:data :requested-session-id]))))
          (finally
            (deliver release true)
            (when-let [f (:ui-watch-loop @state)]
              (future-cancel f))
            (when-let [f (:external-event-loop @state)]
              (future-cancel f))
            (future-cancel loop-future)
            (try (.close in-writer) (catch Exception _ nil))
            (try (.close in-reader) (catch Exception _ nil))))))

  #_(testing "exclusive ops are rejected while prompt is in-flight when lock enforcement is enabled"
      (let [cwd     (str (System/getProperty "java.io.tmpdir") "/psi-rpc-routing-lock-exclusive-" (java.util.UUID/randomUUID))
            _       (.mkdirs (java.io.File. cwd))
            [ctx session-id] (create-session-context {:cwd cwd})
            _                  (session/new-session-in! ctx nil {})
            sid1               (-> ctx ss/list-context-sessions-in last :session-id)
            path1   (:session-file (ss/get-session-data-in ctx sid1))
            _       (persist/flush-journal! (java.io.File. path1)
                                            sid1
                                            cwd
                                            nil
                                            nil
                                            [(persist/thinking-level-entry :off)])
            gate    (promise)
            release (promise)
            state   (atom {:ready? true
                           :pending {}
                           :enforce-session-route-lock? true
                           :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                           :run-agent-loop-fn (fn [& _]
                                                (deliver gate true)
                                                @release
                                                {:role "assistant" :content [{:type :text :text "ok"}]})})
            handler (make-handler ctx state)
            in-reader   (java.io.PipedReader.)
            in-writer   (java.io.PipedWriter. in-reader)
            out-writer  (java.io.StringWriter.)
            err-writer  (java.io.StringWriter.)
            write-line! (fn [line]
                          (.write in-writer (str line "\n"))
                          (.flush in-writer))
            loop-future (future
                          (rpc/run-stdio-loop! {:in              in-reader
                                                :out             out-writer
                                                :err             err-writer
                                                :state           state
                                                :request-handler handler}))]
        (try
          (write-line! "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}")
          (write-line! (str "{:id \"p1\" :kind :request :op \"prompt\" :params {:session-id \"" sid1 "\" :message \"hold\"}}"))
          (deref gate 1000 nil)
          (write-line! "{:id \"n2\" :kind :request :op \"new_session\"}")
          (Thread/sleep 150)
          (deliver release true)
          (Thread/sleep 250)
          (.close in-writer)
          (deref loop-future 1000 nil)
          (let [frames (->> (str/split-lines (str out-writer))
                            (remove str/blank?)
                            parse-frames)
                conflict (some #(when (and (= :error (:kind %))
                                           (= "n2" (:id %))) %)
                               frames)]
            (is (some? conflict))
            (is (= "request/session-routing-conflict" (:error-code conflict)))
            (is (= sid1 (get-in conflict [:data :inflight-session-id])))
            (is (= sid1 (get-in conflict [:data :requested-session-id]))))
          (finally
            (deliver release true)
            (when-let [f (:ui-watch-loop @state)]
              (future-cancel f))
            (when-let [f (:external-event-loop @state)]
              (future-cancel f))
            (future-cancel loop-future)
            (try (.close in-writer) (catch Exception _ nil))
            (try (.close in-reader) (catch Exception _ nil)))))))

(deftest rpc-new-session-uses-callback-rehydrate-payload-test
  (testing "new_session uses on-new-session! callback when provided"
    (let [[ctx session-id] (create-session-context)
          called? (atom 0)
          state (atom {:ready? true
                       :pending {}
                       :subscribed-topics #{"session/rehydrated"}})
          handler (rpc/make-session-request-handler ctx {:on-new-session! (fn [_source-session-id]
                                                                            (swap! called? inc)
                                                                            {:agent-messages [{:role "assistant"
                                                                                               :content [{:type :text :text "startup reply"}]}]
                                                                             :messages [{:role :assistant :text "startup reply"}]
                                                                             :tool-calls {"call-1" {:name "read"}}
                                                                             :tool-order ["call-1"]})})
          input (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"n1\" :kind :request :op \"new_session\" :params {:session-id \"" session-id "\"}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames (parse-frames out-lines)
          rehydrate-event (some #(when (= "session/rehydrated" (:event %)) %) frames)]
      (is (= 1 @called?))
      (is (some? rehydrate-event))
      (is (= [{:role "assistant"
               :content [{:type :text :text "startup reply"}]}]
             (get-in rehydrate-event [:data :messages])))
      (is (= ["call-1"]
             (get-in rehydrate-event [:data :tool-order]))))))

(deftest rpc-new-session-footer-usage-is-session-scoped-test
  (testing "new_session footer/updated does not carry usage totals from previous session"
    (let [[ctx session-id] (create-session-context)
          state      (atom {:ready? true
                            :pending {}
                            :subscribed-topics #{"footer/updated"}})
          handler (make-handler ctx state)
          _          (ss/journal-append-in! ctx session-id
                                            {:kind :message
                                             :session-id session-id
                                             :data {:message {:role "assistant"
                                                              :usage {:input-tokens 111
                                                                      :output-tokens 22}}}})
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"n1\" :kind :request :op \"new_session\"}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames (parse-frames out-lines)
          footer-event (some #(when (= "footer/updated" (:event %)) %) frames)
          stats-line (get-in footer-event [:data :stats-line] "")]
      (is (some? footer-event))
      (is (string? stats-line))
      (is (not (str/includes? stats-line "↑111")))
      (is (not (str/includes? stats-line "↓22"))))))

(deftest footer-updated-payload-includes-model-and-thinking-when-session-reasoning-enabled-test
  (testing "footer payload includes model/thinking details from active session query"
    (let [[ctx session-id] (create-session-context)
          _          (dispatch/dispatch! ctx :session/set-model
                                         {:session-id session-id :model {:provider "openai" :id "gpt-5.3-codex" :reasoning true}}
                                         {:origin :core})
          _          (dispatch/dispatch! ctx :session/set-thinking-level {:session-id session-id :level :high} {:origin :core})
          _          (dispatch/dispatch! ctx :session/update-context-usage {:session-id session-id :tokens 4000 :window 100000} {:origin :core})
          _          (ss/journal-append-in! ctx session-id
                                            {:kind :message
                                             :session-id session-id
                                             :data {:message {:role "assistant"
                                                              :usage {:input-tokens 111
                                                                      :output-tokens 22}}}})
          payload (rpc.events/footer-updated-payload ctx)
          stats-line (:stats-line payload)]
      (is (= ["↑111" "↓22" "4.0%/100k"]
             (:usage-parts payload)))
      (is (= "(openai) gpt-5.3-codex • thinking high"
             (:model-text payload)))
      (is (string? stats-line))
      (is (str/includes? stats-line "↑111"))
      (is (str/includes? stats-line "↓22"))
      (is (str/includes? stats-line "4.0%/100k"))
      (is (str/includes? stats-line "(openai) gpt-5.3-codex • thinking high")))))

(deftest rpc-subscribe-emits-context-updated-test
  (testing "subscribe emits context/updated with active-session-id and sessions list"
    (let [[ctx session-id] (create-session-context)
          user-ts          (java.time.Instant/parse "2026-03-16T10:47:00Z")
          _                (ss/journal-append-in! ctx session-id
                                                  (persist/message-entry {:role "user"
                                                                          :content [{:type :text :text "hi"}]
                                                                          :timestamp user-ts}))
          state            (atom {:ready? true
                                  :pending {}
                                  :subscribed-topics #{"context/updated"}})
          handler          (make-handler ctx state)
          input            (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"context/updated\"]}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames           (parse-frames out-lines)
          context-evt      (some #(when (= "context/updated" (:event %)) %) frames)
          session-slot     (some #(when (= session-id (:id %)) %) (get-in context-evt [:data :sessions]))]
      (is (some? context-evt) "context/updated must be emitted on subscribe")
      (is (contains? (:data context-evt) :active-session-id))
      (is (vector? (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :display-name) (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :worktree-path) (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :created-at) (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :updated-at) (get-in context-evt [:data :sessions])))
      (is (= "psi-session" (get-in context-evt [:data :session-tree-widget :extension-id])))
      (is (= "session-tree" (get-in context-evt [:data :session-tree-widget :widget-id])))
      (is (vector? (get-in context-evt [:data :session-tree-widget :content-lines])))
      (is (= "hi" (:display-name session-slot)) "display-name should reflect inferred latest user message")
      (is (= (str user-ts) (:updated-at session-slot)) "updated-at should reflect latest message timestamp"))))

(deftest rpc-fork-emits-context-updated-test
  (testing "fork emits rehydration and context/updated with new session in sessions list"
    (let [cwd     (str (System/getProperty "java.io.tmpdir") "/psi-rpc-fork-" (java.util.UUID/randomUUID))
          _       (.mkdirs (java.io.File. cwd))
          [ctx session-id] (create-session-context {:cwd cwd})
          _       (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model {:provider "anthropic" :id "claude-sonnet"}} {:origin :core})
          ;; Append a message entry so fork has an entry-id to branch from
          entry   (persist/message-entry {:role "user" :content "hi"})
          _       (ss/journal-append-in! ctx session-id entry)
          _       (ss/journal-append-in! ctx session-id (persist/message-entry {:role "assistant" :content [{:type :text :text "reply"}]}))
          entry-id (:id entry)
          state   (atom {:ready? true
                         :pending {}
                         :subscribed-topics #{"context/updated" "session/rehydrated"}})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"f1\" :kind :request :op \"fork\" :params {:entry-id \"" entry-id "\"}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames      (parse-frames out-lines)
          fork-resp   (some #(when (and (= :response (:kind %)) (= "fork" (:op %))) %) frames)
          rehyd-evt   (some #(when (= "session/rehydrated" (:event %)) %) frames)
          context-evt (some #(when (= "context/updated" (:event %)) %) frames)
          new-sid     (get-in fork-resp [:data :session-id])]
      (is (some? fork-resp) "fork must return a response")
      (is (string? new-sid) "fork must return a new session-id")
      (is (some? rehyd-evt) "fork must emit session/rehydrated")
      (is (= [{:role "user" :content "hi"}
              {:role "assistant" :content [{:type :text :text "reply"}]}]
             (get-in rehyd-evt [:data :messages])))
      (is (some? context-evt) "fork must emit context/updated")
      (is (= new-sid (get-in context-evt [:data :active-session-id]))
          "context/updated active-session-id must be the forked session")
      (is (some #(= new-sid (:id %)) (get-in context-evt [:data :sessions]))
          "context/updated sessions must include the forked session")
      (is (every? #(contains? % :worktree-path) (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :created-at) (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :updated-at) (get-in context-evt [:data :sessions])))))

  (testing "frontend_action_result select-session accepts fork-point payload and forks"
    (let [cwd      (str (System/getProperty "java.io.tmpdir") "/psi-rpc-frontend-fork-" (java.util.UUID/randomUUID))
          _        (.mkdirs (java.io.File. cwd))
          [ctx sid] (create-session-context {:cwd cwd})
          entry     (persist/message-entry {:role "user" :content "branch here"})
          _         (ss/journal-append-in! ctx sid entry)
          _         (ss/journal-append-in! ctx sid (persist/message-entry {:role "assistant" :content [{:type :text :text "reply here"}]}))
          state     (atom {:ready? true
                           :pending {}
                           :subscribed-topics #{"session/resumed" "session/rehydrated" "context/updated"}})
          handler   (make-handler ctx state)
          input     (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"a1\" :kind :request :op \"frontend_action_result\" :params {:request-id \"req-1\" :action-name \"select-session\" :status \"submitted\" :value {:action/kind :fork-session :action/entry-id \"" (:id entry) "\" :action/session-id \"" sid "\"}}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames      (parse-frames out-lines)
          resumed-evt (some #(when (= "session/resumed" (:event %)) %) frames)
          rehyd-evt   (some #(when (= "session/rehydrated" (:event %)) %) frames)
          context-evt (some #(when (= "context/updated" (:event %)) %) frames)
          new-sid     (get-in resumed-evt [:data :session-id])]
      (is (some? resumed-evt))
      (is (some? rehyd-evt))
      (is (some? context-evt))
      (is (string? new-sid))
      (is (not= sid new-sid))
      (is (= new-sid (get-in context-evt [:data :active-session-id])))
      (is (= [{:role "user" :content "branch here"}
              {:role "assistant" :content [{:type :text :text "reply here"}]}]
             (get-in rehyd-evt [:data :messages]))))))

(deftest rpc-new-session-emits-context-updated-test
  (testing "new_session emits context/updated event"
    (let [[ctx session-id] (create-session-context)
          state   (atom {:ready? true
                         :pending {}
                         :subscribed-topics #{"context/updated"}})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"n1\" :kind :request :op \"new_session\" :params {:session-id \"" session-id "\"}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames    (parse-frames out-lines)
          new-resp  (some #(when (and (= :response (:kind %)) (= "new_session" (:op %))) %) frames)
          context-evt  (some #(when (= "context/updated" (:event %)) %) frames)
          new-sid   (get-in new-resp [:data :session-id])]
      (is (some? context-evt) "new_session must emit context/updated")
      (is (= new-sid (get-in context-evt [:data :active-session-id])))
      (is (vector? (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :worktree-path) (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :created-at) (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :updated-at) (get-in context-evt [:data :sessions]))))))

(deftest rpc-e2e-handshake-query-and-streaming-test
  (testing "handshake -> query_eql -> prompt with interleaved events"
    (let [[ctx _] (create-session-context)
          state (atom {:ready? true
                       :pending {}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :run-agent-loop-fn (fn [_ai-ctx _ctx _session-id _agent-ctx _ai-model _new-messages {:keys [progress-queue]}]
                                            (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                    {:event-kind :text-delta :text "Hello" :type :agent-event})
                                            {:role "assistant"
                                             :content [{:type :text :text "Hello final"}]
                                             :stop-reason :stop
                                             :usage {:total-tokens 2}})})
          handler (make-handler ctx state)
          input (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"q1\" :kind :request :op \"query_eql\" :params {:query \"[:psi.graph/domain-coverage :psi.memory/status]\"}}\n"
                     "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"assistant/message\" \"session/updated\" \"footer/updated\"]}}\n"
                     "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          {:keys [out-lines]} (run-loop input handler state 250)
          frames (parse-frames out-lines)
          handshake-frame (some #(when (and (= :response (:kind %)) (= "handshake" (:op %))) %) frames)
          query-frame (some #(when (and (= :response (:kind %)) (= "query_eql" (:op %))) %) frames)
          prompt-response-index (first (keep-indexed (fn [i f] (when (and (= :response (:kind f)) (= "prompt" (:op f))) i)) frames))
          event-indexes (vec (keep-indexed (fn [i f] (when (= :event (:kind f)) i)) frames))]
      (is handshake-frame)
      (is (= true (:ok handshake-frame)))
      (is query-frame)
      (is (= true (:ok query-frame)))
      (is (contains? (get-in query-frame [:data :result]) :psi.graph/domain-coverage))
      (is (contains? (get-in query-frame [:data :result]) :psi.memory/status))
      (is (number? prompt-response-index))
      (is (seq event-indexes))
      (is (some #(< prompt-response-index %) event-indexes))
      (is (some #(= "assistant/delta" (:event %)) (filter #(= :event (:kind %)) frames))))))

(deftest rpc-prompt-honors-explicit-session-id-test
  (testing "prompt worker uses explicit :session-id routing, even when focus points elsewhere"
    (let [[ctx _a-sid] (create-session-context)
          z-sd         (session/new-session-in! ctx _a-sid {})
          z-sid        (:session-id z-sd)
          captured     (atom nil)
          state        (atom {:ready? true
                              :pending {}
                              :focus-session-id* (atom _a-sid)
                              :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                              :run-agent-loop-fn (fn [_ai-ctx _ctx sid _agent-ctx _ai-model _new-messages _opts]
                                                   (reset! captured sid)
                                                   {:role "assistant"
                                                    :content [{:type :text :text "ok"}]})})
          handler (make-handler ctx state)
          input    (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                        (format "{:id \"p1\" :kind :request :op \"prompt\" :params {:session-id \"%s\" :message \"hi\"}}\n" z-sid))]
      (run-loop input handler state 300)
      (dotimes [_ 20]
        (when-not @captured
          (Thread/sleep 50)))
      (is (= z-sid @captured)))))

(deftest rpc-prompt-slash-dispatch-gate-test
  (testing "when commands/dispatch-in returns non-nil, run-agent-loop-fn is NOT called"
    (let [[ctx _]      (create-session-context)
          loop-called? (atom false)
          state        (atom {:ready? true
                              :pending {}
                              :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                              :run-agent-loop-fn (fn [& _]
                                                   (reset! loop-called? true)
                                                   {:role "assistant" :content []})})
          handler (make-handler ctx state)
          input        (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                            "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\" \"session/updated\" \"footer/updated\"]}}\n"
                            "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/history\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type :text :message "history output"})]
        (let [{:keys [out-lines]} (run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line]
                                   (try (edn/read-string line)
                                        (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (false? @loop-called?)
              "run-agent-loop-fn must NOT be called when dispatch returns a command result")
          (is (some? msg-evt)
              "assistant/message event must be emitted for the command result")
          (is (= "assistant"
                 (get-in msg-evt [:data :role]))
              "assistant/message role must be \"assistant\"")
          (is (some #(str/includes? (get % :text "") "history output")
                    (get-in msg-evt [:data :content]))
              "assistant/message content must include command output text")))))

  (testing "when commands/dispatch-in returns nil, run-agent-loop-fn IS called"
    (let [[ctx _]      (create-session-context)
          loop-called? (atom false)
          state        (atom {:ready? true
                              :pending {}
                              :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                              :run-agent-loop-fn (fn [& _]
                                                   (reset! loop-called? true)
                                                   {:role "assistant" :content [{:type :text :text "ok"}]})})
          handler (make-handler ctx state)
          input        (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                            "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"plain text\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts] nil)]
        (run-loop input handler state 250)
        (is (true? @loop-called?)
            "run-agent-loop-fn must be called when dispatch returns nil")))))

(deftest rpc-prompt-expands-skill-input-before-agent-loop-test
  (testing "non-command /skill prompt is expanded through shared runtime path"
    (let [skill-file   (java.io.File/createTempFile "psi-rpc-skill-" ".md")
          _           (.deleteOnExit skill-file)
          _           (spit skill-file "# Skill Body\nUse this carefully.")
          skill       {:name "demo"
                       :description "Demo skill"
                       :file-path (.getAbsolutePath skill-file)
                       :base-dir (.getParent skill-file)
                       :source :path
                       :disable-model-invocation false}
          [ctx _]     (create-session-context {:session-defaults {:skills [skill]}})
          captured    (atom nil)
          bridge      (fn [_ai-ctx _ctx session-id _agent-ctx prepared _pq]
                        ;; Capture the user message text from the prepared request.
                        ;; Provider-format messages have {:role :user :content {:kind :text :text "..."}}
                        (let [msgs (:prepared-request/messages prepared)
                              user-msg (first (filter #(= :user (:role %)) msgs))
                              content  (:content user-msg)
                              txt      (cond
                                         (string? content) content
                                         (map? content)    (:text content)
                                         (vector? content) (-> content first :text))]
                          (reset! captured txt))
                        (assistant-msg->execution-result session-id
                                                         {:role "assistant"
                                                          :content [{:type :text :text "ok"}]
                                                          :stop-reason :stop}))
          ctx*        (assoc ctx :execute-prepared-request-fn bridge)
          state       (atom {:ready? true
                             :pending {}
                             :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}})
          handler     (rpc/make-session-request-handler ctx* (select-keys @state [:rpc-ai-model]))
          input       (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                           "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/skill:demo apply this\"}}\n")]
      (run-loop input handler state 250)
      (is (string? @captured))
      (when (string? @captured)
        (is (str/includes? @captured "<skill name=\"demo\""))
        (is (str/includes? @captured "# Skill Body"))
        (is (str/includes? @captured "apply this"))
        (is (not= "/skill:demo apply this" @captured))))))

(deftest rpc-prompt-passes-resolved-api-key-to-agent-loop-test
  (testing "non-command prompt forwards runtime-resolved api-key through prepared request"
    (let [[ctx _]    (create-session-context)
          captured   (atom nil)
          state      (atom {:ready? true
                            :pending {}
                            :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}})
          handler (make-handler ctx state)
          input      (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                          "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"plain text\"}}\n")]
      (with-redefs [runtime/resolve-api-key-in (fn [_ctx _session-id _model] "test-api-key")
                    commands/dispatch-in (fn [_ctx _session-id _text _opts] nil)
                    prompt-runtime/execute-prepared-request!
                    (fn [_ai-ctx _ctx session-id _agent-ctx prepared _pq]
                      (reset! captured (:prepared-request/ai-options prepared))
                      {:execution-result/turn-id (:prepared-request/id prepared)
                       :execution-result/session-id session-id
                       :execution-result/assistant-message {:role "assistant"}
                       :content [{:type :text :text "ok"}]
                       :stop-reason :stop
                       :timestamp (java.time.Instant/now)
                       :execution-result/turn-outcome :turn.outcome/stop
                       :execution-result/tool-calls []
                       :execution-result/stop-reason :stop})]
        (run-loop input handler state 250)
        (is (= "test-api-key" (:api-key @captured)))))))

(deftest rpc-prompt-handle-command-result-types-test
  (testing "text-command-emits-assistant-message with session/updated and footer/updated"
    (let [[ctx _]    (create-session-context)
          state  (atom {:ready? true
                        :pending {}
                        :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                        :run-agent-loop-fn (fn [& _] {:role "assistant" :content []})})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\" \"session/updated\" \"footer/updated\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/history\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type :text :message "<history output>"})]
        (let [{:keys [out-lines]} (run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              topics  (set (map :event events))
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (some? msg-evt) "assistant/message must be emitted")
          (is (some #(str/includes? (get % :text "") "<history output>")
                    (get-in msg-evt [:data :content]))
              "assistant/message must contain command output")
          (is (contains? topics "session/updated") "session/updated must be emitted")
          (is (contains? topics "footer/updated") "footer/updated must be emitted")))))

  (testing "extension-cmd-executes-handler and captures stdout"
    (let [[ctx _]    (create-session-context)
          loop-called? (atom false)
          received-args (atom nil)
          state  (atom {:ready? true
                        :pending {}
                        :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                        :run-agent-loop-fn (fn [& _]
                                             (reset! loop-called? true)
                                             {:role "assistant" :content []})})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/ext-cmd\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type    :extension-cmd
                                            :name    "test-cmd"
                                            :args    "some args"
                                            :handler (fn [args]
                                                       (reset! received-args args)
                                                       (println "ext output"))})]
        (let [{:keys [out-lines]} (run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (false? @loop-called?) "agent loop must NOT be called for extension-cmd")
          (is (= "some args" @received-args)
              "extension handler must receive raw args string")
          (is (some? msg-evt) "assistant/message must be emitted")
          (is (some #(str/includes? (get % :text "") "ext output")
                    (get-in msg-evt [:data :content]))
              "stdout from extension handler must appear in assistant/message")))))

  (testing "extension-cmd-handler-error-surfaced deterministically"
    (let [[ctx _]    (create-session-context)
          loop-called? (atom false)
          state  (atom {:ready? true
                        :pending {}
                        :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                        :run-agent-loop-fn (fn [& _]
                                             (reset! loop-called? true)
                                             {:role "assistant" :content []})})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/ext-err\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type    :extension-cmd
                                            :name    "err-cmd"
                                            :args    ""
                                            :handler (fn [_] (throw (ex-info "boom" {})))})]
        (let [{:keys [out-lines]} (run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (false? @loop-called?) "agent loop must NOT be called on handler error")
          (is (some? msg-evt) "assistant/message must be emitted on error")
          (is (some #(str/includes? (get % :text "") "[extension command error:")
                    (get-in msg-evt [:data :content]))
              "error message must be surfaced in assistant/message")))))

  (testing "login-start-emits-url-text"
    (let [[ctx _]    (create-session-context)
          state  (atom {:ready? true
                        :pending {}
                        :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                        :run-agent-loop-fn (fn [& _] {:role "assistant" :content []})})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/login\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type     :login-start
                                            :provider {:name "Anthropic"}
                                            :url      "https://example.com/auth"})]
        (let [{:keys [out-lines]} (run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (some? msg-evt) "assistant/message must be emitted for login-start")
          (is (some #(str/includes? (get % :text "") "https://example.com/auth")
                    (get-in msg-evt [:data :content]))
              "URL must appear in assistant/message content")))))

  (testing "login-start manual flow uses pending-code path and shared oauth completion"
    (let [[ctx _]      (create-session-context {:oauth-ctx {:mode :test}})
          loop-called? (atom false)
          dispatches   (atom [])
          completions  (atom [])
          state        (atom {:ready? true
                              :pending {}
                              :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                              :run-agent-loop-fn (fn [& _]
                                                   (reset! loop-called? true)
                                                   {:role "assistant" :content []})})
          handler (make-handler ctx state)]
      (with-redefs [commands/dispatch-in
                    (fn [_ctx _session-id text _opts]
                      (swap! dispatches conj text)
                      (when (= text "/login")
                        {:type                 :login-start
                         :provider             {:id :anthropic :name "Anthropic"}
                         :url                  "https://example.com/auth"
                         :login-state          {:verifier "v1"}
                         :uses-callback-server false}))
                    oauth/complete-login!
                    (fn [_oauth-ctx provider-id input login-state]
                      (swap! completions conj {:provider-id provider-id
                                               :input input
                                               :login-state login-state})
                      {:type :oauth :access "tok" :refresh "ref" :expires (+ (System/currentTimeMillis) 60000)})]
        (run-loop "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/login\"}}\n"
                  handler state 250)
        (is (some? (:pending-login (sa/oauth-projection-in ctx)))
            "manual login-start should set canonical pending-login state")

        (run-loop "{:id \"p2\" :kind :request :op \"prompt\" :params {:message \"auth-code-123\"}}\n"
                  handler state 250)

        (is (= [{:provider-id :anthropic
                 :input "auth-code-123"
                 :login-state {:verifier "v1"}}]
               @completions)
            "pending auth code prompt should complete login via oauth/complete-login!")
        (is (nil? (:pending-login (sa/oauth-projection-in ctx)))
            "pending-login should clear from canonical oauth state after completion attempt")
        (is (= ["/login"] @dispatches)
            "second prompt must bypass commands/dispatch-in while login is pending")
        (is (false? @loop-called?)
            "agent loop must not run during manual login completion"))))

  (testing "login-start callback flow auto-completes with nil input"
    (let [[ctx _]      (create-session-context {:oauth-ctx {:mode :test}})
          loop-called? (atom false)
          completions  (atom [])
          state        (atom {:ready? true
                              :pending {}
                              :rpc-ai-model {:provider "openai" :id "stub" :supports-reasoning true}
                              :run-agent-loop-fn (fn [& _]
                                                   (reset! loop-called? true)
                                                   {:role "assistant" :content []})})
          handler (make-handler ctx state)]
      (with-redefs [commands/dispatch-in
                    (fn [_ctx _session-id text _opts]
                      (when (= text "/login")
                        {:type                 :login-start
                         :provider             {:id :openai :name "OpenAI"}
                         :url                  "https://example.com/openai"
                         :login-state          {:state "s1"}
                         :uses-callback-server true}))
                    oauth/complete-login!
                    (fn [_oauth-ctx provider-id input login-state]
                      (swap! completions conj {:provider-id provider-id
                                               :input input
                                               :login-state login-state})
                      {:type :oauth :access "tok" :refresh "ref" :expires (+ (System/currentTimeMillis) 60000)})]
        (let [{:keys [out-lines]} (run-loop "{:id \"p3\" :kind :request :op \"prompt\" :params {:message \"/login\"}}\n"
                                            handler state 800)
              events (->> out-lines parse-frames (filter #(= :event (:kind %)))
                          (filter #(= "assistant/message" (:event %))))
              texts  (mapcat #(map :text (get-in % [:data :content])) events)]
          (is (= [{:provider-id :openai
                   :input nil
                   :login-state {:state "s1"}}]
                 @completions)
              "callback login-start should complete with nil input and wait for callback server")
          (is (nil? (:pending-login (sa/oauth-projection-in ctx)))
              "callback flow should not leave canonical pending-login state")
          (is (false? @loop-called?)
              "agent loop must not run during callback login completion")
          (is (some #(str/includes? % "Waiting for browser callback") texts)
              "callback flow should emit waiting status text")
          (is (some #(str/includes? % "Logged in to OpenAI") texts)
              "callback flow should emit completion text")))))

  (testing "quit-emits-fallback-text"
    (let [[ctx _]    (create-session-context)
          state  (atom {:ready? true
                        :pending {}
                        :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                        :run-agent-loop-fn (fn [& _] {:role "assistant" :content []})})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/quit\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type :quit})]
        (let [{:keys [out-lines]} (run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (some? msg-evt) "assistant/message must be emitted for quit")
          (is (some #(str/includes? (get % :text "") "not supported over RPC prompt")
                    (get-in msg-evt [:data :content]))
              "fallback text must appear in assistant/message content")))))

  (testing "resume-emits-fallback-text"
    (let [[ctx _]    (create-session-context)
          state  (atom {:ready? true
                        :pending {}
                        :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                        :run-agent-loop-fn (fn [& _] {:role "assistant" :content []})})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/resume\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type :resume})]
        (let [{:keys [out-lines]} (run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (some? msg-evt) "assistant/message must be emitted for resume")
          (is (some #(str/includes? (get % :text "") "not supported over RPC prompt")
                    (get-in msg-evt [:data :content]))
              "fallback text must appear in assistant/message content")))))

  (testing "remember accepted path emits confirmation and writes exactly one record"
    (let [[ctx* _] (create-session-context)
          ctx     (assoc ctx*
                         :memory-ctx
                         (memory/create-context {:state-overrides {:status :ready}}))
          state   (atom {:ready? true
                         :pending {}
                         :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                         :run-agent-loop-fn (fn [& _] {:role "assistant" :content []})})
          handler (make-handler ctx state)
          before-count (count (:records (memory/get-state-in (:memory-ctx ctx))))
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/remember rpc-accepted\"}}\n")
          {:keys [out-lines]} (run-loop input handler state 300)
          frames   (->> out-lines
                        (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                        vec)
          events   (filter #(= :event (:kind %)) frames)
          msg-evts (filter #(= "assistant/message" (:event %)) events)
          texts    (mapcat #(map :text (get-in % [:data :content])) msg-evts)
          after-state (memory/get-state-in (:memory-ctx ctx))
          after-count (count (:records after-state))
          rec (last (:records after-state))
          prov (:provenance rec)
          telemetry (session/query-in ctx
                                      [:psi.memory.remember/captures
                                       :psi.memory.remember/last-capture-at])]
      (is (some #(str/includes? % "Remembered") texts))
      (is (= 1 (- after-count before-count)) "exactly one remember record per RPC invocation")
      (is (= "rpc-accepted" (:content rec)))
      (is (= :remember (get-in rec [:provenance :source])))
      (is (string? (:sessionId prov)))
      (is (= (:cwd ctx) (:cwd prov)))
      (is (contains? prov :gitBranch))
      (is (some #(= (:record-id rec) (:record-id %))
                (:psi.memory.remember/captures telemetry))
          "captured remember record should be visible in remember telemetry captures")
      (is (= (:timestamp rec) (:psi.memory.remember/last-capture-at telemetry)))))

  (testing "remember emits canonical blocked error when memory is not ready"
    (let [[ctx* _] (create-session-context)
          ctx     (assoc ctx*
                         :memory-ctx
                         (memory/create-context
                          {:state-overrides {:status :initializing}}))
          state   (atom {:ready? true
                         :pending {}
                         :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                         :run-agent-loop-fn (fn [& _] {:role "assistant" :content []})})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/remember blocked-path\"}}\n")
          {:keys [out-lines]} (run-loop input handler state 300)
          frames   (->> out-lines
                        (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                        vec)
          events   (filter #(= :event (:kind %)) frames)
          msg-evts (filter #(= "assistant/message" (:event %)) events)
          texts    (mapcat #(map :text (get-in % [:data :content])) msg-evts)]
      (is (some #(str/includes? % "Remember blocked") texts))
      (is (some #(str/includes? % "memory_capture_prerequisites_not_ready") texts))))

  (testing "remember emits fallback warning when active store write fails"
    (let [[ctx* _]    (create-session-context)
          ctx         (assoc ctx*
                             :memory-ctx
                             (memory/create-context {:state-overrides {:status :ready}}))
          status-atom (atom :ready)
          provider    (reify store/StoreProvider
                        (provider-id [_] "failing-store")
                        (provider-capabilities [_]
                          {:durability :persistent
                           :supports-restart-recovery? true
                           :supports-retention-compaction? true
                           :supports-capability-history-query? true
                           :query-mode :indexed})
                        (open-provider! [this _] this)
                        (close-provider! [this] this)
                        (provider-status [_] @status-atom)
                        (provider-health [_]
                          {:status :healthy
                           :checked-at (java.time.Instant/now)
                           :details nil})
                        (provider-write! [_ _ _]
                          {:ok? false :error :boom :message "write failed"})
                        (provider-query! [_ _] {:ok? true :results []})
                        (provider-load-state [_] {:ok? true}))
          _ (memory/register-store-provider-in! (:memory-ctx ctx) provider)
          _ (memory/select-store-provider-in! (:memory-ctx ctx) "failing-store")
          state   (atom {:ready? true
                         :pending {}
                         :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                         :run-agent-loop-fn (fn [& _] {:role "assistant" :content []})})
          handler (make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/remember provider-outage\"}}\n")
          {:keys [out-lines]} (run-loop input handler state 300)
          frames   (->> out-lines
                        (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                        vec)
          events   (filter #(= :event (:kind %)) frames)
          msg-evts (filter #(= "assistant/message" (:event %)) events)
          texts    (mapcat #(map :text (get-in % [:data :content])) msg-evts)]
      (is (some #(str/includes? % "Remembered with store fallback") texts))
      (is (some #(str/includes? % "store-error: boom") texts))
      (is (some #(str/includes? % "provider: failing-store") texts)))))

(deftest rpc-prompt-slash-command-journaled-test
  (testing "slash command user message is journaled even when dispatch matches (not only on agent-loop path)"
    (let [[ctx session-id] (create-session-context)
          state      (atom {:ready? true
                            :pending {}
                            :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                            :run-agent-loop-fn (fn [& _] {:role "assistant" :content []})})
          handler (make-handler ctx state)
          input      (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                          "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/history\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type :text :message "history output"})]
        (run-loop input handler state 250)
        (let [journal-entries (persist/all-entries-in ctx session-id)
              msg-entries     (filterv #(= :message (:kind %)) journal-entries)
              user-msg        (some #(when (= "user" (get-in % [:data :message :role])) %) msg-entries)]
          (is (seq msg-entries)
              "journal must contain at least one :message entry after slash command")
          (is (some? user-msg)
              "journal must contain the user message for the slash command submission")
          (is (= "/history"
                 (get-in user-msg [:data :message :content 0 :text]))
              "journaled user message must contain the slash command text"))))))

(deftest rpc-prompt-plain-text-journaled-test
  (testing "plain text prompt user message is journaled on agent-loop path"
    (let [[ctx session-id] (create-session-context)
          state      (atom {:ready? true
                            :pending {}
                            :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                            :run-agent-loop-fn (fn [& _] {:role "assistant" :content [{:type :text :text "ok"}]})})
          handler (make-handler ctx state)
          input      (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                          "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"tell me a joke\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts] nil)]
        (run-loop input handler state 250)
        (let [journal-entries (persist/all-entries-in ctx session-id)
              msg-entries     (filterv #(= :message (:kind %)) journal-entries)
              user-msg        (some #(when (= "user" (get-in % [:data :message :role])) %) msg-entries)]
          (is (some? user-msg)
              "journal must contain the user message for plain text prompt")
          (is (= "tell me a joke"
                 (get-in user-msg [:data :message :content 0 :text]))
              "journaled user message must contain the prompt text"))))))
