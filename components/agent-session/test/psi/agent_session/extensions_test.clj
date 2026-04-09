(ns psi.agent-session.extensions-test
  "Tests for the extension registry, loading, tool wrapping, and introspection."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.test-support :as test-support]))

;; ── Registry isolation ──────────────────────────────────────────────────────

(deftest registry-isolation-test
  (testing "two registries are independent"
    (let [reg-a (ext/create-registry)
          reg-b (ext/create-registry)]
      (ext/register-extension-in! reg-a "/ext/a")
      (is (= 1 (ext/extension-count-in reg-a)))
      (is (= 0 (ext/extension-count-in reg-b))))))

;; ── Extension registration ──────────────────────────────────────────────────

(deftest register-extension-test
  (testing "register-extension-in! adds path"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/foo")
      (is (= ["/ext/foo"] (ext/extensions-in reg)))))

  (testing "registering same path twice is idempotent"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/foo")
      (ext/register-extension-in! reg "/ext/foo")
      (is (= 1 (ext/extension-count-in reg))))))

;; ── Handler registration ─────────────────────────────────────────────────────

(deftest handler-registration-test
  (testing "register-handler-in! adds handler"
    (let [reg (ext/create-registry)
          h   (fn [_] nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "my_event" h)
      (is (= 1 (ext/handler-count-in reg)))))

  (testing "multiple handlers for same event accumulate"
    (let [reg (ext/create-registry)
          h1  (fn [_] nil)
          h2  (fn [_] nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "my_event" h1)
      (ext/register-handler-in! reg "/ext/a" "my_event" h2)
      (is (= 2 (ext/handler-count-in reg))))))

;; ── Tool and command registration ──────────────────────────────────────────

(deftest tool-command-registration-test
  (testing "tool names tracked"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-tool-in! reg "/ext/a" {:name "my-tool" :label "My Tool"})
      (is (contains? (ext/tool-names-in reg) "my-tool"))))

  (testing "rejects non-canonical tool names"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid tool name"
           (ext/register-tool-in! reg "/ext/a" {:name "my_tool" :label "My Tool"}))))
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid tool name"
           (ext/register-tool-in! reg "/ext/a" {:name "MyTool" :label "My Tool"})))))

  (testing "command names tracked"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-command-in! reg "/ext/a" {:name "/do-thing"})
      (is (contains? (ext/command-names-in reg) "/do-thing")))))

;; ── Event dispatch ──────────────────────────────────────────────────────────

