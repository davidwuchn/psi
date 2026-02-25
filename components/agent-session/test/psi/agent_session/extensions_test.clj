(ns psi.agent-session.extensions-test
  "Tests for the extension registry and event dispatch."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.extensions :as ext]))

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
        (is (contains? (:tool-names s) "t1"))))))
