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

(defn- editor-cursor [editor]
  (:cursor editor))

(defn- editor-cur-line [editor]
  (:line (editor-cursor editor)))

(defn- editor-cur-col [editor]
  (:col (editor-cursor editor)))

(defn- editor-line-at [editor idx]
  (nth (:lines editor) idx ""))

(defn- editor-set-cursor [editor line col]
  (assoc editor :cursor {:line line :col col}))

(defn- editor-set-buffer [editor new-lines line col]
  (-> editor
      (assoc :lines new-lines)
      (editor-set-cursor line col)))

(defn- editor-history-lines [entry]
  (let [hist-lines (str/split-lines entry)]
    (if (seq hist-lines) (vec hist-lines) [""])))

(defn- editor-append-history [history text]
  (let [trimmed (str/trim text)]
    (if (and (seq trimmed)
             (or (empty? history)
                 (not= (first history) trimmed)))
      (into [trimmed]
            (take (dec (:editor-max-history config)) history))
      history)))

(defn- editor-visible-lines [editor]
  (let [max-visible (max (:editor-min-visible config)
                         (int (* 24 (:editor-max-visible-pct config))))]
    (take max-visible
          (drop (:scroll-offset editor)
                (map-indexed vector (:lines editor))))))

(defn- editor-render-line [editor width [i line]]
  (let [pad-x       (or (:padding-x editor) 0)
        inner       (- width (* 2 pad-x))
        cursor-line (editor-cur-line editor)
        cursor-col  (editor-cur-col editor)
        marker      (if (:focused editor) (:cursor-marker config) "")
        rendered    (if (= i cursor-line)
                      (let [split-col (min cursor-col (count line))]
                        (str (subs line 0 split-col)
                             marker
                             (subs line split-col)))
                      line)]
    (str (apply str (repeat pad-x \space))
         (ansi/truncate-to-width rendered inner)
         (apply str (repeat pad-x \space)))))

(defn- editor-submit [editor]
  (assoc editor
         :lines [""]
         :cursor {:line 0 :col 0}
         :scroll-offset 0
         :history-index -1
         :history (editor-append-history (:history editor)
                                         (str/join "\n" (:lines editor)))))

(defn- editor-insert-newline [editor]
  (let [cur-line  (editor-cur-line editor)
        cur-col   (editor-cur-col editor)
        line      (editor-line-at editor cur-line)
        before    (subs line 0 cur-col)
        after     (subs line cur-col)
        new-lines (vec (concat (take cur-line (:lines editor))
                               [before after]
                               (drop (inc cur-line) (:lines editor))))]
    (editor-set-buffer editor new-lines (inc cur-line) 0)))

(defn- editor-insert-char [editor raw]
  (let [cur-line  (editor-cur-line editor)
        cur-col   (editor-cur-col editor)
        line      (editor-line-at editor cur-line)
        new-line  (str (subs line 0 cur-col) raw (subs line cur-col))]
    (editor-set-buffer editor
                       (assoc (:lines editor) cur-line new-line)
                       cur-line
                       (inc cur-col))))

(defn- editor-backspace [editor]
  (let [cur-line (editor-cur-line editor)
        cur-col  (editor-cur-col editor)]
    (cond
      (pos? cur-col)
      (let [line     (editor-line-at editor cur-line)
            new-line (str (subs line 0 (dec cur-col)) (subs line cur-col))]
        (editor-set-buffer editor
                           (assoc (:lines editor) cur-line new-line)
                           cur-line
                           (dec cur-col)))

      (pos? cur-line)
      (let [prev-line    (editor-line-at editor (dec cur-line))
            cur-line-str (editor-line-at editor cur-line)
            merged       (str prev-line cur-line-str)
            new-lines    (vec (concat (take (dec cur-line) (:lines editor))
                                      [merged]
                                      (drop (inc cur-line) (:lines editor))))]
        (editor-set-buffer editor new-lines (dec cur-line) (count prev-line)))

      :else editor)))

(defn- editor-move-left [editor]
  (let [cur-line (editor-cur-line editor)
        cur-col  (editor-cur-col editor)]
    (cond
      (pos? cur-col)
      (editor-set-cursor editor cur-line (dec cur-col))

      (pos? cur-line)
      (editor-set-cursor editor (dec cur-line) (count (editor-line-at editor (dec cur-line))))

      :else editor)))

(defn- editor-move-right [editor]
  (let [cur-line (editor-cur-line editor)
        cur-col  (editor-cur-col editor)
        line     (editor-line-at editor cur-line)]
    (cond
      (< cur-col (count line))
      (editor-set-cursor editor cur-line (inc cur-col))

      (< cur-line (dec (count (:lines editor))))
      (editor-set-cursor editor (inc cur-line) 0)

      :else editor)))

(defn- editor-history-up [editor]
  (let [new-idx (inc (:history-index editor))]
    (if (< new-idx (count (:history editor)))
      (let [hist-lines (editor-history-lines (nth (:history editor) new-idx))]
        (-> editor
            (assoc :lines hist-lines
                   :history-index new-idx)
            (editor-set-cursor (dec (count hist-lines))
                               (count (last hist-lines)))))
      editor)))