(deftest dispatch-broadcast-test
  (testing "all handlers fire (broadcast)"
    (let [reg    (ext/create-registry)
          fired  (atom [])
          h1     (fn [ev] (swap! fired conj [:h1 (:value ev)]) nil)
          h2     (fn [ev] (swap! fired conj [:h2 (:value ev)]) nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "test_event" h1)
      (ext/register-handler-in! reg "/ext/b" "test_event" h2)
      (ext/dispatch-in reg "test_event" {:value 42})
      (is (= [[:h1 42] [:h2 42]] @fired))))

  (testing "registration order preserved"
    (let [reg   (ext/create-registry)
          order (atom [])
          h1    (fn [_] (swap! order conj 1) nil)
          h2    (fn [_] (swap! order conj 2) nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "e" h1)
      (ext/register-handler-in! reg "/ext/b" "e" h2)
      (ext/dispatch-in reg "e" {})
      (is (= [1 2] @order)))))

(deftest dispatch-cancel-test
  (testing "cancel: true in result sets :cancelled?"
    (let [reg (ext/create-registry)
          h   (fn [_] {:cancel true})]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "before_switch" h)
      (let [result (ext/dispatch-in reg "before_switch" {})]
        (is (true? (:cancelled? result))))))

  (testing "cancel: true does NOT suppress remaining handlers"
    (let [reg       (ext/create-registry)
          fired     (atom [])
          h-cancel  (fn [_] (swap! fired conj :cancel-handler) {:cancel true})
          h-after   (fn [_] (swap! fired conj :after-handler) nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "e" h-cancel)
      (ext/register-handler-in! reg "/ext/b" "e" h-after)
      (ext/dispatch-in reg "e" {})
      (is (= [:cancel-handler :after-handler] @fired))))

  (testing "no cancel when no handler returns cancel"
    (let [reg (ext/create-registry)
          h   (fn [_] {:ok true})]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "e" h)
      (let [result (ext/dispatch-in reg "e" {})]
        (is (false? (:cancelled? result)))))))

(deftest dispatch-override-test
  (testing ":result in handler return is captured as :override"
    (let [reg    (ext/create-registry)
          result {:summary "custom" :first-kept-entry-id "e1" :tokens-before 1000}
          h      (fn [_] {:result result})]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "session_before_compact" h)
      (let [dispatch-result (ext/dispatch-in reg "session_before_compact" {})]
        (is (= result (:override dispatch-result))))))

  (testing ":compaction in handler return is captured as :override"
    (let [reg    (ext/create-registry)
          result {:summary "custom" :first-kept-entry-id "e2" :tokens-before 2000}
          h      (fn [_] {:compaction result})]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "session_before_compact" h)
      (let [dispatch-result (ext/dispatch-in reg "session_before_compact" {})]
        (is (= result (:override dispatch-result))))))

  (testing "last override wins when multiple handlers return override payloads"
    (let [reg    (ext/create-registry)
          r1     {:summary "first" :first-kept-entry-id "e1" :tokens-before 100}
          r2     {:summary "second" :first-kept-entry-id "e2" :tokens-before 200}]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "session_before_compact" (fn [_] {:result r1}))
      (ext/register-handler-in! reg "/ext/b" "session_before_compact" (fn [_] {:compaction r2}))
      (let [dispatch-result (ext/dispatch-in reg "session_before_compact" {})]
        (is (= r2 (:override dispatch-result))))))

  (testing "no override when handler returns nil"
    (let [reg (ext/create-registry)
          h   (fn [_] nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "e" h)
      (let [result (ext/dispatch-in reg "e" {})]
        (is (nil? (:override result)))))))

(deftest dispatch-no-handlers-test
  (testing "dispatch with no handlers returns safe defaults"
    (let [reg    (ext/create-registry)
          result (ext/dispatch-in reg "no_handlers_event" {})]
      (is (false? (:cancelled? result)))
      (is (nil? (:override result)))
      (is (= [] (:results result))))))

(deftest dispatch-exception-test
  (testing "handler exception is captured and does not abort dispatch"
    (let [reg    (ext/create-registry)
          fired  (atom false)
          h-bad  (fn [_] (throw (Exception. "boom")))
          h-ok   (fn [_] (reset! fired true) nil)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-handler-in! reg "/ext/a" "e" h-bad)
      (ext/register-handler-in! reg "/ext/b" "e" h-ok)
      (ext/dispatch-in reg "e" {})
      (is (true? @fired)))))

;; ── Unregister all ──────────────────────────────────────────────────────────

(deftest unregister-all-test
  (testing "unregister-all-in! clears registry"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "e" (fn [_] nil))
      (ext/unregister-all-in! reg)
      (is (= 0 (ext/extension-count-in reg)))
      (is (= 0 (ext/handler-count-in reg))))))

;; ── Summary ─────────────────────────────────────────────────────────────────

(deftest summary-test
  (testing "summary-in returns expected keys"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "e" (fn [_] nil))
      (ext/register-tool-in! reg "/ext/a" {:name "t1"})
      (let [s (ext/summary-in reg)]
        (is (= 1 (:extension-count s)))
        (is (= 1 (:handler-count s)))
        (is (contains? (:tool-names s) "t1"))
        (is (contains? (:handler-events s) "e"))))))

;; ── Flag management ─────────────────────────────────────────────────────────

