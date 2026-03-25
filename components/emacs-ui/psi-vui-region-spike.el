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

;;; Window management

(defun psi-vui-region-spike--get-or-create-window (transcript-window)
  "Return the vui split window below TRANSCRIPT-WINDOW, creating it if needed."
  (let ((vui-buf (get-buffer-create psi-vui-region-spike--vui-buffer-name)))
    ;; Look for an existing window already showing the vui buffer
    (or (get-buffer-window vui-buf)
        ;; Split below transcript window, fixed height
        (let ((win (split-window transcript-window
                                 (- psi-vui-region-spike--window-height)
                                 'below)))
          (set-window-buffer win vui-buf)
          (set-window-dedicated-p win t)
          win))))

(defun psi-vui-region-spike--mount-vui (client)
  "Mount the vui component into the vui region buffer with CLIENT."
  (let ((vnode (vui-component 'psi-vui-region-root :client client)))
    ;; vui-mount switches to the buffer — we call it, then restore window state
    (save-window-excursion
      (vui-mount vnode psi-vui-region-spike--vui-buffer-name))))

;;; Entry point

;;;###autoload
(defun psi-vui-region-spike-open ()
  "Open the vui live-region spike alongside the active psi transcript buffer.

Finds the active *psi:...* buffer, splits its window, and mounts a vui
component in the lower split.  The transcript buffer is untouched."
  (interactive)
  (require 'psi-vui-spike)
  (let* ((client (psi-vui-spike--find-client))
         (_ (unless client
              (user-error "No live psi RPC client — open a *psi:...* buffer first")))
         ;; Find or select the transcript window
         (transcript-buf (psi-vui-region-spike--find-transcript-buffer))
         (_ (unless transcript-buf
              (user-error "No *psi:...* transcript buffer found")))
         (transcript-win (or (get-buffer-window transcript-buf)
                             (display-buffer transcript-buf))))
    ;; Mount vui into its own buffer (save-window-excursion keeps us here)
    (psi-vui-region-spike--mount-vui client)
    ;; Open the split below the transcript
    (psi-vui-region-spike--get-or-create-window transcript-win)
    ;; Leave point in the transcript
    (select-window transcript-win)))

(defun psi-vui-region-spike--find-transcript-buffer ()
  "Return the first live *psi:...* buffer, or nil."
  (cl-find-if
   (lambda (buf)
     (string-match-p "\\*psi[*:]" (buffer-name buf)))
   (buffer-list)))

(provide 'psi-vui-region-spike)
;;; psi-vui-region-spike.el ends here
