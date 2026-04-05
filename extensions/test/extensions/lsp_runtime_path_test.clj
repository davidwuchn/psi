(ns extensions.lsp-runtime-path-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.lsp :as sut]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.services :as services]
   [psi.agent-session.test-support :as test-support]
   [psi.query.core :as query]))

(def ^:private lsp-fixture-command
  [(or (System/getenv "BB") "bb")
   (str (.getCanonicalPath (java.io.File. "extensions/test/extensions/lsp_fixture_bb.clj")))])

(defn- create-temp-worktree []
  (let [dir (io/file (test-support/temp-cwd))
        git (io/file dir ".git")]
    (.mkdirs dir)
    (.mkdirs git)
    (.getCanonicalPath dir)))

(defn- write-file! [path text]
  (spit (io/file path) text)
  path)

(defn- create-runtime-session [worktree]
  (let [ctx (session/create-context (test-support/safe-context-opts {:cwd worktree}))
        sd  (session/new-session-in! ctx nil {:worktree-path worktree})]
    [ctx (:session-id sd)]))

(defn- create-ext-api [ctx session-id]
  (let [qctx   (query/create-query-context)
        mutate (fn [op params]
                 (get (query/query-in qctx
                                      {:psi/agent-session-ctx ctx}
                                      [(list op (assoc params :psi/agent-session-ctx ctx :session-id session-id))])
                      op))
        query* (fn [eql]
                 (session/query-in ctx eql {:session-id session-id}))
        runtime-fns {:query-fn query*
                     :mutate-fn mutate
                     :get-api-key-fn (fn [_] nil)
                     :ui-type-fn (fn [] :console)
                     :ui-context-fn (fn [_] nil)
                     :log-fn (fn [_] nil)}]
    (session/register-resolvers-in! qctx false)
    (session/register-mutations-in! qctx mutations/all-mutations true)
    (ext/create-extension-api (:extension-registry ctx) "/ext/lsp.clj" runtime-fns)))

(deftest service-spec-roundtrips-through-real-runtime-test
  (testing "extension service spec can be ensured through mutation path and gets live runtime fns"
    (let [[ctx session-id] (create-runtime-session (create-temp-worktree))
          api (create-ext-api ctx session-id)
          root (create-temp-worktree)
          spec (sut/service-spec root {:command lsp-fixture-command})]
      (try
        ((:ensure-service api)
         {:key (sut/workspace-key root)
          :type :subprocess
          :spec spec})
        (let [svc (services/service-in ctx (sut/workspace-key root))]
          (is (= :json-rpc (:protocol svc)))
          (is (fn? (:send-fn svc)))
          (is (fn? (:await-response-fn svc))))
        (finally
          (when-let [close-fn (:close-fn (services/service-in ctx (sut/workspace-key root)))]
            (future (close-fn)))
          (future (services/stop-service-in! ctx (sut/workspace-key root))))))))

(deftest sync-tool-result-runs-live-initialize-sync-and-diagnostics-test
  (testing "sync-tool-result! uses the live managed-service path and returns diagnostics"
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [worktree (create-temp-worktree)
          file-path (str (io/file worktree "src" "demo.clj"))
          _ (.mkdirs (.getParentFile (io/file file-path)))
          _ (write-file! file-path "(ns demo) ;; warn\n")
          [ctx session-id] (create-runtime-session worktree)
          api (create-ext-api ctx session-id)
          result (sut/sync-tool-result! api
                                        {:worktree-path worktree
                                         :tool-result {:effects [{:path file-path}]}
                                         :config {:command lsp-fixture-command
                                                  :startup-timeout-ms 2000
                                                  :diagnostics-timeout-ms 2000
                                                  :sync-timeout-ms 2000}})
          root (:workspace-root result)
          svc (services/service-in ctx (sut/workspace-key root))
          debug @(:debug-atom svc)
          notifications @(:notifications-atom svc)]
      (try
        (is (= root worktree))
        (is (sut/workspace-initialized? root))
        (is (= [{:path file-path :version 1 :first-open? true :method "textDocument/didOpen"}]
               (:document-syncs result)))
        (is (= [{"message" (str "fixture diagnostic for " file-path)
                 "severity" 2}]
               (get (:diagnostics-by-path result) file-path)))
        (is (some #(and (= :send (:event %))
                        (= "initialize" (get-in % [:payload "method"])))
                  debug)
            (str {:debug debug :notifications notifications}))
        (is (some #(and (= :send (:event %))
                        (= "initialized" (get-in % [:payload "method"])))
                  debug))
        (is (some #(and (= :send (:event %))
                        (= "textDocument/didOpen" (get-in % [:payload "method"])))
                  debug))
        (is (some #(and (= :send (:event %))
                        (= "textDocument/diagnostic" (get-in % [:payload "method"])))
                  debug))
        (is (some #(= "observed" (get-in % [:payload "method"])) notifications))
        (finally
          (when-let [close-fn (:close-fn svc)]
            (future (close-fn)))
          (future (services/stop-service-in! ctx (sut/workspace-key root))))))))