(deftest flag-registration-test
  (testing "register-flag-in! with default sets initial value"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-flag-in! reg "/ext/a" {:name "verbose" :type :boolean :default true})
      (is (= true (ext/get-flag-in reg "verbose")))))

  (testing "set-flag-in! updates value"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-flag-in! reg "/ext/a" {:name "verbose" :type :boolean :default false})
      (ext/set-flag-in! reg "verbose" true)
      (is (= true (ext/get-flag-in reg "verbose")))))

  (testing "flag without default has nil value"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-flag-in! reg "/ext/a" {:name "mode" :type :string})
      (is (nil? (ext/get-flag-in reg "mode")))))

  (testing "flag names tracked"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-flag-in! reg "/ext/a" {:name "f1" :type :boolean})
      (is (contains? (ext/flag-names-in reg) "f1"))))

  (testing "all-flag-values-in returns complete map"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-flag-in! reg "/ext/a" {:name "f1" :type :boolean :default true})
      (ext/register-flag-in! reg "/ext/a" {:name "f2" :type :string :default "x"})
      (is (= {"f1" true "f2" "x"} (ext/all-flag-values-in reg))))))

;; ── Event bus ───────────────────────────────────────────────────────────────

(deftest event-bus-test
  (testing "bus-emit fires subscribers"
    (let [reg     (ext/create-registry)
          received (atom nil)]
      (ext/bus-on-in! reg "test-channel" (fn [data] (reset! received data)))
      (ext/bus-emit-in! reg "test-channel" {:value 42})
      (is (= {:value 42} @received))))

  (testing "bus-on-in! returns unsubscribe fn"
    (let [reg     (ext/create-registry)
          count-a (atom 0)
          unsub   (ext/bus-on-in! reg "ch" (fn [_] (swap! count-a inc)))]
      (ext/bus-emit-in! reg "ch" {})
      (is (= 1 @count-a))
      (unsub)
      (ext/bus-emit-in! reg "ch" {})
      (is (= 1 @count-a))))

  (testing "multiple subscribers receive same event"
    (let [reg    (ext/create-registry)
          fired  (atom [])]
      (ext/bus-on-in! reg "ch" (fn [d] (swap! fired conj [:a d])))
      (ext/bus-on-in! reg "ch" (fn [d] (swap! fired conj [:b d])))
      (ext/bus-emit-in! reg "ch" :x)
      (is (= [[:a :x] [:b :x]] @fired)))))

;; ── Tool wrapping ───────────────────────────────────────────────────────────

(deftest tool-wrapping-test
  (testing "wrap-tool-executor passes through when no handlers"
    (let [reg        (ext/create-registry)
          execute-fn (fn [tool-name _args] {:content (str tool-name " ok") :is-error false})
          wrapped    (ext/wrap-tool-executor reg execute-fn)]
      (is (= {:content "read ok" :is-error false}
             (wrapped "read" {})))))

  (testing "wrap-tool-executor remains an extension-local compatibility wrapper"
    (let [reg        (ext/create-registry)
          calls      (atom [])
          execute-fn (fn [tool-name args]
                       (swap! calls conj {:tool-name tool-name :args args})
                       {:content "ok" :is-error false})
          wrapped    (ext/wrap-tool-executor reg execute-fn)]
      (is (= {:content "ok" :is-error false}
             (wrapped "echo" {"x" 1})))
      (is (= [{:tool-name "echo" :args {"x" 1}}] @calls))))

  (testing "tool_call handler can block execution"
    (let [reg        (ext/create-registry)
          execute-fn (fn [_tool-name _args]
                       (throw (Exception. "should not be called")))
          _          (ext/register-extension-in! reg "/ext/a")
          _          (ext/register-handler-in! reg "/ext/a" "tool_call"
                                               (fn [_] {:block true :reason "blocked!"}))
          wrapped    (ext/wrap-tool-executor reg execute-fn)]
      (is (= {:content "blocked!" :is-error true}
             (wrapped "bash" {"command" "rm -rf /"})))))

  (testing "tool_result handler can modify result"
    (let [reg        (ext/create-registry)
          execute-fn (fn [_tool-name _args] {:content "original" :is-error false})
          _          (ext/register-extension-in! reg "/ext/a")
          _          (ext/register-handler-in! reg "/ext/a" "tool_result"
                                               (fn [_] {:content "modified"}))
          wrapped    (ext/wrap-tool-executor reg execute-fn)]
      (is (= {:content "modified" :is-error false}
             (wrapped "read" {"path" "test.txt"})))))

  (testing "tool_result handler can modify is-error"
    (let [reg        (ext/create-registry)
          execute-fn (fn [_tool-name _args] {:content "result" :is-error false})
          _          (ext/register-extension-in! reg "/ext/a")
          _          (ext/register-handler-in! reg "/ext/a" "tool_result"
                                               (fn [_] {:is-error true}))
          wrapped    (ext/wrap-tool-executor reg execute-fn)]
      (is (= {:content "result" :is-error true}
             (wrapped "read" {"path" "test.txt"}))))))

