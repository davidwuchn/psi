(ns psi.tui.core-test
  "Tests for the TUI core — differential renderer, focus & overlay manager.
   Uses VirtualTerminal (Nullable pattern) throughout."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.tui.components :as comp]
   [psi.tui.core :as tui]
   [psi.tui.protocols :as proto]
   [psi.tui.terminal :as term]))

;;;; Helpers

(defn- make-ctx
  "Build a fresh isolated context with a VirtualTerminal (80×24)."
  [children]
  (let [vterm (term/create-virtual-terminal {:cols 80 :rows 24})]
    (tui/create-context vterm children)))

(defn- tui-state [ctx]
  @(:tui-atom ctx))

;;;; Tests

(deftest create-context-test
  ;; A freshly created context has sane defaults.
  (testing "create-context"
    (testing "starts with no previous lines"
      (let [ctx (make-ctx [])]
        (is (empty? (:previous-lines (tui-state ctx))))))

    (testing "render is not yet requested"
      (let [ctx (make-ctx [])]
        (is (false? (:render-requested (tui-state ctx))))))

    (testing "focused is nil"
      (let [ctx (make-ctx [])]
        (is (nil? (:focused (tui-state ctx))))))))

(deftest request-render-test
  ;; request-render-in! sets the flag.
  (testing "request-render-in!"
    (testing "marks render-requested true"
      (let [ctx (make-ctx [])]
        (tui/request-render-in! ctx)
        (is (true? (:render-requested (tui-state ctx))))))))

(deftest tick-first-render-test
  ;; After requesting a render, tick writes to the terminal.
  (testing "first render"
    (testing "writes content to the virtual terminal"
      (let [txt (comp/create-text "hello")
            ctx (make-ctx [txt])]
        (tui/request-render-in! ctx)
        (tui/tick-in! ctx)
        (let [output (term/written-output (:terminal (tui-state ctx)))]
          (is (some #(str/includes? % "hello") output)))))

    (testing "clears render-requested flag"
      (let [txt (comp/create-text "hi")
            ctx (make-ctx [txt])]
        (tui/request-render-in! ctx)
        (tui/tick-in! ctx)
        (is (false? (:render-requested (tui-state ctx))))))

    (testing "records previous-lines"
      (let [txt (comp/create-text "line1")
            ctx (make-ctx [txt])]
        (tui/request-render-in! ctx)
        (tui/tick-in! ctx)
        (is (seq (:previous-lines (tui-state ctx))))))))

(deftest differential-render-test
  ;; Second tick with same content produces no-changes path.
  (testing "differential render"
    (testing "no-changes: render-requested cleared, previous-lines unchanged"
      (let [txt (comp/create-text "same")
            ctx (make-ctx [txt])]
        (tui/request-render-in! ctx)
        (tui/tick-in! ctx)
        (let [prev (:previous-lines (tui-state ctx))]
          (tui/request-render-in! ctx)
          (tui/tick-in! ctx)
          (is (= prev (:previous-lines (tui-state ctx)))))))))

(deftest set-focus-test
  ;; set-focus-in! updates :focused in the TUI map.
  (testing "set-focus-in!"
    (testing "sets focused component"
      (let [inp (comp/create-input "")
            ctx (make-ctx [inp])]
        (tui/set-focus-in! ctx inp)
        (is (some? (:focused (tui-state ctx))))))

    (testing "Focusable component has focused? = true after focus"
      (let [inp (comp/create-input "")
            ctx (make-ctx [inp])]
        (tui/set-focus-in! ctx inp)
        (let [focused (:focused (tui-state ctx))]
          (is (proto/focused? focused)))))))

(deftest handle-input-routing-test
  ;; handle-input-in! routes keystrokes to the focused component.
  (testing "handle-input-in!"
    (testing "updates focused Input value on printable char"
      (let [inp (comp/create-input "")
            ctx (make-ctx [inp])]
        (tui/set-focus-in! ctx inp)
        (tui/handle-input-in! ctx "a")
        (let [focused (:focused (tui-state ctx))]
          (is (= "a" (:value focused))))))

    (testing "debug key does not update state"
      (let [inp (comp/create-input "")
            ctx (make-ctx [inp])]
        (tui/set-focus-in! ctx inp)
        (let [before (:value (:focused (tui-state ctx)))]
          (tui/handle-input-in! ctx "shift+ctrl+d")
          (is (= before (:value (:focused (tui-state ctx))))))))))

(deftest overlay-test
  ;; Show and hide overlay entries.
  (testing "show-overlay-in!"
    (testing "adds overlay to list"
      (let [ctx   (make-ctx [])
            popup (comp/create-text "popup")]
        (tui/show-overlay-in! ctx popup {})
        (is (= 1 (count (:overlays (tui-state ctx)))))))

    (testing "sets focused to overlay component"
      (let [ctx   (make-ctx [])
            popup (comp/create-text "popup")]
        (tui/show-overlay-in! ctx popup {})
        (is (= popup (:focused (tui-state ctx)))))))

  (testing "hide-overlay-in!"
    (testing "removes overlay entry"
      (let [ctx   (make-ctx [])
            popup (comp/create-text "popup")
            entry (tui/show-overlay-in! ctx popup {})]
        (tui/hide-overlay-in! ctx entry)
        (is (empty? (:overlays (tui-state ctx))))))))

(deftest two-contexts-are-independent-test
  ;; Nullable pattern isolation: two contexts share no state.
  (testing "context isolation"
    (let [ctx-a (make-ctx [(comp/create-text "A")])
          ctx-b (make-ctx [(comp/create-text "B")])]
      (tui/request-render-in! ctx-a)
      (tui/tick-in! ctx-a)
      (is (seq (:previous-lines (tui-state ctx-a))))
      (is (empty? (:previous-lines (tui-state ctx-b)))))))