(deftest sync-tool-result-subsequent-sync-uses-didchange-test
  (testing "subsequent syncs for an open document use didChange"
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [worktree (create-temp-worktree)
          file-path (str (io/file worktree "src" "demo.clj"))
          _ (.mkdirs (.getParentFile (io/file file-path)))
          [ctx session-id] (create-runtime-session worktree)
          api (create-ext-api ctx session-id)
          _ (write-file! file-path "(ns demo)\n")
          first-result (sut/sync-tool-result! api
                                              {:worktree-path worktree
                                               :tool-result {:effects [{:path file-path}]}
                                               :config {:command lsp-fixture-command
                                                        :startup-timeout-ms 2000
                                                        :diagnostics-timeout-ms 2000}})
          _ (write-file! file-path "(ns demo) ;; warn\n")
          second-result (sut/sync-tool-result! api
                                               {:worktree-path worktree
                                                :tool-result {:effects [{:path file-path}]}
                                                :config {:command lsp-fixture-command
                                                         :startup-timeout-ms 2000
                                                         :diagnostics-timeout-ms 2000}})
          root (:workspace-root second-result)
          svc (services/service-in ctx (sut/workspace-key root))
          debug @(:debug-atom svc)]
      (try
        (is (= [{:path file-path :version 1 :first-open? true :method "textDocument/didOpen"}]
               (:document-syncs first-result)))
        (is (= [{:path file-path :version 2 :first-open? false :method "textDocument/didChange"}]
               (:document-syncs second-result)))
        (is (some #(= 2 (get-in % [:payload "params" "textDocument" "version"])) debug))
        (is (some #(= "textDocument/didChange" (get-in % [:payload "method"])) debug))
        (is (= [{"message" (str "fixture diagnostic for " file-path)
                 "severity" 2}]
               (get (:diagnostics-by-path second-result) file-path)))
        (finally
          (when-let [close-fn (:close-fn svc)]
            (future (close-fn)))
          (future (services/stop-service-in! ctx (sut/workspace-key root))))))))

(deftest restart-workspace-closes-documents-and-restarts-service-test
  (testing "restart-workspace! sends didClose and clears tracked document state"
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [worktree (create-temp-worktree)
          file-path (str (io/file worktree "src" "demo.clj"))
          _ (.mkdirs (.getParentFile (io/file file-path)))
          _ (write-file! file-path "(ns demo) ;; warn\n")
          [ctx session-id] (create-runtime-session worktree)
          api (create-ext-api ctx session-id)
          _ (sut/sync-tool-result! api
                                   {:worktree-path worktree
                                    :tool-result {:effects [{:path file-path}]}
                                    :config {:command lsp-fixture-command
                                             :startup-timeout-ms 2000
                                             :diagnostics-timeout-ms 2000}})
          root worktree
          svc-before (services/service-in ctx (sut/workspace-key root))
          restarted-root (sut/restart-workspace! api {:worktree-path worktree
                                                      :config {:command lsp-fixture-command}})
          debug-before @(:debug-atom svc-before)
          svc-after (services/service-in ctx (sut/workspace-key root))]
      (try
        (is (= root restarted-root))
        (is (some #(= "textDocument/didClose" (get-in % [:payload "method"])) debug-before))
        (is (nil? (sut/document-state file-path)))
        (is (false? (sut/workspace-initialized? root)))
        (is (= :running (:status svc-after)))
        (is (not= (:pid svc-before) (:pid svc-after)))
        (finally
          (when-let [close-fn (:close-fn svc-after)]
            (future (close-fn)))
          (future (services/stop-service-in! ctx (sut/workspace-key root))))))))

(deftest workspace-status-lines-reflect-live-service-state-test
  (testing "workspace-status-lines reports initialized service and tracked documents"
    (reset! sut/state {:initialized-workspaces #{} :documents {}})
    (let [worktree (create-temp-worktree)
          file-path (str (io/file worktree "src" "demo.clj"))
          _ (.mkdirs (.getParentFile (io/file file-path)))
          _ (write-file! file-path "(ns demo)\n")
          [ctx session-id] (create-runtime-session worktree)
          api (create-ext-api ctx session-id)
          _ (sut/sync-tool-result! api
                                   {:worktree-path worktree
                                    :tool-result {:effects [{:path file-path}]}
                                    :config {:command lsp-fixture-command
                                             :startup-timeout-ms 2000
                                             :diagnostics-timeout-ms 2000}})
          lines (sut/workspace-status-lines api {:worktree-path worktree})]
      (try
        (is (= (str "LSP workspace: " worktree) (first lines)))
        (is (some #(= "Initialized: true" %) lines))
        (is (some #(= "Service status: :running" %) lines))
        (is (some #(str/includes? % file-path) lines))
        (finally
          (when-let [svc (services/service-in ctx (sut/workspace-key worktree))]
            (when-let [close-fn (:close-fn svc)]
              (future (close-fn))))
          (future (services/stop-service-in! ctx (sut/workspace-key worktree))))))))
