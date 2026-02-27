(ns psi.tui.markdown
  "Markdown → ANSI terminal string renderer.

   Parses markdown using commonmark-java and walks the AST to produce
   ANSI-styled terminal output. Designed for rendering LLM responses
   in the psi TUI.

   Public API:
     render-markdown  — parse markdown string, return ANSI-styled string

   Supported elements:
     - Headings (H1–H6)           → bold + color, underline for H1
     - Bold / Italic / Bold+Italic → ANSI bold / italic
     - Inline code                 → dim background
     - Fenced & indented code blocks → indented, dimmed, with language hint
     - Bullet lists                → • bullets with indent
     - Ordered lists               → numbered with indent
     - Block quotes                → │ prefix, dimmed
     - Thematic breaks (---)       → horizontal rule
     - Links                       → text + dimmed URL
     - Images                      → [image: alt] + dimmed URL
     - Soft/hard line breaks       → newlines"
  (:require
   [clojure.string :as str])
  (:import
   [org.commonmark.parser Parser]
   [org.commonmark.node
    Document Paragraph Heading Text SoftLineBreak HardLineBreak
    BulletList OrderedList ListItem
    BlockQuote Code FencedCodeBlock IndentedCodeBlock
    ThematicBreak Emphasis StrongEmphasis Link Image
    HtmlBlock HtmlInline]))

;; ── ANSI escape helpers ─────────────────────────────────────
;; Inline SGR codes — we compose these rather than using charm/style
;; so the renderer has no dependency on charm's render cycle.

(def ^:private sgr-reset    "\u001b[0m")
(def ^:private sgr-bold     "\u001b[1m")
(def ^:private sgr-dim      "\u001b[2m")
(def ^:private sgr-italic   "\u001b[3m")
(def ^:private sgr-underline "\u001b[4m")

