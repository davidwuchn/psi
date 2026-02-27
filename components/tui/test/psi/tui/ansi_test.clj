(ns psi.tui.ansi-test
  "Tests for ANSI/VT escape sequence utilities.
   Covers: visible-width, truncate-to-width, word-wrap, find-marker, strip-marker."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.tui.ansi :as ansi])
  (:import
   [org.jline.utils AttributedString]))

;;;; visible-width

(deftest visible-width-plain-test
  ;; Plain strings: width equals character count.
  (testing "visible-width — plain strings"
    (testing "empty string"
      (is (= 0 (ansi/visible-width ""))))
    (testing "ascii string"
      (is (= 5 (ansi/visible-width "hello"))
          "five printable chars = width 5"))))

(deftest visible-width-ignores-ansi-test
  ;; VisibleWidthIgnoresAnsi rule: escape sequences not counted.
  (testing "visible-width — with ANSI SGR"
    (testing "bold + reset does not add columns"
      (is (= 5 (ansi/visible-width "\u001b[1mhello\u001b[0m"))))
    (testing "OSC 8 hyperlink does not add columns"
      (is (= 4 (ansi/visible-width "\u001b]8;;http://x\u0007link\u001b]8;;\u0007"))))))

;;;; strip-ansi

(deftest strip-ansi-test
  ;; strip-ansi removes all sequences.
  (testing "strip-ansi"
    (testing "plain string unchanged"
      (is (= "hello" (ansi/strip-ansi "hello"))))
    (testing "SGR codes stripped"
      (is (= "hello" (ansi/strip-ansi "\u001b[1;32mhello\u001b[0m"))))
    (testing "CSI sequence stripped"
      (is (= "hi" (ansi/strip-ansi "\u001b[2Jhi"))))))

;;;; reset-line

(deftest reset-line-test
  ;; reset-line appends SGR reset.
  (testing "reset-line"
    (testing "appends reset sequence"
      (let [result (ansi/reset-line "text")]
        (is (str/starts-with? result "text"))
        (is (str/includes? result "\u001b[0m"))))))

(deftest reset-line-jline-width-test
  ;; Regression: reset suffix must not add visible columns in JLine.
  (testing "reset-line does not change JLine display width"
    (let [s (ansi/reset-line "text")
          a (AttributedString/fromAnsi s)]
      (is (= 4 (.columnLength a))))))

;;;; truncate-to-width

(deftest truncate-to-width-short-string-test
  ;; Short string: no truncation.
  (testing "truncate-to-width — short string"
    (testing "shorter than width returned unchanged"
      (is (= "hi" (ansi/truncate-to-width "hi" 10))))))

(deftest truncate-to-width-exact-width-test
  ;; Exact width: no truncation.
  (testing "truncate-to-width — exact width"
    (testing "string equal to width returned unchanged"
      (is (= "hello" (ansi/truncate-to-width "hello" 5))))))

(deftest truncate-to-width-long-string-test
  ;; TruncateToWidthPreservesAnsi rule.
  (testing "truncate-to-width — long string"
    (testing "result visible width <= target width"
      (let [result (ansi/truncate-to-width "hello world" 7)]
        (is (<= (ansi/visible-width result) 7))))

    (testing "default ellipsis appended"
      (let [result (ansi/truncate-to-width "hello world" 7)]
        (is (str/includes? result "…"))))

    (testing "custom ellipsis respected"
      (let [result (ansi/truncate-to-width "hello world" 7 "...")]
        (is (str/includes? result "..."))))))

(deftest truncate-to-width-ansi-string-test
  ;; ANSI sequences do not consume visible columns.
  (testing "truncate-to-width — ANSI string"
    (testing "does not truncate visible text incorrectly"
      (let [s      "\u001b[1mhello world\u001b[0m"
            result (ansi/truncate-to-width s 7)]
        (is (<= (ansi/visible-width result) 7))))))

;;;; word-wrap

(deftest word-wrap-empty-test
  ;; Empty input.
  (testing "word-wrap — empty string"
    (testing "returns single empty line"
      (is (= [""] (ansi/word-wrap "" 10))))))

(deftest word-wrap-short-test
  ;; Short line: no wrapping.
  (testing "word-wrap — short line"
    (testing "single word within width returned as-is"
      (is (= ["hello"] (ansi/word-wrap "hello" 10))))))

