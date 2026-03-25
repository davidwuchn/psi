;;; psi-vui-spike.el --- Spike: vui component driven by psi RPC query  -*- lexical-binding: t; -*-

;;; Commentary:
;;
;; Spike: prove the loop —
;;   vui component → on-mount query_eql over RPC → async callback → vui-set-state → re-render
;;
;; Usage:
;;   M-x psi-vui-spike-open
;;
;; Requires a live psi RPC process (i.e. an active *psi* buffer).
;; The spike opens a separate *psi-vui-spike* buffer and mounts a vui
;; component that queries [:psi.agent-session/phase :psi.agent-session/model]
;; on mount, then re-queries every 2 seconds.
;;
;; Nothing here is wired into the main psi frontend — it is purely exploratory.

;;; Code:

(require 'cl-lib)
(require 'vui)
(require 'psi-rpc)

;;; Context — RPC client flows down through the vui tree

(vui-defcontext psi-vui-rpc-client nil
  "The psi-rpc-client available to vui components.")

;;; Helpers

(defun psi-vui--query-result (frame)
  "Extract :result map from a query_eql response FRAME."
  (let ((data (alist-get :data frame nil nil #'equal)))
    (and (listp data)
         (alist-get :result data nil nil #'equal))))

(defun psi-vui--alist-kw (result key)
  "Get KEY (keyword symbol) from alist RESULT, tolerating absent values."
  (alist-get key result nil nil #'equal))

;;; Components

(vui-defcomponent psi-vui-session-status ()
  "Display session phase and model, queried live over RPC."
  :state ((phase  "…")
          (model  "…")
          (err    nil)
          (loaded nil))
  :render
  (vui-vstack
   (vui-text "── psi session status ──" :face 'bold)
   (vui-newline)
   (if err
       (vui-error (format "query failed: %s" err))
     (vui-vstack
      (vui-hstack
       (vui-text "phase : " :face 'font-lock-comment-face)
       (vui-text (if loaded (format "%s" phase) "querying…")))
      (vui-newline)
      (vui-hstack
       (vui-text "model : " :face 'font-lock-comment-face)
       (vui-text (if loaded (format "%s" model) "querying…")))))
   (vui-newline)
   (vui-button "↺ refresh"
               :on-click (lambda ()
                           (vui-set-state :loaded nil)
                           (vui-set-state :err    nil)
                           (psi-vui--do-query))))

  :on-mount
  (psi-vui--do-query))

(defun psi-vui--do-query ()
  "Fire query_eql and wire result back into the component via async callback."
  (let ((client (use-psi-vui-rpc-client)))
    (if (not (psi-rpc-client-p client))
        (vui-set-state :err "no rpc client in context")
      (let ((cb (vui-async-callback (frame)
                  (let* ((result (psi-vui--query-result frame))
                         (phase  (psi-vui--alist-kw result :psi.agent-session/phase))
                         (model  (psi-vui--alist-kw result :psi.agent-session/model)))
                    (if result
                        (progn
                          (vui-set-state :phase  (or phase "nil"))
                          (vui-set-state :model  (or model "nil"))
                          (vui-set-state :loaded t)
                          (vui-set-state :err    nil))
                      (vui-set-state :err "empty result"))))))
        (psi-rpc-send-request!
         client
         "query_eql"
         `((:query . "[:psi.agent-session/phase :psi.agent-session/model]"))
         cb)))))

;;; Root component — provides the context

(vui-defcomponent psi-vui-root ()
  "Root component: provides RPC client context, renders session status."
  :state ((client nil))
  :render
  (psi-vui-rpc-client-provider client
    (vui-component 'psi-vui-session-status)))

;;; Entry point

(defvar psi-vui-spike--buffer-name "*psi-vui-spike*"
  "Buffer name for the vui spike.")

;;;###autoload
(defun psi-vui-spike-open ()
  "Open the psi vui spike buffer, wired to the active psi RPC client.

Looks for a live psi-rpc client in any buffer named *psi*.  If found,
mounts the vui component tree with that client in context."
  (interactive)
  (let ((client (psi-vui-spike--find-client)))
    (unless client
      (user-error "No live psi RPC client found — open a *psi* buffer first"))
    (let ((buf (get-buffer-create psi-vui-spike--buffer-name)))
      (with-current-buffer buf
        (let ((instance (vui-mount 'psi-vui-root psi-vui-spike--buffer-name)))
          ;; Inject the client into root state after mount
          (vui-flush-sync)
          (when instance
            (let ((vui--current-instance instance)
                  (vui--root-instance instance))
              (vui-set-state :client client)))))
      (pop-to-buffer buf))))

(defun psi-vui-spike--client-from-buffer (buf)
  "Return a ready psi-rpc-client from BUF, or nil."
  (with-current-buffer buf
    (when (boundp 'psi-emacs--state)
      (let ((client (and psi-emacs--state
                         (psi-emacs-state-rpc-client psi-emacs--state))))
        (when (and (psi-rpc-client-p client)
                   (eq (psi-rpc-client-transport-state client) 'ready))
          client)))))

(defun psi-vui-spike--find-client ()
  "Return a live psi-rpc-client — current buffer first, then any *psi:...* buffer."
  (or (psi-vui-spike--client-from-buffer (current-buffer))
      (cl-some
       (lambda (buf)
         (when (string-match-p "\\*psi[*:]" (buffer-name buf))
           (psi-vui-spike--client-from-buffer buf)))
       (buffer-list))))

(provide 'psi-vui-spike)
;;; psi-vui-spike.el ends here
