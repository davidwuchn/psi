(ns psi.tui.core
  "TUI container — differential renderer, focus & overlay manager.

   Public API (all functions have an isolated *-in context variant):

     create-context       — Nullable context factory (for tests)
     start-tui-in!        — wire terminal; hide cursor; request render
     stop-tui-in!         — restore cursor; stop terminal
     request-render-in!   — mark render pending (RenderScheduled)
     tick-in!             — execute a pending render if due (DifferentialRender etc.)
     set-focus-in!        — FocusComponent
     show-overlay-in!     — ShowOverlay
     hide-overlay-in!     — HideOverlayPermanently
     handle-input-in!     — InputRoutedToFocused

   Context shape:
     {:tui-atom (atom TUI-map)}

   TUI-map shape mirrors the Allium spec variant TUI."
  (:require
   [clojure.string :as str]
   [psi.tui.ansi :as ansi]
   [psi.tui.protocols :as proto]))

;;;; Constants

(def ^:private cursor-marker "\u001b_pi:c\u0007")
(def ^:private debug-key     "shift+ctrl+d")
(def ^:private show-cursor   "\u001b[?25h")
(def ^:private move-up-fmt   "\u001b[%dA")
(def ^:private erase-down    "\u001b[J")

;;;; TUI state helpers

(defn- make-tui
  "Construct a fresh TUI state map."
  [terminal children]
  {:terminal              terminal
   :children              (vec children)
   :overlays              []
   :focused               nil
   :previous-lines        []
   :previous-width        0
   :hardware-cursor-row   0
   :max-lines-rendered    0
   :render-requested      false
   :show-hardware-cursor  false
   :clear-on-shrink       false})

;;;; Rendering internals

(defn- render-component
  "Render a component at width, appending reset to each line."
  [component width]
  (mapv ansi/reset-line (proto/render component width)))