(deftest word-wrap-wraps-test
  ;; WrapTextWithAnsi rule: each wrapped line <= width.
  (testing "word-wrap — wrapping"
    (testing "all result lines fit within width"
      (let [text   "one two three four five six seven eight"
            width  15
            lines  (ansi/word-wrap text width)]
        (is (every? #(<= (count %) width) lines))))

    (testing "all words appear in result"
      (let [text   "one two three"
            result (str/join " " (ansi/word-wrap text 6))]
        (is (str/includes? result "one"))
        (is (str/includes? result "two"))
        (is (str/includes? result "three"))))))

;;;; CJK / fullwidth character support

(deftest char-width-test
  (testing "char-width"
    (testing "ASCII character is width 1"
      (is (= 1 (ansi/char-width \A))))
    (testing "CJK ideograph is width 2"
      (is (= 2 (ansi/char-width \刀))))
    (testing "Hiragana is width 2"
      (is (= 2 (ansi/char-width \あ))))
    (testing "Fullwidth Latin is width 2"
      (is (= 2 (ansi/char-width \Ａ))))))

(deftest visible-width-cjk-test
  (testing "visible-width — CJK characters"
    (testing "single CJK char counts as 2"
      (is (= 2 (ansi/visible-width "刀"))))
    (testing "mixed ASCII and CJK"
      ;; "hi刀" = 2 + 2 = 4
      (is (= 4 (ansi/visible-width "hi刀"))))
    (testing "CJK with ANSI escapes"
      (is (= 2 (ansi/visible-width "\u001b[1m刀\u001b[0m"))))))

(deftest truncate-to-width-cjk-test
  (testing "truncate-to-width — CJK"
    (testing "CJK string within width not truncated"
      (is (= "刀" (ansi/truncate-to-width "刀" 5))))
    (testing "CJK string truncated at column boundary"
      (let [result (ansi/truncate-to-width "刀人ψ" 4)]
        (is (<= (ansi/visible-width result) 4))))))

(deftest word-wrap-cjk-test
  (testing "word-wrap — CJK"
    (testing "CJK words wrapped respecting display width"
      (let [lines (ansi/word-wrap "刀人" 3)]
        ;; "刀" is 2 cols, "人" is 2 cols, width 3 can't fit both
        (is (every? #(<= (ansi/visible-width %) 3) lines))))))

;;;; ANSI-preserving word wrap

(deftest word-wrap-ansi-empty-test
  (testing "empty/nil input returns single empty line"
    (is (= [""] (ansi/word-wrap-ansi "" 10)))
    (is (= [""] (ansi/word-wrap-ansi nil 10)))))

(deftest word-wrap-ansi-short-test
  (testing "short plain text stays on one line"
    (is (= ["hello"] (ansi/word-wrap-ansi "hello" 10)))))

(deftest word-wrap-ansi-plain-wrap-test
  (testing "plain text wraps at word boundaries"
    (let [lines (ansi/word-wrap-ansi "one two three" 8)]
      (is (= ["one two" "three"] lines)))))

(deftest word-wrap-ansi-preserves-ansi-test
  (testing "ANSI codes are preserved across wrap"
    (let [bold  "\u001b[1m"
          reset "\u001b[0m"
          s     (str bold "hello world" reset)
          lines (ansi/word-wrap-ansi s 7)]
      ;; Should wrap into two lines
      (is (= 2 (count lines)))
      ;; First line should contain the bold code
      (is (str/includes? (first lines) bold))
      ;; Content should be present
      (is (str/includes? (ansi/strip-ansi
                          (str/join " " lines))
                         "hello"))
      (is (str/includes? (ansi/strip-ansi
                          (str/join " " lines))
                         "world")))))

(deftest word-wrap-ansi-width-respected-test
  (testing "all lines fit within width"
    (let [s "the quick brown fox jumps over the lazy dog"
          w 15
          lines (ansi/word-wrap-ansi s w)]
      (is (every? #(<= (ansi/visible-width %) w) lines)))))

(deftest word-wrap-ansi-hard-break-test
  (testing "word wider than width is hard-broken"
    (let [lines (ansi/word-wrap-ansi "abcdefghij" 4)]
      (is (every? #(<= (ansi/visible-width %) 4) lines))
      (is (= "abcdefghij"
             (str/join (map ansi/strip-ansi lines)))))))

;;;; find-marker / strip-marker

(deftest find-marker-test
  ;; CursorMarkerExtracted: find-marker locates the cursor marker.
  (let [marker "CURSOR"]
    (testing "find-marker"
      (testing "returns nil when marker absent"
        (is (nil? (ansi/find-marker ["hello" "world"] marker))))

      (testing "returns row+col when marker present"
        (let [result (ansi/find-marker ["hello" "woCURSORrld"] marker)]
          (is (= {:row 1 :col 2} result))))

      (testing "finds marker on first line"
        (let [result (ansi/find-marker ["CURSORhello"] marker)]
          (is (= {:row 0 :col 0} result))))

      (testing "col is display column not char index after CJK"
        (let [result (ansi/find-marker ["刀CURSOR"] marker)]
          ;; 刀 is 2 display columns, so col should be 2 not 1
          (is (= {:row 0 :col 2} result)))))))

(deftest strip-marker-test
  ;; strip-marker removes all occurrences.
  (let [marker "CURSOR"]
    (testing "strip-marker"
      (testing "removes marker from the target line"
        (let [result (ansi/strip-marker ["hello" "woCURSORrld"] marker)]
          (is (= ["hello" "world"] result))))

      (testing "no-op when marker absent"
        (let [lines ["hello" "world"]]
          (is (= lines (ansi/strip-marker lines marker))))))))