;; 256-color foreground: ESC[38;5;Nm
(defn- fg256 [n] (str "\u001b[38;5;" n "m"))

;; Named palette
(def ^:private c-heading   (str sgr-bold (fg256 141)))    ; light purple
(def ^:private c-h1        (str sgr-bold sgr-underline (fg256 141)))
(def ^:private c-code-fg   (fg256 223))                   ; warm tan
(def ^:private c-code-bg   "\u001b[48;5;236m")            ; dark gray bg
(def ^:private c-code-span (str c-code-bg c-code-fg))
(def ^:private c-quote     (str sgr-dim (fg256 245)))     ; gray
(def ^:private c-quote-bar (fg256 240))                   ; darker gray
(def ^:private c-link-url  (str sgr-dim (fg256 75)))      ; blue
(def ^:private c-link-text (str sgr-underline (fg256 75)))
(def ^:private c-bullet    (fg256 245))
(def ^:private c-hr        (fg256 240))
(def ^:private c-lang      (str sgr-dim (fg256 245)))

;; ── AST node children helper ────────────────────────────────

(defn- node-children
  "Return a seq of direct child nodes."
  [node]
  (when node
    (when-let [first-child (.getFirstChild node)]
      (loop [c first-child, acc []]
        (let [acc' (conj acc c)]
          (if-let [n (.getNext c)]
            (recur n acc')
            acc'))))))

;; ── Render context ──────────────────────────────────────────
;; A plain map threaded through rendering to track:
;;   :indent      — current left-margin string (spaces/quote bars)
;;   :list-index  — counter for ordered lists (atom per list)
;;   :inline-styles — stack of ANSI codes for nested inline styles

(defn- make-ctx []
  {:indent ""
   :list-index nil
   :inline-styles []})

;; ── Inline rendering ────────────────────────────────────────
;; Inline nodes (Text, Code, Emphasis, StrongEmphasis, Link, Image,
;; SoftLineBreak, HardLineBreak, HtmlInline) produce string fragments
;; that are concatenated by the parent block node.

(declare render-inline)

(defn- render-inline-children
  "Render all children of an inline-container node."
  [node ctx]
  (str/join (map #(render-inline % ctx) (node-children node))))

(defn- render-inline
  "Render a single inline node to an ANSI string."
  [node ctx]
  (condp instance? node
    Text
    (.getLiteral ^Text node)

    Code
    (str c-code-span " " (.getLiteral ^Code node) " " sgr-reset)

    Emphasis
    (str sgr-italic (render-inline-children node ctx) sgr-reset
         ;; Restore any outer styles
         (str/join (:inline-styles ctx)))

    StrongEmphasis
    (str sgr-bold (render-inline-children node ctx) sgr-reset
         (str/join (:inline-styles ctx)))

    Link
    (let [text (render-inline-children node ctx)
          url  (.getDestination ^Link node)]
      (str c-link-text text sgr-reset
           " " c-link-url "(" url ")" sgr-reset))

    Image
    (let [alt (render-inline-children node ctx)
          url (.getDestination ^Image node)]
      (str c-link-text "[image: " alt "]" sgr-reset
           " " c-link-url "(" url ")" sgr-reset))

    SoftLineBreak
    "\n"

    HardLineBreak
    "\n"

    HtmlInline
    (.getLiteral ^HtmlInline node)

    ;; Fallback — unknown inline
    ""))

;; ── Block rendering ─────────────────────────────────────────
;; Block nodes (Paragraph, Heading, CodeBlock, List, BlockQuote, etc.)
;; produce complete lines (with trailing newline).

(declare render-block)

(defn- render-block-children
  "Render all child blocks, concatenating their output."
  [node ctx]
  (str/join (map #(render-block % ctx) (node-children node))))

(defn- indent-lines
  "Prepend indent string to every line."
  [text indent]
  (if (str/blank? indent)
    text
    (->> (str/split-lines text)
         (map #(str indent %))
         (str/join "\n"))))

(defn- render-block
  "Render a single block node to an ANSI string (with trailing newline)."
  [node ctx]
  (condp instance? node

    Document
    (let [children (node-children node)
          blocks   (map #(render-block % ctx) children)]
      ;; Join blocks with blank line between them, trim trailing whitespace
      (str/trimr (str/join "\n" blocks)))

    Heading
    (let [level (.getLevel ^Heading node)
          hdr   (apply str (repeat level "#"))
          style (if (= 1 level) c-h1 c-heading)
          text  (render-inline-children node ctx)]
      (str (:indent ctx) style hdr " " text sgr-reset "\n"))

    Paragraph
    (let [text (render-inline-children node ctx)]
      ;; Apply indent to each line (inline content may contain soft breaks)
      (str (indent-lines text (:indent ctx)) "\n"))

    FencedCodeBlock
    (let [info    (.getInfo ^FencedCodeBlock node)
          literal (str/trimr (.getLiteral ^FencedCodeBlock node))
          lang    (when-not (str/blank? info) info)
          indent  (:indent ctx)
          lines   (str/split-lines literal)]
      (str indent c-code-bg
           (if lang
             (str c-lang " " lang " " sgr-reset "\n")
             "\n")
           (str/join "\n"
                     (map (fn [line]
                            (str indent c-code-bg c-code-fg "  " line sgr-reset))
                          lines))
           "\n" indent sgr-reset "\n"))

    IndentedCodeBlock
    (let [literal (str/trimr (.getLiteral ^IndentedCodeBlock node))
          indent  (:indent ctx)
          lines   (str/split-lines literal)]
      (str (str/join "\n"
                     (map (fn [line]
                            (str indent c-code-bg c-code-fg "  " line sgr-reset))
                          lines))
           "\n"))

    BulletList
    (let [items (node-children node)]
      (str/join
       (map (fn [item]
              (render-block item (assoc ctx :list-marker :bullet)))
            items)))

    OrderedList
    (let [items  (node-children node)
          start  (.getStartNumber ^OrderedList node)
          idx    (atom (dec start))]
      (str/join
       (map (fn [item]
              (let [n (swap! idx inc)]
                (render-block item (assoc ctx :list-marker n))))
            items)))

    ListItem
    (let [marker  (:list-marker ctx)
          prefix  (if (= :bullet marker)
                    (str c-bullet "  • " sgr-reset)
                    (str c-bullet (format "%2d. " marker) sgr-reset))
          pw      (if (= :bullet marker) 4 4)
          sub-indent (apply str (repeat pw \space))
          child-ctx  (-> ctx
                         (update :indent str sub-indent)
                         (dissoc :list-marker))
          children   (node-children node)
          ;; First child gets the bullet prefix, rest get indent
          first-block (render-block (first children) (assoc child-ctx :indent ""))
          rest-blocks (map #(render-block % child-ctx) (rest children))
          ;; Prepend bullet to first line, indent continuation
          first-lines (str/split-lines first-block)
          rendered-first (str (:indent ctx) prefix (first first-lines)
                              (when (next first-lines)
                                (str "\n" (indent-lines
                                           (str/join "\n" (rest first-lines))
                                           (str (:indent ctx) sub-indent)))))]
      (str rendered-first "\n"
           (when (seq rest-blocks) (str/join rest-blocks))))

    BlockQuote
    (let [bar (str c-quote-bar "│ " sgr-reset)
          child-ctx (-> ctx
                        (update :indent str bar)
                        (update :inline-styles conj c-quote))]
      (str c-quote
           (render-block-children node child-ctx)
           sgr-reset))

    ThematicBreak
    (str (:indent ctx) c-hr (apply str (repeat 40 "─")) sgr-reset "\n")

    HtmlBlock
    (let [literal (str/trimr (.getLiteral ^HtmlBlock node))]
      (str (indent-lines literal (:indent ctx)) "\n"))

    ;; Fallback — render children if any, otherwise empty
    (if-let [children (seq (node-children node))]
      (str/join (map #(render-block % ctx) children))
      "")))

;; ── Public API ──────────────────────────────────────────────

(def ^:private parser
  "Shared commonmark parser instance (thread-safe, stateless)."
  (.build (Parser/builder)))

(defn render-markdown
  "Parse a markdown string and return an ANSI-styled terminal string.

   The output is suitable for display in a terminal that supports
   256-color ANSI escapes. All styles are properly reset so they
   don't bleed into surrounding content."
  [^String markdown-text]
  (when (and markdown-text (not (str/blank? markdown-text)))
    (let [doc (.parse parser markdown-text)]
      (render-block doc (make-ctx)))))