;; ── Introspection: handler event names ──────────────────────────────────────

(deftest handler-event-names-test
  (testing "handler-event-names-in returns sorted set of event names"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "session_switch" (fn [_] nil))
      (ext/register-handler-in! reg "/ext/a" "tool_call" (fn [_] nil))
      (ext/register-handler-in! reg "/ext/a" "model_select" (fn [_] nil))
      (is (= #{"model_select" "session_switch" "tool_call"}
             (ext/handler-event-names-in reg))))))

;; ── Introspection: all tools/commands/flags ─────────────────────────────────

(deftest all-tools-in-test
  (testing "all-tools-in returns tools with extension-path"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-tool-in! reg "/ext/a" {:name "my-tool" :label "T" :description "d" :parameters {:type "object"}})
      (let [tools (ext/all-tools-in reg)]
        (is (= 1 (count tools)))
        (is (= "my-tool" (:name (first tools))))
        (is (= "/ext/a" (:extension-path (first tools))))
        (is (= :extension (:source (first tools))))
        (is (= {:type "object"} (:parameters (first tools)))))))

  (testing "first registration per name wins"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (ext/register-tool-in! reg "/ext/a" {:name "t" :label "A"})
      (ext/register-tool-in! reg "/ext/b" {:name "t" :label "B"})
      (let [tools (ext/all-tools-in reg)]
        (is (= 1 (count tools)))
        (is (= "A" (:label (first tools))))))))

(deftest all-commands-in-test
  (testing "all-commands-in returns commands with extension-path"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-command-in! reg "/ext/a" {:name "hello" :description "say hi"})
      (let [cmds (ext/all-commands-in reg)]
        (is (= 1 (count cmds)))
        (is (= "hello" (:name (first cmds))))
        (is (= "/ext/a" (:extension-path (first cmds))))))))

(deftest all-flags-in-test
  (testing "all-flags-in includes current values"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-flag-in! reg "/ext/a" {:name "verbose" :type :boolean :default true})
      (let [flags (ext/all-flags-in reg)]
        (is (= 1 (count flags)))
        (is (= "verbose" (:name (first flags))))
        (is (= true (:current-value (first flags))))))))

;; ── Introspection: extension details ────────────────────────────────────────

