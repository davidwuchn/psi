(ns psi.tui.components
  "Concrete TUI component implementations.

   Each variant from the Allium spec is a Clojure record implementing the
   Component protocol (and Focusable where appropriate).

   Implemented variants:
     Text, TruncatedText, Spacer    — display-only
     Box, Container                 — layout wrappers
     Input                          — single-line editable field
     Editor                         — multi-line editor with history
     Loader, CancellableLoader      — progress indicators
     SelectList                     — filtered, navigable list
     Markdown                       — rendered markdown block

   Kill-ring, undo stack, autocomplete, image rendering, and word-wrap
   internals are deferred per the spec's open questions / deferred sections."
  (:require
   [clojure.string :as str]
   [psi.tui.ansi :as ansi]
   [psi.tui.protocols :as proto]))

;;;; Config

(def config
  {:cursor-marker          "\u001b_pi:c\u0007"
   :debug-key              "shift+ctrl+d"
   :editor-max-history     100
   :editor-max-visible-pct 0.30
   :editor-min-visible     5
   :paste-line-threshold   10
   :paste-char-threshold   1000
   :autocomplete-max       5
   :select-list-no-match   "  No matching commands"})

;;;; Key matching helpers

(defn matches-key?
  "True when raw matches key-id (exact string match for now).
   Full VT/Kitty matching is deferred (KeyMatchesBinding rule)."
  [raw key-id]
  (= raw key-id))

(defn printable?
  "True when raw is a single printable character (IsPrintable rule)."
  [raw]
  (and (= 1 (count raw))
       (let [c (int (.charAt ^String raw 0))]
         (and (>= c 32) (not= c 127)))))

;;;; Text

(defrecord Text [content padding-x padding-y invalidated]
  proto/Component
  (render [_this width]
    (let [pad-x  (or padding-x 0)
          pad-y  (or padding-y 0)
          lines  (mapv (fn [line]
                         (let [padded (str (apply str (repeat pad-x \space))
                                          line
                                          (apply str (repeat pad-x \space)))]
                           (ansi/truncate-to-width padded width)))
                       (str/split-lines content))
          blank  (apply str (repeat width \space))
          top    (repeat pad-y blank)
          bottom (repeat pad-y blank)]
      (vec (concat top lines bottom))))

  (handle-input [this _raw] this)
  (invalidate   [this] (assoc this :invalidated true)))

(defn create-text
  "Create a Text component."
  ([content] (create-text content {}))
  ([content {:keys [padding-x padding-y] :or {padding-x 0 padding-y 0}}]
   (->Text content padding-x padding-y false)))

;;;; TruncatedText

(defrecord TruncatedText [content padding-x padding-y invalidated]
  proto/Component
  (render [_this width]
    (let [pad-x (or padding-x 0)
          inner (- width (* 2 pad-x))
          lines (mapv (fn [line]
                        (str (apply str (repeat pad-x \space))
                             (ansi/truncate-to-width line inner)
                             (apply str (repeat pad-x \space))))
                      (str/split-lines content))]
      lines))

  (handle-input [this _raw] this)
  (invalidate   [this] (assoc this :invalidated true)))

(defn create-truncated-text
  "Create a TruncatedText component."
  ([content] (create-truncated-text content {}))
  ([content {:keys [padding-x padding-y] :or {padding-x 0 padding-y 0}}]
   (->TruncatedText content padding-x padding-y false)))

;;;; Spacer

(defrecord Spacer [lines invalidated]
  proto/Component
  (render [_this width]
    (vec (repeat (or lines 1) (apply str (repeat width \space)))))

  (handle-input [this _raw] this)
  (invalidate   [this] (assoc this :invalidated true)))

(defn create-spacer
  "Create a Spacer component with n blank lines."
  ([] (create-spacer 1))
  ([n] (->Spacer n false)))

;;;; Container