(defn- render-tui-children
  "Render all TUI children concatenated."
  [tui width]
  (vec (mapcat #(render-component % width) (:children tui))))

(defn- resolve-overlay-layout
  "Compute overlay position. Supports :center anchor only for now."
  [entry term-width term-rows]
  (let [width  (or (get-in entry [:width :value]) (quot term-width 2))
        height (or (get-in entry [:max-height :value]) (quot term-rows 2))
        row    (quot (- term-rows height) 2)
        col    (quot (- term-width width) 2)]
    {:row row :col col :width width :max-height height}))

(defn- composite-overlays
  "Lay overlays over base-lines. Returns updated line vector.
   Implements CompositeOverlays + OverlayLayoutResolution."
  [base-lines overlays term-width term-rows]
  (let [visible (filter #(not (:hidden %)) overlays)]
    (reduce
     (fn [lines entry]
       (let [comp   (:component entry)
             layout (resolve-overlay-layout entry term-width term-rows)
             col    (:col layout)
             width  (:width layout)
             row    (:row layout)
             ol     (mapv ansi/reset-line (proto/render comp width))]
         (reduce
          (fn [acc [i ol-line]]
            (let [target-row (+ row i)]
              (if (< target-row (count acc))
                (let [padded  (format (str "%-" col "s") "")
                      spliced (str padded ol-line)]
                  (assoc acc target-row spliced))
                acc)))
          lines
          (map-indexed vector ol))))
     base-lines
     visible)))

(defn- first-diff
  "Return index of first differing line between prev and next, or nil.
   Implements FirstDiff."
  [prev next]
  (let [max-idx (max (count prev) (count next))]
    (loop [i 0]
      (cond
        (= i max-idx) nil
        (not= (nth prev i nil) (nth next i nil)) i
        :else (recur (inc i))))))

(defn- write-synchronized!
  "Write lines wrapped in sync-start/end (SynchronizedOutput rule)."
  [terminal lines]
  (proto/write! terminal
                (str "\u001b[?2026h"
                     (str/join "\r\n" lines)
                     "\u001b[?2026l")))

(defn- do-render!
  "Execute a render cycle. Implements FirstRender, DifferentialRender,
   WidthChangedRender, NoChangesRender."
  [tui-map terminal]
  (let [width      (proto/columns terminal)
        rows       (proto/rows terminal)
        new-lines  (render-tui-children tui-map width)
        composited (composite-overlays new-lines (:overlays tui-map) width rows)
        prev-lines (:previous-lines tui-map)
        prev-width (:previous-width tui-map)]
    (cond
      ;; FirstRender
      (empty? prev-lines)
      (do
        (write-synchronized! terminal composited)
        (assoc tui-map
               :previous-lines     composited
               :previous-width     width
               :max-lines-rendered (count composited)
               :render-requested   false))

      ;; WidthChangedRender
      (not= prev-width width)
      (do
        (proto/write! terminal "\u001b[2J\u001b[H")
        (write-synchronized! terminal composited)
        (assoc tui-map
               :previous-lines     composited
               :previous-width     width
               :max-lines-rendered (count composited)
               :render-requested   false))

      ;; DifferentialRender / NoChangesRender
      :else
      (let [changed (first-diff prev-lines composited)]
        (if changed
          (let [lines-above (- (count prev-lines) changed)]
            (when (pos? lines-above)
              (proto/write! terminal (format move-up-fmt lines-above)))
            (proto/write! terminal erase-down)
            (write-synchronized! terminal (subvec composited changed))
            (assoc tui-map
                   :previous-lines     composited
                   :max-lines-rendered (max (:max-lines-rendered tui-map)
                                            (count composited))
                   :render-requested   false))
          ;; NoChangesRender — only cursor position may have changed
          (let [show? (:show-hardware-cursor tui-map)]
            (when-let [pos (ansi/find-marker composited cursor-marker)]
              (proto/write! terminal (ansi/move-cursor-to pos))
              (proto/write! terminal (if show? show-cursor "\u001b[?25l")))
            (assoc tui-map :render-requested false)))))))

;;;; Nullable context

(defn create-context
  "Create an isolated TUI context (Nullable pattern).
   terminal must satisfy the Terminal protocol."
  [terminal children]
  {:tui-atom (atom (make-tui terminal children))})

(defn- tui-of    [ctx] @(:tui-atom ctx))
(defn- swap-tui! [ctx f & args] (apply swap! (:tui-atom ctx) f args))

;;;; Public API

(defn request-render-in!
  "Mark a render as pending. Implements RenderScheduled."
  [ctx]
  (swap-tui! ctx assoc :render-requested true))

(defn tick-in!
  "Execute a pending render if one is due.
   Implements RenderScheduled / RenderDue."
  [ctx]
  (swap-tui! ctx
             (fn [tui]
               (if (:render-requested tui)
                 (do-render! tui (:terminal tui))
                 tui))))

(defn handle-input-in!
  "Route input to focused component. Implements InputRoutedToFocused."
  [ctx raw]
  (when (not= raw debug-key)
    (swap-tui! ctx
               (fn [tui]
                 (if-let [focused (:focused tui)]
                   (let [updated (proto/handle-input focused raw)
                         tui-a   (update tui :children
                                         (fn [cs]
                                           (mapv #(if (= % focused) updated %) cs)))
                         tui-b   (update tui-a :overlays
                                         (fn [os]
                                           (mapv #(if (= (:component %) focused)
                                                    (assoc % :component updated)
                                                    %)
                                                 os)))]
                     (assoc tui-b :focused updated :render-requested true))
                   tui)))))

(defn set-focus-in!
  "Set focused component. Implements FocusComponent + FocusableFlagSet/Cleared."
  [ctx component]
  (swap-tui! ctx
             (fn [tui]
               (let [old   (:focused tui)
                     ;; Clear old focus flag if Focusable
                     tui-a (if (and old (satisfies? proto/Focusable old))
                             (update tui :children
                                     (fn [cs]
                                       (mapv #(if (= % old) (proto/set-focused! % false) %) cs)))
                             tui)
                     ;; Set new focus flag if Focusable; keep reference to the updated record
                     [tui-b focused-comp]
                     (if (satisfies? proto/Focusable component)
                       (let [updated (proto/set-focused! component true)]
                         [(update tui-a :children
                                  (fn [cs]
                                    (mapv #(if (= % component) updated %) cs)))
                          updated])
                       [tui-a component])]
                 (assoc tui-b :focused focused-comp :render-requested true)))))

(defn start-tui-in!
  "Start the TUI: wire callbacks, hide cursor, request first render.
   Implements TUIStarts."
  [ctx]
  (let [tui      (tui-of ctx)
        terminal (:terminal tui)
        on-input  (fn [raw] (handle-input-in! ctx raw))
        on-resize (fn [_c _r] (request-render-in! ctx))]
    (proto/start! terminal on-input on-resize)
    (proto/write! terminal "\u001b[?25l")
    (request-render-in! ctx)
    (tick-in! ctx)))

(defn stop-tui-in!
  "Stop the TUI: show cursor, stop terminal.
   Implements TUIStops."
  [ctx]
  (let [{:keys [terminal max-lines-rendered]} (tui-of ctx)]
    (proto/write! terminal (str "\u001b[" (inc max-lines-rendered) "B"))
    (proto/write! terminal show-cursor)
    (proto/stop! terminal)))

(defn show-overlay-in!
  "Add an overlay entry and focus it. Implements ShowOverlay.
   Returns the overlay entry map."
  [ctx component opts]
  (let [entry (merge {:component component
                      :hidden    false
                      :anchor    :center
                      :offset-x  0
                      :offset-y  0
                      :margin    {:top 0 :right 0 :bottom 0 :left 0}}
                     opts)]
    (swap-tui! ctx
               (fn [tui]
                 (assoc tui
                        :overlays         (conj (:overlays tui) entry)
                        :focused          component
                        :render-requested true)))
    entry))

(defn hide-overlay-in!
  "Remove an overlay entry. Implements HideOverlayPermanently."
  [ctx entry]
  (swap-tui! ctx
             (fn [tui]
               (assoc tui
                      :overlays         (filterv #(not= % entry) (:overlays tui))
                      :focused          nil
                      :render-requested true))))

(defn swap-tui-children-in!
  "Replace the TUI children with a new list and request a render."
  [ctx children]
  (swap-tui! ctx assoc :children (vec children) :render-requested true))

(defn focused-in
  "Return the currently focused component, or nil."
  [ctx]
  (:focused (tui-of ctx)))

;;;; Global singleton API

(defonce ^:private global-ctx (atom nil))

(defn init!
  "Initialise the global TUI context with terminal and children."
  [terminal children]
  (reset! global-ctx (create-context terminal children)))

(defn start-tui!          [] (start-tui-in!          @global-ctx))
(defn stop-tui!           [] (stop-tui-in!           @global-ctx))
(defn tick!               [] (tick-in!               @global-ctx))
(defn focused             [] (focused-in             @global-ctx))
(defn set-focus!          [c]   (set-focus-in!          @global-ctx c))
(defn swap-tui-children!  [cs]  (swap-tui-children-in!  @global-ctx cs))
(defn show-overlay!       [c o] (show-overlay-in!       @global-ctx c o))
(defn hide-overlay!       [e]   (hide-overlay-in!       @global-ctx e))