(deftest extension-detail-test
  (testing "extension-detail-in returns detail map"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "tool_call" (fn [_] nil))
      (ext/register-handler-in! reg "/ext/a" "tool_result" (fn [_] nil))
      (ext/register-tool-in! reg "/ext/a" {:name "my-tool"})
      (ext/register-command-in! reg "/ext/a" {:name "cmd"})
      (ext/register-flag-in! reg "/ext/a" {:name "f1" :type :boolean})
      (let [d (ext/extension-detail-in reg "/ext/a")]
        (is (= "/ext/a" (:path d)))
        (is (= #{"tool_call" "tool_result"} (:handler-names d)))
        (is (= 2 (:handler-count d)))
        (is (= #{"my-tool"} (:tool-names d)))
        (is (= 1 (:tool-count d)))
        (is (= #{"cmd"} (:command-names d)))
        (is (= 1 (:command-count d)))
        (is (= #{"f1"} (:flag-names d)))
        (is (= 1 (:flag-count d))))))

  (testing "extension-detail-in returns nil for unknown path"
    (let [reg (ext/create-registry)]
      (is (nil? (ext/extension-detail-in reg "/ext/unknown")))))

  (testing "extension-details-in returns all extensions"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (let [details (ext/extension-details-in reg)]
        (is (= 2 (count details)))
        (is (= ["/ext/a" "/ext/b"] (mapv :path details)))))))

;; ── Get command/tool by name ────────────────────────────────────────────────

(deftest get-command-in-test
  (testing "returns command by name"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-command-in! reg "/ext/a" {:name "hello" :handler identity})
      (is (= "hello" (:name (ext/get-command-in reg "hello"))))))

  (testing "returns nil for unknown command"
    (let [reg (ext/create-registry)]
      (is (nil? (ext/get-command-in reg "nope"))))))

(deftest get-tool-in-test
  (testing "returns tool by name"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-tool-in! reg "/ext/a" {:name "my-tool" :label "T"})
      (let [tool (ext/get-tool-in reg "my-tool")]
        (is (= "my-tool" (:name tool)))
        (is (= {:type "object"} (:parameters tool))))))

  (testing "returns nil for unknown tool"
    (let [reg (ext/create-registry)]
      (is (nil? (ext/get-tool-in reg "nope"))))))

;; ── Extension API (factory invocation) ──────────────────────────────────────

(deftest extension-api-registration-test
  (testing "API :on registers handlers"
    (let [reg (ext/create-registry)
          _   (ext/register-extension-in! reg "/ext/test")
          api (ext/create-extension-api reg "/ext/test" {})]
      ((:on api) "session_switch" (fn [_] nil))
      (is (= 1 (ext/handler-count-in reg)))))

  (testing "API :register-tool registers tools"
    (let [reg (ext/create-registry)
          _   (ext/register-extension-in! reg "/ext/test")
          api (ext/create-extension-api reg "/ext/test" {})]
      ((:register-tool api) {:name "ext-tool" :label "ET" :description "test"})
      (is (contains? (ext/tool-names-in reg) "ext-tool"))))

  (testing "API :register-command registers commands"
    (let [reg (ext/create-registry)
          _   (ext/register-extension-in! reg "/ext/test")
          api (ext/create-extension-api reg "/ext/test" {})]
      ((:register-command api) "greet" {:handler (fn [_] nil) :description "Say hi"})
      (is (contains? (ext/command-names-in reg) "greet"))))

  (testing "API :register-flag with default"
    (let [reg (ext/create-registry)
          _   (ext/register-extension-in! reg "/ext/test")
          api (ext/create-extension-api reg "/ext/test" {})]
      ((:register-flag api) "debug" {:type :boolean :default false})
      (is (= false ((:get-flag api) "debug")))))

  (testing "API :query delegates to runtime query fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          runtime-fns {:query-fn (fn [q] {:echo q})}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= {:echo [:x]} ((:query api) [:x])))))

  (testing "API :list-services delegates to runtime query fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          services    [{:psi.service/key [:lsp "/repo"]
                        :psi.service/status :running}]
          runtime-fns {:query-fn (fn [q]
                                   (is (= [{:psi.service/services [:psi.service/key
                                                                   :psi.service/status
                                                                   :psi.service/command
                                                                   :psi.service/cwd
                                                                   :psi.service/transport
                                                                   :psi.service/ext-path]}]
                                          q))
                                   {:psi.service/services services})}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= services ((:list-services api))))))

  (testing "API :ui-type delegates to runtime ui-type fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          runtime-fns {:ui-type-fn (fn [] :emacs)}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= :emacs (:ui-type api)))))

  (testing "API :mutate delegates to runtime mutate fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          runtime-fns {:mutate-fn (fn [op params] {:op op :params params})}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= {:op 'psi.extension/test :params {:a 1 :ext-path "/ext/test"}}
             ((:mutate api) 'psi.extension/test {:a 1})))
      (is (= {:op 'psi.other/test :params {:a 1}}
             ((:mutate api) 'psi.other/test {:a 1})))
      (is (= {:op 'psi.extension/test :params {:a 1 :ext-path "/custom"}}
             ((:mutate api) 'psi.extension/test {:a 1 :ext-path "/custom"})))
      (is (= {:op 'psi.extension.workflow/create :params {:type :agent :ext-path "/ext/test"}}
             ((:mutate api) 'psi.extension.workflow/create {:type :agent})))))

  (testing "API session lifecycle helpers delegate to runtime mutate fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          runtime-fns {:mutate-fn (fn [op params] {:op op :params params})}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= {:op 'psi.extension/create-session
              :params {:session-name "feature"
                       :worktree-path "/repo/feature"
                       :system-prompt "prompt"}}
             ((:create-session api) {:session-name "feature"
                                     :worktree-path "/repo/feature"
                                     :system-prompt "prompt"})))
      (is (= {:op 'psi.extension/switch-session
              :params {:session-id "s1"}}
             ((:switch-session api) "s1")))))

  (testing "extension UI dispatch includes ext-id so extension-origin events are authorized"
    (let [[ctx session-id] (test-support/make-session-ctx {})
          ext-path         "/ext/test"
          _                (ext/register-extension-in! (:extension-registry ctx) ext-path)
          ui               (#'ext-rt/extension-ui-context ctx session-id (fn [] {:ui-type :emacs}) ext-path)]
      ((:set-widget ui) "w1" :below-editor ["hello"])
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/ui-set-widget (:event-type entry)))
        (is (= :extension (:origin entry)))
        (is (= ext-path (:ext-id entry)))
        (is (not (:blocked? entry))))))

  (testing "API :get-api-key delegates to runtime get-api-key fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          runtime-fns {:get-api-key-fn (fn [provider] (str "key-for-" provider))}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= "key-for-openai" ((:get-api-key api) "openai")))))

  (testing "API prompt contribution helpers delegate to runtime"
    (let [reg   (ext/create-registry)
          _     (ext/register-extension-in! reg "/ext/test")
          calls (atom [])
          runtime-fns {:mutate-fn (fn [op params]
                                    (swap! calls conj {:op op :params params})
                                    {:ok true})
                       :query-fn  (fn [_]
                                    {:psi.extension/prompt-contributions
                                     [{:id "a" :ext-path "/ext/test" :content "x"}
                                      {:id "b" :ext-path "/ext/other" :content "y"}]})}
          api   (ext/create-extension-api reg "/ext/test" runtime-fns)]
      ((:register-prompt-contribution api) "a" {:content "x"})
      ((:update-prompt-contribution api) "a" {:enabled false})
      ((:unregister-prompt-contribution api) "a")
      (is (= [{:id "a" :ext-path "/ext/test" :content "x"
               :section nil :priority nil :enabled nil
               :created-at nil :updated-at nil}]
             ((:list-prompt-contributions api))))
      (is (= ['psi.extension/register-prompt-contribution
              'psi.extension/update-prompt-contribution
              'psi.extension/unregister-prompt-contribution]
             (mapv :op @calls))))))

;; ── Extension loading from file ─────────────────────────────────────────────

(deftest load-extension-from-file-test
  (testing "loads a .clj extension file and invokes init"
    (let [tmp-dir  (io/file (System/getProperty "java.io.tmpdir")
                            (str "psi-ext-test-" (System/nanoTime)))
          _        (.mkdirs tmp-dir)
          ext-file (io/file tmp-dir "hello_ext.clj")
          reg      (ext/create-registry)]
      (try
        ;; Write a minimal extension file
        (spit ext-file
              "(ns psi.test-extensions.hello-ext)

(defn init [api]
  ((:register-command api) \"hello\" {:description \"Say hello\"
                                     :handler (fn [args] (println \"Hello\" args))})
  ((:register-flag api) \"verbose\" {:type :boolean :default true})
  ((:on api) \"session_switch\" (fn [ev] (println \"switched!\" ev))))")
        (let [result (ext/load-extension-in! reg (.getAbsolutePath ext-file) {})]
          (is (nil? (:error result)))
          (is (= (.getAbsolutePath ext-file) (:extension result)))
          ;; Verify registrations took effect
          (is (= 1 (ext/extension-count-in reg)))
          (is (contains? (ext/command-names-in reg) "hello"))
          (is (contains? (ext/flag-names-in reg) "verbose"))
          (is (= true (ext/get-flag-in reg "verbose")))
          (is (= 1 (ext/handler-count-in reg))))
        (finally
          (.delete ext-file)
          (.delete tmp-dir))))))

(deftest load-extension-missing-file-test
  (testing "returns error for missing file"
    (let [reg    (ext/create-registry)
          result (ext/load-extension-in! reg "/nonexistent/ext.clj" {})]
      (is (some? (:error result)))
      (is (nil? (:extension result))))))

;; ── Extension discovery ─────────────────────────────────────────────────────

(deftest discover-extension-paths-test
  (testing "discovers .clj files in a directory"
    (let [tmp-dir  (io/file (System/getProperty "java.io.tmpdir")
                            (str "psi-discover-" (System/nanoTime)))
          ext-dir  (io/file tmp-dir ".psi" "extensions")
          ext-file (io/file ext-dir "my_ext.clj")]
      (try
        (.mkdirs ext-dir)
        (spit ext-file "(ns test-ext)")
        (let [paths (ext/discover-extension-paths [] (.getAbsolutePath tmp-dir))]
          (is (some #(str/ends-with? % "my_ext.clj") paths)))
        (finally
          (.delete ext-file)
          (.delete ext-dir)
          (.delete (io/file tmp-dir ".psi"))
          (.delete tmp-dir)))))

  (testing "discovers extension.clj in subdirectories"
    (let [tmp-dir  (io/file (System/getProperty "java.io.tmpdir")
                            (str "psi-discover-sub-" (System/nanoTime)))
          ext-dir  (io/file tmp-dir ".psi" "extensions" "my-ext")
          ext-file (io/file ext-dir "extension.clj")]
      (try
        (.mkdirs ext-dir)
        (spit ext-file "(ns test-ext-sub)")
        (let [paths (ext/discover-extension-paths [] (.getAbsolutePath tmp-dir))]
          (is (some #(str/ends-with? % "extension.clj") paths)))
        (finally
          (.delete ext-file)
          (.delete ext-dir)
          (.delete (io/file tmp-dir ".psi" "extensions"))
          (.delete (io/file tmp-dir ".psi"))
          (.delete tmp-dir)))))

  (testing "explicit path included"
    (let [tmp-file (io/file (System/getProperty "java.io.tmpdir")
                            (str "explicit-ext-" (System/nanoTime) ".clj"))]
      (try
        (spit tmp-file "(ns explicit)")
        (let [paths (ext/discover-extension-paths [(.getAbsolutePath tmp-file)]
                                                  "/nonexistent")]
          (is (some #(= (.getAbsolutePath tmp-file) %) paths)))
        (finally
          (.delete tmp-file))))))

;; ── Shortcut registration ───────────────────────────────────────────────────

(deftest shortcut-registration-test
  (testing "register-shortcut-in! adds shortcut"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-shortcut-in! reg "/ext/a" {:key "ctrl+k" :handler (fn [] nil)})
      (let [d (ext/extension-detail-in reg "/ext/a")]
        (is (= 1 (:shortcut-count d)))))))

;; ── EQL introspection ───────────────────────────────────────────────────────

(deftest eql-extension-introspection-test
  (testing "resolvers expose extension data via EQL"
    (let [;; Need a full session context for resolver tests
          reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "tool_call" (fn [_] nil))
      (ext/register-tool-in! reg "/ext/a" {:name "ext-read" :label "R" :description "ext read"})
      (ext/register-command-in! reg "/ext/a" {:name "greet" :description "Say hi"})
      (ext/register-flag-in! reg "/ext/a" {:name "debug" :type :boolean :default false})
      ;; Test the inspection functions that resolvers will call
      (is (= ["/ext/a"] (vec (ext/extensions-in reg))))
      (is (= 1 (ext/handler-count-in reg)))
      (is (= ["tool_call"] (vec (ext/handler-event-names-in reg))))
      (is (= 1 (count (ext/all-tools-in reg))))
      (is (= 1 (count (ext/all-commands-in reg))))
      (is (= 1 (count (ext/all-flags-in reg))))
      (is (= false (:current-value (first (ext/all-flags-in reg)))))
      (is (= 1 (count (ext/extension-details-in reg)))))))
