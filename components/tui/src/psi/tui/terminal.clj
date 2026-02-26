(ns psi.tui.terminal
  "Terminal implementations.

   ProcessTerminal  — real OS terminal wrapping stdin/stdout.
                      Uses Java ProcessBuilder-style raw-mode via JVM threads.
   VirtualTerminal  — in-memory terminal for tests (Nullable pattern).

   Implements rules:
     TUIStarts        (start! wires callbacks; hides cursor; enables Kitty/paste)
     TUIStops         (stop! restores state; disables Kitty/paste)
     KittyProtocolQueried   (start! sends query sequence)
     BracketedPasteEnabled  (start! enables bracketed paste)
     KittyProtocolDisabledOnStop
     BracketedPasteDisabledOnStop"
  (:require
   [psi.tui.protocols :as proto])
  (:import
   (java.io FileInputStream PrintStream)))

;;;; Escape constants

(def ^:private hide-cursor   "\u001b[?25l")
(def ^:private show-cursor   "\u001b[?25h")
(def ^:private kitty-query   "\u001b[?u")
(def ^:private kitty-disable "\u001b[<u")
(def ^:private paste-enable  "\u001b[?2004h")
(def ^:private paste-disable "\u001b[?2004l")

(defn move-cursor-to
  "Return an ANSI sequence to move the hardware cursor to {:row r :col c}."
  [{:keys [row col]}]
  (format "\u001b[%d;%dH" (inc row) (inc col)))

;;;; VirtualTerminal (Nullable / in-memory)

(defrecord VirtualTerminal
  [cols-atom
   rows-atom
   output-atom          ; vector of written strings
   kitty-active-atom
   running-atom]

  proto/Terminal
  (start! [this on-input on-resize]
    ;; In-memory: record that we're running; write startup sequences to output.
    (reset! running-atom true)
    (swap! output-atom conj kitty-query)
    (swap! output-atom conj paste-enable)
    (swap! output-atom conj hide-cursor)
    ;; Store callbacks so tests can fire synthetic events.
    (alter-meta! (:on-input-atom (meta this)) (constantly on-input))
    (alter-meta! (:on-resize-atom (meta this)) (constantly on-resize))
    this)

  (stop! [_this]
    (reset! running-atom false)
    (swap! output-atom conj kitty-disable)
    (swap! output-atom conj paste-disable)
    (swap! output-atom conj show-cursor))

  (write! [_this data]
    (swap! output-atom conj data))

  (columns [_this] @cols-atom)
  (rows    [_this] @rows-atom))

(defn create-virtual-terminal
  "Create an in-memory VirtualTerminal for testing.
   opts: {:cols 80 :rows 24}

   Returns the terminal. Use written-output to inspect output.
   Use fire-input! / fire-resize! to inject synthetic events."
  ([] (create-virtual-terminal {}))
  ([{:keys [cols rows] :or {cols 80 rows 24}}]
   (let [on-input-atom  (atom nil)
         on-resize-atom (atom nil)
         term           (->VirtualTerminal
                         (atom cols)
                         (atom rows)
                         (atom [])
                         (atom false)
                         (atom false))]
     ;; Stash callbacks into metadata so fire-input!/fire-resize! can reach them.
     (with-meta term {:on-input-atom  on-input-atom
                      :on-resize-atom on-resize-atom}))))

(defn written-output
  "Return the vector of strings written to a VirtualTerminal."
  [vterm]
  @(:output-atom vterm))

(defn fire-input!
  "Inject a synthetic key event into a running VirtualTerminal."
  [vterm raw-key]
  (when-let [cb (deref (:on-input-atom (meta vterm)))]
    (cb raw-key)))

(defn fire-resize!
  "Inject a synthetic resize event into a running VirtualTerminal."
  [vterm cols rows]
  (reset! (:cols-atom vterm) cols)
  (reset! (:rows-atom vterm) rows)
  (when-let [cb (deref (:on-resize-atom (meta vterm)))]
    (cb cols rows)))

