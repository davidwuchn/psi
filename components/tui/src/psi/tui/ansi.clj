(ns psi.tui.ansi
  "ANSI/VT escape-sequence utilities.

   All functions operate on plain Clojure strings that may contain ANSI SGR
   codes, OSC 8 hyperlinks, APC sequences, etc.

   visible-width       — printable column count, ignoring all escapes
   strip-ansi          — remove all escape sequences from a string
   truncate-to-width   — cut at N visible columns, append ellipsis, reset codes
   word-wrap           — wrap a (possibly ANSI-styled) string at N columns
   reset-line          — append SGR reset + OSC 8 reset to a line
   find-marker         — locate cursor marker position in a line vector
   strip-marker        — remove cursor marker from a line vector
   move-cursor-to      — produce ANSI cursor-move sequence

   Rules implemented:
     VisibleWidthIgnoresAnsi
     TruncateToWidthPreservesAnsi
     WrapTextWithAnsi
     LineResetAfterRender (partial, the reset string itself)
     CursorMarkerExtracted (find-marker / strip-marker)"
  (:require
   [clojure.string :as str]))

;;;; Constants

(def ^:const reset-seq  "\u001b[0m")
(def ^:const osc8-reset "\u001b]8;;\u0007")
(def ^:const line-reset (str reset-seq osc8-reset))

;;; Regex matching all common ANSI / VT escape sequences.
;;  CSI: ESC [ ... (final byte A-~)
;;  OSC: ESC ] ... BEL or ST
;;  APC: ESC _ ... ST
;;  Two-char: ESC + any char
(def ^:private ansi-pattern
  (re-pattern
   (str "(?:\u001b\\[[0-9;?<>!]*[A-Za-z~]"
        "|\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)"
        "|\u001b_[^\u001b]*\u001b\\\\"
        "|\u001b.)")))

;;; Same pattern as a string for substring matching inside truncation.
(def ^:private esc-seq-str
  (str "(?:\u001b\\[[0-9;?<>!]*[A-Za-z~]"
       "|\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)"
       "|\u001b_[^\u001b]*\u001b\\\\"
       "|\u001b.)"))

;;;; Core helpers

(defn strip-ansi
  "Remove all ANSI escape sequences from s. Returns plain text."
  [s]
  (str/replace s ansi-pattern ""))

(defn visible-width
  "Return the printable column count of s (ignores all ANSI escapes).
   Each code-point is assumed to occupy 1 column."
  [s]
  (count (strip-ansi s)))

(defn reset-line
  "Append a full SGR reset and OSC 8 hyperlink reset to line.
   Implements LineResetAfterRender."
  [line]
  (str line line-reset))

(defn move-cursor-to
  "Return an ANSI sequence to move the hardware cursor to {:row r :col c}."
  [{:keys [row col]}]
  (format "\u001b[%d;%dH" (inc row) (inc col)))

;;;; Truncation

(defn truncate-to-width
  "Truncate s to at most width visible columns.
   Appends ellipsis (default \"...\") when truncation occurs, and closes
   any open ANSI codes with reset-line.
   Implements TruncateToWidthPreservesAnsi."
  ([s width]
   (truncate-to-width s width "\u2026"))
  ([s width ellipsis]
   (let [plain  (strip-ansi s)
         pcols  (count plain)
         ecols  (count ellipsis)]
     (if (<= pcols width)
       s
       ;; Walk the raw string accumulating visible columns.
       (let [target (- width ecols)
             esc-re (re-pattern (str "^" esc-seq-str))
             result (StringBuilder.)]
         (loop [remaining (seq s)
                vis-cols   0]
           (if (or (nil? remaining) (>= vis-cols target))
             (str result ellipsis line-reset)
             (let [c (first remaining)]
               (if (= c \u001b)
                 ;; Escape sequence: copy without counting columns
                 (let [raw-sub (apply str remaining)
                       m       (re-find esc-re raw-sub)
                       esc-len (if m (count m) 2)]
                   (.append result (subs raw-sub 0 esc-len))
                   (recur (drop esc-len remaining) vis-cols))
                 ;; Printable character: count 1 column
                 (do
                   (.append result c)
                   (recur (rest remaining) (inc vis-cols))))))))))))

;;;; Word wrap

(defn word-wrap
  "Wrap s at width visible columns. Returns a vector of strings.
   ANSI sequences are stripped for simplicity; full ANSI-preserving
   wrap is deferred per the spec.
   Implements WrapTextWithAnsi."
  [s width]
  (let [plain (strip-ansi s)
        words (re-seq (re-pattern "[^\\s]+|\\s+") plain)]
    (if (or (nil? words) (empty? words))
      [""]
      (loop [remaining    words
             current-line (StringBuilder.)
             current-cols 0
             lines        []]
        (if (nil? remaining)
          (conj lines (str current-line))
          (let [token       (first remaining)
                token-cols  (count token)
                whitespace? (re-matches (re-pattern "\\s+") token)]
            (cond
              ;; Whitespace at start of a line: skip it
              (and whitespace? (zero? current-cols))
              (recur (next remaining) current-line current-cols lines)

              ;; Token fits on current line
              (<= (+ current-cols token-cols) width)
              (do
                (.append current-line token)
                (recur (next remaining) current-line (+ current-cols token-cols) lines))

              ;; Whitespace that overflows: flush and skip
              whitespace?
              (recur (next remaining)
                     (StringBuilder.)
                     0
                     (conj lines (str current-line)))

              ;; Word that overflows: flush current line first
              (pos? current-cols)
              (recur remaining (StringBuilder.) 0 (conj lines (str current-line)))

              ;; Word alone wider than column: hard-break it
              :else
              (loop [token-chars (seq token)
                     sb          current-line
                     cols        current-cols
                     acc         lines]
                (if (nil? token-chars)
                  (recur (next remaining) sb cols acc)
                  (if (< cols width)
                    (do
                      (.append sb (first token-chars))
                      (recur (next token-chars) sb (inc cols) acc))
                    (recur token-chars
                           (StringBuilder.)
                           0
                           (conj acc (str sb)))))))))))))

;;;; Cursor marker

(defn find-marker
  "Locate cursor-marker in lines (a vector of strings).
   Returns a map with :row and :col keys (zero-indexed), or nil.
   Implements CursorMarkerExtracted."
  [lines marker]
  (reduce
   (fn [_ [row line]]
     (let [plain-line   (strip-ansi line)
           plain-marker (strip-ansi marker)
           idx          (str/index-of plain-line plain-marker)]
       (when idx
         (reduced {:row row :col idx}))))
   nil
   (map-indexed vector lines)))

(defn strip-marker
  "Remove cursor-marker from all lines in the vector.
   Returns the updated line vector.
   Implements CursorMarkerExtracted (strip step)."
  [lines marker]
  (mapv #(str/replace % marker "") lines))