(defrecord Container [children invalidated]
  proto/Component
  (render [_this width]
    (vec (mapcat #(proto/render % width) children)))

  (handle-input [this _raw] this)
  (invalidate   [this] (assoc this :invalidated true)))

(defn create-container
  "Create a Container wrapping a sequence of child components."
  [children]
  (->Container (vec children) false))

;;;; Box

(defrecord Box [children padding-x padding-y invalidated]
  proto/Component
  (render [_this width]
    (let [pad-x  (or padding-x 0)
          pad-y  (or padding-y 0)
          inner  (- width (* 2 pad-x))
          h-pad  (apply str (repeat pad-x \space))
          blank  (apply str (repeat width \space))
          top    (repeat pad-y blank)
          bottom (repeat pad-y blank)
          inner-lines (mapcat #(proto/render % inner) children)
          padded (mapv (fn [line] (str h-pad line h-pad)) inner-lines)]
      (vec (concat top padded bottom))))

  (handle-input [this _raw] this)
  (invalidate   [this] (assoc this :invalidated true)))

(defn create-box
  "Create a Box layout wrapper."
  ([children] (create-box children {}))
  ([children {:keys [padding-x padding-y] :or {padding-x 0 padding-y 0}}]
   (->Box (vec children) padding-x padding-y false)))

;;;; Input (single-line)

(defrecord Input [value cursor-col focused invalidated]
  proto/Component
  (render [this width]
    ;; Show value with cursor marker when focused.
    (let [marker (if (:focused this) (:cursor-marker config) "")
          before (subs value 0 cursor-col)
          after  (subs value cursor-col)
          line   (str before marker after)]
      [(ansi/truncate-to-width line width)]))

  (handle-input [this raw]
    (cond
      ;; InputSubmitted — caller handles the event; component stays
      (matches-key? raw "enter")
      this

      ;; InputCharInserted
      (printable? raw)
      (let [new-val (str (subs value 0 cursor-col) raw (subs value cursor-col))]
        (assoc this :value new-val :cursor-col (inc cursor-col)))

      ;; InputBackspace
      (or (matches-key? raw "backspace") (= raw "\u007f"))
      (if (pos? cursor-col)
        (let [new-val (str (subs value 0 (dec cursor-col)) (subs value cursor-col))]
          (assoc this :value new-val :cursor-col (dec cursor-col)))
        this)

      ;; InputCursorLeft
      (or (matches-key? raw "left") (matches-key? raw "\u0002"))
      (assoc this :cursor-col (max 0 (dec cursor-col)))

      ;; InputCursorRight
      (or (matches-key? raw "right") (matches-key? raw "\u0006"))
      (assoc this :cursor-col (min (count value) (inc cursor-col)))

      ;; InputLineStart
      (or (matches-key? raw "home") (matches-key? raw "\u0001"))
      (assoc this :cursor-col 0)

      ;; InputLineEnd
      (or (matches-key? raw "end") (matches-key? raw "\u0005"))
      (assoc this :cursor-col (count value))

      ;; InputDeleteToEnd
      (matches-key? raw "\u000b")
      (assoc this :value (subs value 0 cursor-col))

      ;; InputDeleteToStart
      (matches-key? raw "\u0015")
      (assoc this :value (subs value cursor-col) :cursor-col 0)

      :else this))

  (invalidate [this] (assoc this :invalidated true))

  proto/Focusable
  (focused?     [this] (:focused this))
  (set-focused! [this v] (assoc this :focused v)))

(defn create-input
  "Create an Input component."
  ([] (create-input ""))
  ([initial-value]
   (->Input initial-value 0 false false)))

;;;; Editor (multi-line)

(defrecord Editor
  [lines cursor scroll-offset padding-x focused
   disable-submit autocomplete-active
   history history-index
   jump-mode last-action paste-counter
   invalidated]

  proto/Component
  (render [_this width]
    (let [pad-x         (or padding-x 0)
          inner         (- width (* 2 pad-x))
          cursor-line   (:line cursor)
          cursor-col    (:col cursor)
          max-visible   (max (:editor-min-visible config)
                             (int (* 24 (:editor-max-visible-pct config))))
          visible-lines (take max-visible
                              (drop scroll-offset
                                    (map-indexed vector lines)))
          marker        (if focused (:cursor-marker config) "")]
      (vec (for [[i line] visible-lines]
             (let [rendered (if (= i cursor-line)
                              (str (subs line 0 (min cursor-col (count line)))
                                   marker
                                   (subs line (min cursor-col (count line))))
                              line)
                   padded   (str (apply str (repeat pad-x \space))
                                 (ansi/truncate-to-width rendered inner)
                                 (apply str (repeat pad-x \space)))]
               padded)))))

  (handle-input [this raw]
    (cond
      ;; EditorSubmitted
      (and (matches-key? raw "enter")
           (not disable-submit)
           (not autocomplete-active))
      (let [text (str/join "\n" lines)
            trimmed (str/trim text)
            new-hist (if (and (seq trimmed)
                              (or (empty? history)
                                  (not= (first history) trimmed)))
                       (into [trimmed]
                             (take (dec (:editor-max-history config)) history))
                       history)]
        (assoc this
               :lines [""]
               :cursor {:line 0 :col 0}
               :scroll-offset 0
               :history-index -1
               :history new-hist))

      ;; EditorNewLine
      (or (matches-key? raw "shift+enter")
          (matches-key? raw "alt+enter")
          (matches-key? raw "ctrl+enter"))
      (let [cur-line  (:line cursor)
            cur-col   (:col cursor)
            line      (nth lines cur-line)
            before    (subs line 0 cur-col)
            after     (subs line cur-col)
            new-lines (vec (concat (take cur-line lines)
                                   [before after]
                                   (drop (inc cur-line) lines)))]
        (assoc this
               :lines new-lines
               :cursor {:line (inc cur-line) :col 0}))

      ;; EditorCharInserted
      (and (printable? raw) (= jump-mode :none))
      (let [cur-line  (:line cursor)
            cur-col   (:col cursor)
            line      (nth lines cur-line "")
            new-line  (str (subs line 0 cur-col) raw (subs line cur-col))
            new-lines (assoc lines cur-line new-line)]
        (assoc this
               :lines new-lines
               :cursor {:line cur-line :col (inc cur-col)}))

      ;; EditorBackspace
      (or (matches-key? raw "backspace") (= raw "\u007f"))
      (let [cur-line (:line cursor)
            cur-col  (:col cursor)]
        (if (pos? cur-col)
          ;; Delete char before cursor on same line
          (let [line     (nth lines cur-line)
                new-line (str (subs line 0 (dec cur-col)) (subs line cur-col))]
            (assoc this
                   :lines (assoc lines cur-line new-line)
                   :cursor {:line cur-line :col (dec cur-col)}))
          ;; At start of line — merge with previous line
          (if (pos? cur-line)
            (let [prev-line    (nth lines (dec cur-line))
                  cur-line-str (nth lines cur-line)
                  merged       (str prev-line cur-line-str)
                  new-lines    (vec (concat (take (dec cur-line) lines)
                                            [merged]
                                            (drop (inc cur-line) lines)))]
              (assoc this
                     :lines new-lines
                     :cursor {:line (dec cur-line) :col (count prev-line)}))
            this)))

      ;; EditorCursorLeft
      (or (matches-key? raw "left") (matches-key? raw "\u0002"))
      (let [cur-line (:line cursor)
            cur-col  (:col cursor)]
        (cond
          (pos? cur-col)
          (assoc this :cursor {:line cur-line :col (dec cur-col)})
          (pos? cur-line)
          (let [prev-line (nth lines (dec cur-line))]
            (assoc this :cursor {:line (dec cur-line) :col (count prev-line)}))
          :else this))

      ;; EditorCursorRight
      (or (matches-key? raw "right") (matches-key? raw "\u0006"))
      (let [cur-line (:line cursor)
            cur-col  (:col cursor)
            line     (nth lines cur-line "")]
        (cond
          (< cur-col (count line))
          (assoc this :cursor {:line cur-line :col (inc cur-col)})
          (< cur-line (dec (count lines)))
          (assoc this :cursor {:line (inc cur-line) :col 0})
          :else this))

      ;; EditorCursorUp / EditorCursorDown (history navigation at buffer edges)
      (matches-key? raw "up")
      (let [cur-line (:line cursor)]
        (if (pos? cur-line)
          (let [new-line (dec cur-line)
                line     (nth lines new-line "")
                new-col  (min (:col cursor) (count line))]
            (assoc this :cursor {:line new-line :col new-col}))
          ;; At first line — navigate history upward
          (let [new-idx (inc history-index)]
            (if (< new-idx (count history))
              (let [hist-lines (str/split-lines (nth history new-idx))
                    hist-lines (if (seq hist-lines) hist-lines [""])]
                (assoc this
                       :lines (vec hist-lines)
                       :cursor {:line (dec (count hist-lines))
                                :col  (count (last hist-lines))}
                       :history-index new-idx))
              this))))

      (matches-key? raw "down")
      (let [cur-line (:line cursor)]
        (if (< cur-line (dec (count lines)))
          (let [new-line (inc cur-line)
                line     (nth lines new-line "")
                new-col  (min (:col cursor) (count line))]
            (assoc this :cursor {:line new-line :col new-col}))
          ;; At last line — navigate history downward
          (let [new-idx (dec history-index)]
            (cond
              (= new-idx -1)
              (assoc this
                     :lines [""]
                     :cursor {:line 0 :col 0}
                     :history-index -1)
              (>= new-idx 0)
              (let [hist-lines (str/split-lines (nth history new-idx))
                    hist-lines (if (seq hist-lines) hist-lines [""])]
                (assoc this
                       :lines (vec hist-lines)
                       :cursor {:line (dec (count hist-lines))
                                :col  (count (last hist-lines))}
                       :history-index new-idx))
              :else this))))

      ;; EditorLineStart
      (or (matches-key? raw "home") (matches-key? raw "\u0001"))
      (assoc this :cursor {:line (:line cursor) :col 0})

      ;; EditorLineEnd
      (or (matches-key? raw "end") (matches-key? raw "\u0005"))
      (let [cur-line (:line cursor)
            line     (nth lines cur-line "")]
        (assoc this :cursor {:line cur-line :col (count line)}))

      ;; EditorDeleteToLineEnd
      (matches-key? raw "\u000b")
      (let [cur-line (:line cursor)
            cur-col  (:col cursor)
            line     (nth lines cur-line "")
            new-line (subs line 0 cur-col)]
        (assoc this :lines (assoc lines cur-line new-line)))

      ;; EditorDeleteToLineStart
      (matches-key? raw "\u0015")
      (let [cur-line (:line cursor)
            cur-col  (:col cursor)
            line     (nth lines cur-line "")
            new-line (subs line cur-col)]
        (assoc this
               :lines (assoc lines cur-line new-line)
               :cursor {:line cur-line :col 0}))

      ;; AutocompleteCancelled / AutocompleteItemSelected
      (and autocomplete-active (matches-key? raw "escape"))
      (assoc this :autocomplete-active false)

      :else this))

  (invalidate [this] (assoc this :invalidated true))

  proto/Focusable
  (focused?     [this] (:focused this))
  (set-focused! [this v] (assoc this :focused v)))

(defn create-editor
  "Create an Editor component."
  ([] (create-editor {}))
  ([{:keys [padding-x disable-submit history]
     :or   {padding-x 0 disable-submit false history []}}]
   (->Editor
    [""]                   ; lines
    {:line 0 :col 0}       ; cursor
    0                      ; scroll-offset
    padding-x
    false                  ; focused
    disable-submit
    false                  ; autocomplete-active
    (vec history)
    -1                     ; history-index
    :none                  ; jump-mode
    :none                  ; last-action
    0                      ; paste-counter
    false)))               ; invalidated

;;;; Loader

(defrecord Loader [running message invalidated]
  proto/Component
  (render [_this width]
    [(ansi/truncate-to-width (str (when running "⠋ ") message) width)])

  (handle-input [this _raw] this)
  (invalidate   [this] (assoc this :invalidated true)))

(defn create-loader
  "Create a Loader component."
  ([] (create-loader "Loading…"))
  ([message] (->Loader false message false)))

;;;; CancellableLoader

(defrecord CancellableLoader [running message aborted invalidated]
  proto/Component
  (render [_this width]
    [(ansi/truncate-to-width
      (str (when running "⠋ ") message (when aborted " (cancelling…)"))
      width)])

  (handle-input [this raw]
    (if (and (matches-key? raw "escape") (not aborted))
      (assoc this :aborted true)
      this))

  (invalidate [this] (assoc this :invalidated true)))

(defn create-cancellable-loader
  "Create a CancellableLoader component."
  ([] (create-cancellable-loader "Loading…"))
  ([message] (->CancellableLoader false message false false)))

;;;; SelectList

(defrecord SelectList [items filtered-items selected-index max-visible invalidated]
  proto/Component
  (render [_this width]
    (let [visible (take (or max-visible 10) filtered-items)
          no-match (:select-list-no-match config)]
      (if (empty? filtered-items)
        [(ansi/truncate-to-width no-match width)]
        (vec (map-indexed
              (fn [i item]
                (let [label (:label item)
                      prefix (if (= i selected-index) "> " "  ")]
                  (ansi/truncate-to-width (str prefix label) width)))
              visible)))))

  (handle-input [this raw]
    (cond
      ;; SelectListMoveUp
      (matches-key? raw "up")
      (assoc this :selected-index
             (if (zero? selected-index)
               (dec (count filtered-items))
               (dec selected-index)))

      ;; SelectListMoveDown
      (matches-key? raw "down")
      (assoc this :selected-index
             (if (= selected-index (dec (count filtered-items)))
               0
               (inc selected-index)))

      ;; SelectListConfirm / SelectListCancel handled by TUI layer
      :else this))

  (invalidate [this] (assoc this :invalidated true)))

(defn create-select-list
  "Create a SelectList component from a sequence of {:value :label :description?} maps."
  ([items] (create-select-list items {}))
  ([items {:keys [max-visible] :or {max-visible 10}}]
   (->SelectList (vec items) (vec items) 0 max-visible false)))

(defn set-filter
  "Apply a filter string to a SelectList. Returns updated list.
   Implements SelectListFiltered rule."
  [select-list filter-str]
  (let [filtered (filterv #(str/starts-with? (:label %) filter-str)
                           (:items select-list))]
    (assoc select-list
           :filtered-items filtered
           :selected-index 0)))

;;;; Markdown (stub — full rendering deferred)

(defrecord Markdown [content padding-x padding-y invalidated]
  proto/Component
  (render [_this width]
    (let [pad-x (or padding-x 0)
          inner (- width (* 2 pad-x))]
      (mapv (fn [line]
              (str (apply str (repeat pad-x \space))
                   (ansi/truncate-to-width line inner)
                   (apply str (repeat pad-x \space))))
            (str/split-lines content))))

  (handle-input [this _raw] this)
  (invalidate   [this] (assoc this :invalidated true)))

(defn create-markdown
  "Create a Markdown component (stub renderer — no markdown parsing yet)."
  ([content] (create-markdown content {}))
  ([content {:keys [padding-x padding-y] :or {padding-x 0 padding-y 0}}]
   (->Markdown content padding-x padding-y false)))