;;;; ProcessTerminal (real OS terminal)

(defn- run-stty!
  "Run stty with args against /dev/tty. Ignores errors."
  [& args]
  (try
    (-> (ProcessBuilder. ^java.util.List (into ["stty"] args))
        (.redirectInput (java.io.File. "/dev/tty"))
        (.redirectOutput (java.io.File. "/dev/tty"))
        (.start)
        (.waitFor))
    (catch Exception _ nil)))

(defn- translate-key
  "Translate raw VT byte sequences to symbolic key names that components expect.
   Printable characters are returned as-is.
   Unknown sequences are returned as-is."
  [^String raw]
  (case raw
    "\r"       "enter"
    "\n"       "enter"
    "\u001b"   "escape"
    "\u001b[A" "up"
    "\u001b[B" "down"
    "\u001b[C" "right"
    "\u001b[D" "left"
    "\u001b[H" "home"
    "\u001b[F" "end"
    "\u001b[1~" "home"
    "\u001b[4~" "end"
    "\u001b[5~" "pageUp"
    "\u001b[6~" "pageDown"
    "\u001b[3~" "delete"
    "\u007f"   "backspace"
    "\u0008"   "backspace"
    "\t"       "tab"
    ;; Ctrl+letter sequences already handled by components as raw chars:
    ;;  \u0001=ctrl+a, \u0002=ctrl+b, \u0005=ctrl+e, \u0006=ctrl+f,
    ;;  \u000b=ctrl+k, \u000f=ctrl+o, \u0015=ctrl+u
    ;; Shift+enter variants sent by some terminals
    "\u001b[13;2u" "shift+enter"
    "\u001b\r"     "alt+enter"
    raw))

(defn- read-input-loop
  "Read bytes from /dev/tty, translate to key names, call on-input.
   Runs in a background daemon thread. Exits when running? becomes false."
  [on-input running?]
  (try
    (let [tty (FileInputStream. "/dev/tty")
          buf (byte-array 64)]
      (loop []
        (when @running?
          (let [n (.read tty buf 0 64)]
            (when (pos? n)
              (on-input (translate-key (String. buf 0 n "UTF-8"))))
            (recur)))))
    (catch Exception _e nil)))

(defrecord ProcessTerminal
  [^PrintStream out
   cols-atom
   rows-atom
   kitty-active-atom
   running-atom
   reader-thread-atom]

  proto/Terminal
  (start! [this on-input _on-resize]
    (reset! running-atom true)
    ;; Enable raw mode: no echo, no canonical buffering
    (run-stty! "-echo" "raw")
    ;; Query Kitty protocol + enable bracketed paste + hide cursor
    (.print out kitty-query)
    (.print out paste-enable)
    (.print out hide-cursor)
    (.flush out)
    ;; Background reader thread reads from /dev/tty directly
    (let [t (Thread.
             #(read-input-loop on-input running-atom)
             "tui-input-reader")]
      (.setDaemon t true)
      (.start t)
      (reset! reader-thread-atom t))
    this)

  (stop! [_this]
    (reset! running-atom false)
    ;; Restore terminal state before writing cursor-restore sequences
    (run-stty! "sane")
    (when @kitty-active-atom
      (.print out kitty-disable)
      (reset! kitty-active-atom false))
    (.print out paste-disable)
    (.print out show-cursor)
    (.flush out))

  (write! [_this data]
    (.print out data)
    (.flush out))

  (columns [_this] @cols-atom)
  (rows    [_this] @rows-atom))

(defn create-process-terminal
  "Create a ProcessTerminal backed by System/out.
   Dimensions default to 80×24; pass {:cols n :rows n} to override."
  ([] (create-process-terminal {}))
  ([{:keys [cols rows] :or {cols 80 rows 24}}]
   (->ProcessTerminal
    System/out
    (atom cols)
    (atom rows)
    (atom false)
    (atom false)
    (atom nil))))