(defn- editor-history-down [editor]
  (let [new-idx (dec (:history-index editor))]
    (cond
      (= new-idx -1)
      (-> editor
          (assoc :lines [""]
                 :history-index -1)
          (editor-set-cursor 0 0))

      (>= new-idx 0)
      (let [hist-lines (editor-history-lines (nth (:history editor) new-idx))]
        (-> editor
            (assoc :lines hist-lines
                   :history-index new-idx)
            (editor-set-cursor (dec (count hist-lines))
                               (count (last hist-lines)))))

      :else editor)))

(defn- editor-move-up [editor]
  (let [cur-line (editor-cur-line editor)]
    (if (pos? cur-line)
      (let [new-line (dec cur-line)
            line     (editor-line-at editor new-line)
            new-col  (min (editor-cur-col editor) (count line))]
        (editor-set-cursor editor new-line new-col))
      (editor-history-up editor))))

(defn- editor-move-down [editor]
  (let [cur-line (editor-cur-line editor)]
    (if (< cur-line (dec (count (:lines editor))))
      (let [new-line (inc cur-line)
            line     (editor-line-at editor new-line)
            new-col  (min (editor-cur-col editor) (count line))]
        (editor-set-cursor editor new-line new-col))
      (editor-history-down editor))))

(defn- editor-line-start [editor]
  (editor-set-cursor editor (editor-cur-line editor) 0))

(defn- editor-line-end [editor]
  (let [cur-line (editor-cur-line editor)]
    (editor-set-cursor editor cur-line (count (editor-line-at editor cur-line)))))

(defn- editor-delete-to-line-end [editor]
  (let [cur-line (editor-cur-line editor)
        cur-col  (editor-cur-col editor)
        line     (editor-line-at editor cur-line)
        new-line (subs line 0 cur-col)]
    (assoc editor :lines (assoc (:lines editor) cur-line new-line))))

(defn- editor-delete-to-line-start [editor]
  (let [cur-line (editor-cur-line editor)
        cur-col  (editor-cur-col editor)
        line     (editor-line-at editor cur-line)
        new-line (subs line cur-col)]
    (-> editor
        (assoc :lines (assoc (:lines editor) cur-line new-line))
        (editor-set-cursor cur-line 0))))

(defn- editor-submit-key? [editor raw]
  (and (matches-key? raw "enter")
       (not (:disable-submit editor))
       (not (:autocomplete-active editor))))

(defn- editor-newline-key? [raw]
  (or (matches-key? raw "shift+enter")
      (matches-key? raw "alt+enter")
      (matches-key? raw "ctrl+enter")))

(defn- editor-backspace-key? [raw]
  (or (matches-key? raw "backspace")
      (= raw "\u007f")))

(defn- editor-left-key? [raw]
  (or (matches-key? raw "left")
      (matches-key? raw "\u0002")))

(defn- editor-right-key? [raw]
  (or (matches-key? raw "right")
      (matches-key? raw "\u0006")))

(defn- editor-line-start-key? [raw]
  (or (matches-key? raw "home")
      (matches-key? raw "\u0001")))

(defn- editor-line-end-key? [raw]
  (or (matches-key? raw "end")
      (matches-key? raw "\u0005")))

(defn- editor-input-op [editor raw]
  (cond
    (editor-submit-key? editor raw) :submit
    (editor-newline-key? raw) :newline
    (and (printable? raw) (= (:jump-mode editor) :none)) :insert-char
    (editor-backspace-key? raw) :backspace
    (editor-left-key? raw) :left
    (editor-right-key? raw) :right
    (matches-key? raw "up") :up
    (matches-key? raw "down") :down
    (editor-line-start-key? raw) :line-start
    (editor-line-end-key? raw) :line-end
    (matches-key? raw "\u000b") :delete-to-line-end
    (matches-key? raw "\u0015") :delete-to-line-start
    (and (:autocomplete-active editor) (matches-key? raw "escape")) :autocomplete-cancel
    :else nil))

(defn- editor-handle-input [editor raw]
  (case (editor-input-op editor raw)
    :submit (editor-submit editor)
    :newline (editor-insert-newline editor)
    :insert-char (editor-insert-char editor raw)
    :backspace (editor-backspace editor)
    :left (editor-move-left editor)
    :right (editor-move-right editor)
    :up (editor-move-up editor)
    :down (editor-move-down editor)
    :line-start (editor-line-start editor)
    :line-end (editor-line-end editor)
    :delete-to-line-end (editor-delete-to-line-end editor)
    :delete-to-line-start (editor-delete-to-line-start editor)
    :autocomplete-cancel (assoc editor :autocomplete-active false)
    editor))

(defrecord Editor
           [lines cursor scroll-offset padding-x focused
            disable-submit autocomplete-active
            history history-index
            jump-mode last-action paste-counter
            invalidated]

  proto/Component
  (render [this width]
    (mapv #(editor-render-line this width %)
          (editor-visible-lines this)))

  (handle-input [this raw]
    (editor-handle-input this raw))

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
