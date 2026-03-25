;;; psi-vui-region-spike.el --- Spike: vui in a split window alongside plain transcript  -*- lexical-binding: t; -*-

;;; Commentary:
;;
;; Spike: prove the "static scrollback + vui live region" strategy.
;;
;; Layout:
;;
;;   ┌─────────────────────────────────┐
;;   │  *psi:vui*  (transcript)        │  ← plain text, grows freely
;;   │  ...                            │
;;   │  ...                            │
;;   ├─────────────────────────────────┤
;;   │  *psi-vui-region*  (vui)        │  ← vui buffer, fixed height
;;   │  phase: idle   model: claude    │    owns interactive region
;;   │  [↺ refresh]                    │
;;   └─────────────────────────────────┘
;;
;; The vui buffer is mounted in a dedicated split window below the transcript.
;; vui erases/rewrites only its own buffer — the transcript is untouched.
;;
;; Usage:
;;   M-x psi-vui-region-spike-open
;;
;; Requires a live psi RPC process (i.e. an active *psi:...* buffer).

;;; Code:

(require 'cl-lib)
(require 'vui)
(require 'psi-rpc)

;;; Reuse context + helpers from the first spike

(declare-function psi-vui-rpc-client-provider "psi-vui-spike")
(declare-function use-psi-vui-rpc-client "psi-vui-spike")
(declare-function psi-vui--query-result "psi-vui-spike")
(declare-function psi-vui--alist-kw "psi-vui-spike")
(declare-function psi-vui-spike--find-client "psi-vui-spike")

(defvar psi-vui-region-spike--vui-buffer-name "*psi-vui-region*"
  "Buffer name for the vui live-region spike.")

(defconst psi-vui-region-spike--window-height 5
  "Fixed height in lines for the vui split window.")

;;; Component — same query loop, now lives in a split window

(vui-defcomponent psi-vui-region-status ()
  "Session status component for the live vui region."
  :state ((phase  "…")
          (model  "…")
          (err    nil)
          (loaded nil))
  :render
  (vui-vstack
   (vui-hstack
    (vui-text "phase : " :face 'font-lock-comment-face)
    (vui-text (if loaded (format "%s" phase) "querying…")))
   (vui-hstack
    (vui-text "model : " :face 'font-lock-comment-face)
    (vui-text (if loaded (format "%s" model) "querying…")))
   (vui-newline)
   (vui-button "↺ refresh"
               :on-click (lambda ()
                           (vui-set-state :err nil)
                           (psi-vui-region--do-query))))
  :on-mount
  (psi-vui-region--do-query))

(defun psi-vui-region--do-query ()
  "Fire query_eql; update component state via async callback."
  (let ((client (use-psi-vui-rpc-client)))
    (if (not (psi-rpc-client-p client))
        (vui-set-state :err "no rpc client in context")
      (psi-rpc-send-request!
       client
       "query_eql"
       '((:query . "[:psi.agent-session/phase :psi.agent-session/model]"))
       (vui-async-callback (frame)
         (let* ((result (psi-vui--query-result frame))
                (phase  (psi-vui--alist-kw result :psi.agent-session/phase))
                (model  (psi-vui--alist-kw result :psi.agent-session/model)))
           (if result
               (progn
                 (vui-set-state :phase  (or phase "nil"))
                 (vui-set-state :model  (or model "nil"))
                 (vui-set-state :loaded t)
                 (vui-set-state :err    nil))
             (vui-set-state :err "empty result"))))))))

(vui-defcomponent psi-vui-region-root (client)
  "Root: provides rpc client context to the live region."
  :render
  (psi-vui-rpc-client-provider client
    (vui-component 'psi-vui-region-status)))

;;; Mount without switching buffers
;; vui-mount unconditionally calls switch-to-buffer at the end.
;; We replicate its internals using with-current-buffer so the
;; calling window is never disturbed.

(defun psi-vui-region-spike--mount-in-buffer (vnode buf)
  "Mount VNODE into BUF without switching the selected window.
Returns the root instance."
  (let* ((instance (vui--create-instance vnode nil))
         (vui--pending-effects nil))
    (setf (vui-instance-buffer instance) buf)
    (with-current-buffer buf
      (let ((inhibit-read-only t))
        (unless (derived-mode-p 'vui-mode)
          (vui-mode))
        (remove-overlays)
        (erase-buffer)
        (setq-local vui--root-instance instance)
        (let ((vui--root-instance instance))
          (setq vui--rendering-p t)
          (unwind-protect
              (vui--render-instance instance)
            (setq vui--rendering-p nil)))
        (widget-setup)
        (vui--run-pending-effects)
        (goto-char (point-min))))
    instance))

;;; Window management
;; Use a side window so the vui buffer stays frame-persistent —
;; it doesn't disappear when the transcript buffer is switched.

(defun psi-vui-region-spike--show-side-window ()
  "Display the vui region buffer in a bottom side window.
Side windows are frame-persistent: they survive buffer switches."
  (let ((buf (get-buffer-create psi-vui-region-spike--vui-buffer-name)))
    (display-buffer-in-side-window
     buf
     `((side          . bottom)
       (slot          . 0)
       (window-height . ,psi-vui-region-spike--window-height)
       (preserve-size . (nil . t))))))

(defun psi-vui-region-spike--mount-vui (client)
  "Mount the vui component into the vui region buffer with CLIENT."
  (let* ((buf   (get-buffer-create psi-vui-region-spike--vui-buffer-name))
         (vnode (vui-component 'psi-vui-region-root :client client)))
    (psi-vui-region-spike--mount-in-buffer vnode buf)))

;;; Entry point

;;;###autoload
(defun psi-vui-region-spike-open ()
  "Open the vui live-region spike in a bottom side window.

The vui buffer appears as a frame-persistent side window — it stays
visible regardless of which buffer occupies the main window.
The transcript buffer (*psi:...*) is untouched."
  (interactive)
  (require 'psi-vui-spike)
  (let ((client (psi-vui-spike--find-client)))
    (unless client
      (user-error "No live psi RPC client — open a *psi:...* buffer first"))
    (psi-vui-region-spike--mount-vui client)
    (psi-vui-region-spike--show-side-window)))

(provide 'psi-vui-region-spike)
;;; psi-vui-region-spike.el ends here
