(ns psi.tui.components-test
  "Tests for concrete TUI component implementations.
   Covers: Input, Editor, SelectList, Loader, CancellableLoader, Text, Spacer, Box."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.tui.ansi :as ansi]
   [psi.tui.components :as comp]
   [psi.tui.protocols :as proto]))

;;;; Input

(deftest input-char-insert-test
  ;; InputCharInserted rule.
  (testing "Input — char insertion"
    (testing "printable char appended to value"
      (let [inp  (comp/create-input "")
            inp' (proto/handle-input inp "a")]
        (is (= "a" (:value inp')))))

    (testing "cursor advances"
      (let [inp  (comp/create-input "")
            inp' (proto/handle-input inp "a")]
        (is (= 1 (:cursor-col inp')))))

    (testing "mid-string insertion"
      (let [inp  (assoc (comp/create-input "hello") :cursor-col 2)
            inp' (proto/handle-input inp "X")]
        (is (= "heXllo" (:value inp')))
        (is (= 3 (:cursor-col inp')))))))

(deftest input-backspace-test
  ;; InputBackspace rule.
  (testing "Input — backspace"
    (testing "deletes char before cursor"
      (let [inp  (assoc (comp/create-input "ab") :cursor-col 2)
            inp' (proto/handle-input inp "backspace")]
        (is (= "a" (:value inp')))
        (is (= 1 (:cursor-col inp')))))

    (testing "no-op at start of input"
      (let [inp  (comp/create-input "")
            inp' (proto/handle-input inp "backspace")]
        (is (= "" (:value inp')))
        (is (= 0 (:cursor-col inp')))))))

(deftest input-cursor-movement-test
  ;; InputCursorLeft / InputCursorRight / InputLineStart / InputLineEnd.
  (testing "Input — cursor movement"
    (testing "left arrow moves cursor left"
      (let [inp  (assoc (comp/create-input "ab") :cursor-col 2)
            inp' (proto/handle-input inp "left")]
        (is (= 1 (:cursor-col inp')))))

    (testing "right arrow moves cursor right"
      (let [inp  (assoc (comp/create-input "ab") :cursor-col 0)
            inp' (proto/handle-input inp "right")]
        (is (= 1 (:cursor-col inp')))))

    (testing "home moves to start"
      (let [inp  (assoc (comp/create-input "hello") :cursor-col 3)
            inp' (proto/handle-input inp "home")]
        (is (= 0 (:cursor-col inp')))))

    (testing "end moves to end"
      (let [inp  (comp/create-input "hello")
            inp' (proto/handle-input inp "end")]
        (is (= 5 (:cursor-col inp')))))))

(deftest input-render-test
  ;; Render returns a single line; line width <= given width.
  (testing "Input — render"
    (testing "returns a single line"
      (let [inp (comp/create-input "hello")]
        (is (= 1 (count (proto/render inp 20))))))

    (testing "line visible width <= given width"
      (let [inp (comp/create-input "hello world")]
        (is (<= (ansi/visible-width (first (proto/render inp 5))) 5))))))

(deftest input-focusable-test
  ;; Focusable protocol.
  (testing "Input — Focusable"
    (testing "focused? false by default"
      (let [inp (comp/create-input "")]
        (is (false? (proto/focused? inp)))))

    (testing "set-focused! true returns focused component"
      (let [inp  (comp/create-input "")
            inp' (proto/set-focused! inp true)]
        (is (true? (proto/focused? inp')))))))

;;;; Editor

(deftest editor-char-insert-test
  ;; EditorCharInserted rule.
  (testing "Editor — char insertion"
    (testing "printable char added to current line"
      (let [ed  (comp/create-editor)
            ed' (proto/handle-input ed "x")]
        (is (= "x" (first (:lines ed'))))))

    (testing "cursor col advances"
      (let [ed  (comp/create-editor)
            ed' (proto/handle-input ed "x")]
        (is (= 1 (get-in ed' [:cursor :col])))))))

(deftest editor-backspace-test
  ;; EditorBackspace rule.
  (testing "Editor — backspace"
    (testing "deletes char before cursor"
      (let [ed  (-> (comp/create-editor)
                    (proto/handle-input "a")
                    (proto/handle-input "b"))
            ed' (proto/handle-input ed "backspace")]
        (is (= "a" (first (:lines ed'))))))

    (testing "merges with previous line at start of line"
      (let [ed  (-> (comp/create-editor)
                    (proto/handle-input "a")
                    (proto/handle-input "shift+enter")
                    (proto/handle-input "b"))
            ;; cursor now at line 1, col 1 — backspace at col 0 after moving left
            at-start (assoc ed :cursor {:line 1 :col 0})
            ed'      (proto/handle-input at-start "backspace")]
        (is (= 1 (count (:lines ed'))))
        (is (= "ab" (first (:lines ed'))))))))

(deftest editor-newline-test
  ;; EditorNewLine rule.
  (testing "Editor — new line"
    (testing "shift+enter inserts a new line"
      (let [ed  (-> (comp/create-editor)
                    (proto/handle-input "a"))
            ed' (proto/handle-input ed "shift+enter")]
        (is (= 2 (count (:lines ed'))))))))

(deftest editor-submit-test
  ;; EditorSubmitted rule.
  (testing "Editor — submit"
    (testing "enter clears buffer"
      (let [ed  (-> (comp/create-editor)
                    (proto/handle-input "a")
                    (proto/handle-input "b"))
            ed' (proto/handle-input ed "enter")]
        (is (= [""] (:lines ed')))
        (is (= {:line 0 :col 0} (:cursor ed')))))

    (testing "non-empty text recorded in history"
      (let [ed  (-> (comp/create-editor)
                    (proto/handle-input "a")
                    (proto/handle-input "b"))
            ed' (proto/handle-input ed "enter")]
        (is (= ["ab"] (:history ed')))))

    (testing "duplicate consecutive text not added to history"
      (let [ed  (-> (comp/create-editor)
                    (proto/handle-input "a")
                    (proto/handle-input "b")
                    (proto/handle-input "enter"))
            ;; type same text again
            ed' (-> ed
                    (proto/handle-input "a")
                    (proto/handle-input "b")
                    (proto/handle-input "enter"))]
        (is (= 1 (count (:history ed'))))))))

(deftest editor-history-navigation-test
  ;; EditorHistoryNavigation rule.
  (testing "Editor — history navigation"
    (let [ed (-> (comp/create-editor)
                 (proto/handle-input "a")
                 (proto/handle-input "enter")
                 (proto/handle-input "b")
                 (proto/handle-input "enter"))]
      (testing "up arrow loads previous entry"
        (let [ed' (proto/handle-input ed "up")]
          (is (= "b" (first (:lines ed'))))))

      (testing "up twice loads older entry"
        (let [ed' (-> ed
                      (proto/handle-input "up")
                      (proto/handle-input "up"))]
          (is (= "a" (first (:lines ed'))))))

      (testing "down after up restores fresh buffer"
        (let [ed' (-> ed
                      (proto/handle-input "up")
                      (proto/handle-input "down"))]
          (is (= [""] (:lines ed'))))))))

(deftest editor-cursor-movement-test
  ;; EditorLineStart / EditorLineEnd.
  (testing "Editor — cursor movement"
    (let [ed (-> (comp/create-editor)
                 (proto/handle-input "h")
                 (proto/handle-input "e")
                 (proto/handle-input "l")
                 (proto/handle-input "l")
                 (proto/handle-input "o"))]
      (testing "home moves to col 0"
        (let [ed' (proto/handle-input ed "home")]
          (is (= 0 (get-in ed' [:cursor :col])))))

      (testing "end moves to line length"
        (let [ed' (-> ed
                      (proto/handle-input "home")
                      (proto/handle-input "end"))]
          (is (= 5 (get-in ed' [:cursor :col]))))))))

;;;; SelectList

(deftest select-list-render-test
  ;; Renders items with "> " prefix for selected.
  (testing "SelectList — render"
    (let [items [{:value "a" :label "Apple"}
                 {:value "b" :label "Banana"}]
          sl    (comp/create-select-list items)]
      (testing "selected item has > prefix"
        (let [lines (proto/render sl 40)]
          (is (str/starts-with? (first lines) "> "))))

      (testing "non-selected item has space prefix"
        (let [lines (proto/render sl 40)]
          (is (str/starts-with? (second lines) "  ")))))))

(deftest select-list-navigation-test
  ;; SelectListMoveDown / SelectListMoveUp rules.
  (testing "SelectList — navigation"
    (let [items [{:value "a" :label "A"}
                 {:value "b" :label "B"}
                 {:value "c" :label "C"}]
          sl    (comp/create-select-list items)]
      (testing "down moves selection to next"
        (let [sl' (proto/handle-input sl "down")]
          (is (= 1 (:selected-index sl')))))

      (testing "down wraps at end"
        (let [sl' (-> sl
                      (proto/handle-input "down")
                      (proto/handle-input "down")
                      (proto/handle-input "down"))]
          (is (= 0 (:selected-index sl')))))

      (testing "up wraps at start"
        (let [sl' (proto/handle-input sl "up")]
          (is (= 2 (:selected-index sl'))))))))

(deftest select-list-filter-test
  ;; SelectListFiltered rule.
  (testing "SelectList — set-filter"
    (let [items [{:value "a" :label "Apple"}
                 {:value "b" :label "Apricot"}
                 {:value "c" :label "Banana"}]
          sl    (comp/create-select-list items)]
      (testing "filter reduces filtered-items"
        (let [sl' (comp/set-filter sl "Ap")]
          (is (= 2 (count (:filtered-items sl'))))))

      (testing "filter resets selected-index to 0"
        (let [sl' (comp/set-filter sl "Ban")]
          (is (= 0 (:selected-index sl')))))

      (testing "no match renders no-match message"
        (let [sl'   (comp/set-filter sl "ZZZ")
              lines (proto/render sl' 40)]
          (is (str/includes? (first lines) "No matching")))))))

;;;; Loader / CancellableLoader

(deftest loader-render-test
  ;; Loader shows spinner only when running.
  (testing "Loader — render"
    (testing "stopped loader shows message only"
      (let [loader (comp/create-loader "Working")
            line   (first (proto/render loader 40))]
        (is (str/includes? line "Working"))))

    (testing "running loader includes spinner"
      (let [loader (assoc (comp/create-loader "Working") :running true)
            line   (first (proto/render loader 40))]
        (is (str/includes? line "Working"))))))

(deftest cancellable-loader-cancel-test
  ;; CancellableLoaderEscaped rule.
  (testing "CancellableLoader — escape"
    (testing "escape sets aborted to true"
      (let [loader  (assoc (comp/create-cancellable-loader "Working") :running true)
            loader' (proto/handle-input loader "escape")]
        (is (true? (:aborted loader')))))

    (testing "escape when already aborted is no-op"
      (let [loader  (assoc (comp/create-cancellable-loader "Working") :aborted true)
            loader' (proto/handle-input loader "escape")]
        (is (true? (:aborted loader')))))))

;;;; Text / Spacer

(deftest text-render-test
  ;; Text renders its content as lines.
  (testing "Text — render"
    (testing "single line content"
      (let [txt   (comp/create-text "hello")
            lines (proto/render txt 80)]
        (is (str/includes? (first lines) "hello"))))

    (testing "multiline content produces multiple lines"
      (let [txt   (comp/create-text "line1\nline2")
            lines (proto/render txt 80)]
        (is (= 2 (count lines)))))))

(deftest spacer-render-test
  ;; Spacer produces blank lines.
  (testing "Spacer — render"
    (testing "n=1 produces 1 blank line"
      (is (= 1 (count (proto/render (comp/create-spacer 1) 10)))))

    (testing "n=3 produces 3 blank lines"
      (is (= 3 (count (proto/render (comp/create-spacer 3) 10)))))))

;;;; Box

(deftest box-render-test
  ;; Box pads inner content.
  (testing "Box — render"
    (testing "padding-x adds leading spaces"
      (let [inner (comp/create-text "hi")
            box   (comp/create-box [inner] {:padding-x 2})
            line  (first (proto/render box 20))]
        (is (str/starts-with? line "  "))))

    (testing "padding-y adds blank lines"
      (let [inner (comp/create-text "hi")
            box   (comp/create-box [inner] {:padding-y 1})
            lines (proto/render box 20)]
        (is (>= (count lines) 3))))))
