(ns psi.tui.ansi
  "ANSI/VT escape-sequence utilities.

   All functions operate on plain Clojure strings that may contain ANSI SGR
   codes, OSC 8 hyperlinks, APC sequences, etc.

   visible-width       — printable column count, ignoring all escapes
   strip-ansi          — remove all escape sequences from a string
   truncate-to-width   — cut at N visible columns, append ellipsis, reset codes
   word-wrap           — wrap a (possibly ANSI-styled) string at N columns
   reset-line          — append SGR reset to a line
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
(def ^:const line-reset reset-seq)

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

(defn char-width
  "Return the display width of a character in terminal columns.
   CJK and fullwidth characters occupy 2 columns; most others occupy 1."
  ^long [^Character c]
  (let [cp (int c)]
    (if (or
         ;; Hangul Jamo
         (<= 0x1100 cp 0x115F)
         ;; Misc wide punctuation
         (<= 0x2329 cp 0x232A)
         ;; CJK Radicals .. CJK Symbols and Punctuation
         (<= 0x2E80 cp 0x303E)
         ;; Hiragana .. CJK Compatibility
         (<= 0x3040 cp 0x33FF)
         ;; CJK Extension A
         (<= 0x3400 cp 0x4DBF)
         ;; CJK Unified Ideographs
         (<= 0x4E00 cp 0x9FFF)
         ;; Yi Syllables .. Yi Radicals
         (<= 0xA000 cp 0xA4CF)
         ;; Hangul Syllables
         (<= 0xAC00 cp 0xD7AF)
         ;; CJK Compatibility Ideographs
         (<= 0xF900 cp 0xFAFF)
         ;; Vertical Forms
         (<= 0xFE10 cp 0xFE19)
         ;; CJK Compatibility Forms .. Small Form Variants
         (<= 0xFE30 cp 0xFE6F)
         ;; Fullwidth Forms (excluding halfwidth)
         (<= 0xFF01 cp 0xFF60)
         ;; Fullwidth Signs
         (<= 0xFFE0 cp 0xFFE6))
      2
      1)))

(defn display-width
  "Return the total display width of a plain string (no ANSI) in terminal columns."
  ^long [^String s]
  (loop [i 0 w 0]
    (if (>= i (.length s))
      w
      (recur (inc i) (+ w (char-width (.charAt s i)))))))

(defn visible-width
  "Return the printable column count of s (ignores all ANSI escapes).
   CJK and fullwidth characters count as 2 columns."
  [s]
  (display-width (strip-ansi s)))

(defn reset-line
  "Append a full SGR reset to line.
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
         pcols  (display-width plain)
         ecols  (display-width ellipsis)]
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
                 ;; Printable character: count by display width
                 (let [cw (char-width c)]
                   (if (> (+ vis-cols cw) target)
                     ;; Wide char won't fit — stop here
                     (str result ellipsis line-reset)
                     (do
                       (.append result c)
                       (recur (rest remaining) (+ vis-cols cw))))))))))))))

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
                token-cols  (display-width token)
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
              (let [[new-sb new-cols new-acc]
                    (loop [token-chars (seq token)
                           sb          current-line
                           cols        current-cols
                           acc         lines]
                      (if (nil? token-chars)
                        [sb cols acc]
                        (let [cw (char-width (first token-chars))]
                          (cond
                            ;; Char fits on current line
                            (<= (+ cols cw) width)
                            (do
                              (.append sb (first token-chars))
                              (recur (next token-chars) sb (+ cols cw) acc))
                            ;; Single char wider than line — force it alone
                            (zero? cols)
                            (do
                              (.append sb (first token-chars))
                              (recur (next token-chars) (StringBuilder.) 0 (conj acc (str sb))))
                            ;; Normal overflow — flush line, retry char
                            :else
                            (recur token-chars (StringBuilder.) 0 (conj acc (str sb)))))))]
                (recur (next remaining) new-sb new-cols new-acc)))))))))

;;;; ANSI-preserving word wrap

(defn word-wrap-ansi
  "Wrap an ANSI-styled string at `width` visible columns.
   Returns a vector of strings with ANSI codes preserved.
   Breaks on whitespace boundaries; words wider than
   width are hard-broken."
  [s width]
  (if (or (nil? s) (str/blank? s))
    [""]
    (let [esc-re (re-pattern (str "^" esc-seq-str))
          chars  (seq s)]
      (if (nil? chars)
        [""]
        (loop [remaining  chars
               line-buf   (StringBuilder.)
               line-cols  0
               break-buf  nil
               break-cols 0
               after-buf  (StringBuilder.)
               after-cols 0
               lines      []]
          (if (nil? remaining)
            (conj lines (str line-buf))
            (let [c (first remaining)]
              (if (= c \u001b)
                ;; ANSI escape: copy verbatim, zero width
                (let [raw (apply str remaining)
                      m   (re-find esc-re raw)
                      esc (if m m (subs raw 0 (min 2 (count raw))))
                      n   (count esc)]
                  (.append line-buf esc)
                  (.append after-buf esc)
                  (recur (seq (drop n remaining))
                         line-buf line-cols
                         break-buf break-cols
                         after-buf after-cols
                         lines))
                ;; Printable character
                (let [cw (char-width c)]
                  (if (Character/isWhitespace c)
                    ;; Whitespace: potential break point
                    (if (> (+ line-cols cw) width)
                      ;; Overflow on space: wrap
                      (recur (next remaining)
                             (StringBuilder.) 0
                             nil 0
                             (StringBuilder.) 0
                             (conj lines (str line-buf)))
                      ;; Space fits: mark break
                      (do
                        (.append line-buf c)
                        (recur (next remaining)
                               line-buf (+ line-cols cw)
                               (str line-buf) (+ line-cols cw)
                               (StringBuilder.) 0
                               lines)))
                    ;; Non-whitespace
                    (if (> (+ line-cols cw) width)
                      ;; Overflow on word char
                      (if break-buf
                        ;; Break at last whitespace
                        (let [new-buf (StringBuilder.
                                       (str after-buf c))]
                          (recur (next remaining)
                                 new-buf (+ after-cols cw)
                                 nil 0
                                 (StringBuilder.) 0
                                 (conj lines
                                       (str/trimr break-buf))))
                        ;; No break point: hard break
                        (let [new-buf (doto (StringBuilder.)
                                        (.append c))]
                          (recur (next remaining)
                                 new-buf cw
                                 nil 0
                                 (StringBuilder.) 0
                                 (conj lines (str line-buf)))))
                      ;; Fits
                      (do
                        (.append line-buf c)
                        (.append after-buf c)
                        (recur (next remaining)
                               line-buf (+ line-cols cw)
                               break-buf break-cols
                               after-buf (+ after-cols cw)
                               lines)))))))))))))

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
         (reduced {:row row :col (display-width (subs plain-line 0 idx))}))))
   nil
   (map-indexed vector lines)))

(defn strip-marker
  "Remove cursor-marker from all lines in the vector.
   Returns the updated line vector.
   Implements CursorMarkerExtracted (strip step)."
  [lines marker]
  (mapv #(str/replace % marker "") lines))
