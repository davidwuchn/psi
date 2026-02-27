(ns psi.tui.markdown-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.tui.markdown :as md]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- strip-ansi
  "Remove ANSI escape sequences for assertion clarity."
  [s]
  (str/replace s #"\u001b\[[0-9;]*m|\u001b\]8;;[^\u0007]*\u0007" ""))

(defn- contains-ansi?
  "True if string contains at least one ANSI escape."
  [s]
  (boolean (re-find #"\u001b\[" s)))

;; ── Nil / blank ─────────────────────────────────────────────

(deftest nil-and-blank-test
  (testing "nil input returns nil"
    (is (nil? (md/render-markdown nil))))
  (testing "blank input returns nil"
    (is (nil? (md/render-markdown "")))
    (is (nil? (md/render-markdown "   ")))))

;; ── Headings ────────────────────────────────────────────────

(deftest headings-test
  (testing "H1 renders with styling"
    (let [result (md/render-markdown "# Hello World")]
      (is (contains-ansi? result))
      (is (str/includes? (strip-ansi result) "# Hello World"))))

  (testing "H2 renders with styling"
    (let [result (md/render-markdown "## Section")]
      (is (str/includes? (strip-ansi result) "## Section"))))

  (testing "H3 renders"
    (let [result (md/render-markdown "### Sub-section")]
      (is (str/includes? (strip-ansi result) "### Sub-section")))))

;; ── Inline styles ───────────────────────────────────────────

(deftest inline-styles-test
  (testing "bold text gets ANSI bold"
    (let [result (md/render-markdown "Some **bold** text")]
      (is (contains-ansi? result))
      (is (str/includes? (strip-ansi result) "bold"))))

  (testing "italic text gets ANSI italic"
    (let [result (md/render-markdown "Some *italic* text")]
      (is (contains-ansi? result))
      (is (str/includes? (strip-ansi result) "italic"))))

  (testing "inline code gets styling"
    (let [result (md/render-markdown "Use `foo-bar` here")]
      (is (contains-ansi? result))
      (is (str/includes? (strip-ansi result) "foo-bar")))))

;; ── Code blocks ─────────────────────────────────────────────

(deftest code-block-test
  (testing "fenced code block renders with language hint"
    (let [result (md/render-markdown "```clojure\n(+ 1 2)\n```")]
      (is (contains-ansi? result))
      (is (str/includes? (strip-ansi result) "(+ 1 2)"))
      (is (str/includes? (strip-ansi result) "clojure"))))

  (testing "fenced code block without language"
    (let [result (md/render-markdown "```\nplain code\n```")]
      (is (str/includes? (strip-ansi result) "plain code"))))

  (testing "multi-line code block preserves lines"
    (let [result (md/render-markdown "```\nline1\nline2\nline3\n```")
          plain  (strip-ansi result)]
      (is (str/includes? plain "line1"))
      (is (str/includes? plain "line2"))
      (is (str/includes? plain "line3")))))

;; ── Lists ───────────────────────────────────────────────────

(deftest bullet-list-test
  (testing "bullet list renders with bullets"
    (let [result (md/render-markdown "- item one\n- item two\n- item three")
          plain  (strip-ansi result)]
      (is (str/includes? plain "item one"))
      (is (str/includes? plain "item two"))
      (is (str/includes? plain "item three"))
      (is (str/includes? plain "•")))))

(deftest ordered-list-test
  (testing "ordered list renders with numbers"
    (let [result (md/render-markdown "1. first\n2. second\n3. third")
          plain  (strip-ansi result)]
      (is (str/includes? plain "first"))
      (is (str/includes? plain "second"))
      (is (str/includes? plain "1."))
      (is (str/includes? plain "2.")))))

;; ── Block quotes ────────────────────────────────────────────

(deftest blockquote-test
  (testing "blockquote renders with bar prefix"
    (let [result (md/render-markdown "> This is a quote")
          plain  (strip-ansi result)]
      (is (str/includes? plain "│"))
      (is (str/includes? plain "This is a quote")))))

;; ── Thematic breaks ────────────────────────────────────────

(deftest thematic-break-test
  (testing "--- renders as horizontal rule"
    (let [result (md/render-markdown "above\n\n---\n\nbelow")
          plain  (strip-ansi result)]
      (is (str/includes? plain "───"))
      (is (str/includes? plain "above"))
      (is (str/includes? plain "below")))))

;; ── Links ───────────────────────────────────────────────────

(deftest link-test
  (testing "link renders text and URL"
    (let [result (md/render-markdown "[click here](http://example.com)")
          plain  (strip-ansi result)]
      (is (str/includes? plain "click here"))
      (is (str/includes? plain "http://example.com")))))

;; ── Complex document ────────────────────────────────────────

(deftest complex-document-test
  (testing "full markdown document renders all elements"
    (let [md-text (str "# Title\n\n"
                       "Some **bold** and *italic* text with `code`.\n\n"
                       "## Code Example\n\n"
                       "```clojure\n(defn hello [name]\n  (println \"Hello\" name))\n```\n\n"
                       "- item 1\n- item 2\n\n"
                       "> A wise quote\n\n"
                       "---\n\n"
                       "[link](http://example.com)")
          result  (md/render-markdown md-text)
          plain   (strip-ansi result)]
      (is (some? result))
      (is (str/includes? plain "Title"))
      (is (str/includes? plain "bold"))
      (is (str/includes? plain "italic"))
      (is (str/includes? plain "code"))
      (is (str/includes? plain "defn hello"))
      (is (str/includes? plain "item 1"))
      (is (str/includes? plain "A wise quote"))
      (is (str/includes? plain "───"))
      (is (str/includes? plain "link")))))

;; ── Streaming partial markdown ──────────────────────────────

(deftest partial-markdown-test
  (testing "incomplete markdown still renders (streaming use case)"
    ;; During streaming, text may be incomplete — should not throw
    (is (some? (md/render-markdown "# Heading\n\nSome text so far")))
    (is (some? (md/render-markdown "```clojure\n(+ 1")))
    (is (some? (md/render-markdown "- item\n- ite"))))

  (testing "dangling list markers render without throwing"
    (doseq [snippet ["-" "-\n" "- \n" "- item\n-" "1." "1.\n" "1. \n"]]
      (is (some? (md/render-markdown snippet))))))

;; ── Width-aware wrapping ─────────────────────────────────────

(deftest paragraph-wrapping-test
  (testing "paragraph wraps at specified width"
    (let [text "This is a long paragraph that should be wrapped to fit within a narrow terminal width."
          result (md/render-markdown text 30)
          plain  (strip-ansi result)
          lines  (str/split-lines plain)]
      (is (> (count lines) 1)
          "long paragraph should wrap to multiple lines")
      (is (every? #(<= (count %) 30) lines)
          "all lines should fit within width")))

  (testing "code blocks are NOT wrapped"
    (let [long-code "```\nthis-is-a-very-long-line-of-code-that-should-not-be-wrapped-by-the-renderer\n```"
          result (md/render-markdown long-code 30)
          plain  (strip-ansi result)]
      (is (some #(> (count (str/trim %)) 30)
                (str/split-lines plain))
          "code block lines should not be wrapped")))

  (testing "nil width behaves like no wrapping"
    (let [text   "Short text."
          with-w (strip-ansi (md/render-markdown text 80))
          no-w   (strip-ansi (md/render-markdown text))]
      (is (= with-w no-w)))))

;; ── Plain text passthrough ──────────────────────────────────

(deftest plain-text-test
  (testing "plain text without markdown renders as-is"
    (let [result (md/render-markdown "Just plain text.")]
      (is (str/includes? (strip-ansi result) "Just plain text.")))))
