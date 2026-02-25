(ns psi.tui.protocols
  "Protocols for the TUI component layer.

   Three protocols mirror the Allium spec guidance:

     Component  — every renderable, input-handling TUI element
     Focusable  — extension for components that host the hardware cursor
     Terminal   — abstraction over OS terminal I/O

   No implementation details live here; see tui.terminal and tui.components.")

;;;; Component protocol

(defprotocol Component
  "A renderable, interactive TUI element.

   render returns a vector of plain strings (no ANSI reset appended here;
   the TUI layer adds resets per LineResetAfterRender).

   handle-input receives a raw key string (VT or Kitty CSI-u encoding).

   invalidate marks cached render state stale so the next render is fresh."
  (render        [this width]  "Render to width columns. Returns vector of strings.")
  (handle-input  [this raw]    "Handle a raw key event string. Returns updated component.")
  (invalidate    [this]        "Mark cached state stale. Returns updated component."))

;;;; Focusable protocol

(defprotocol Focusable
  "Extension for components that need the hardware cursor positioned.

   focused? returns true when this component currently holds focus.
   set-focused! returns an updated component with focus flag applied."
  (focused?     [this]   "True when this component holds focus.")
  (set-focused! [this v] "Returns updated component with focused flag set to v."))

;;;; Terminal protocol

(defprotocol Terminal
  "Abstraction over OS terminal I/O.

   start!  begins raw-mode input; calls on-input for each key event and
           on-resize when the terminal dimensions change.
   stop!   restores terminal state and flushes the cursor.
   write!  sends raw bytes/string to stdout.
   columns returns the current column count.
   rows    returns the current row count."
  (start!  [this on-input on-resize] "Start terminal; register callbacks.")
  (stop!   [this]                    "Stop terminal and restore state.")
  (write!  [this data]               "Write raw string to the terminal.")
  (columns [this]                    "Current terminal column count.")
  (rows    [this]                    "Current terminal row count."))
