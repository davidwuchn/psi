;;; psi-compose.el --- Compose input + send/queue/abort flow for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted compose-region/tail-draft helpers and send routing used by psi.el.

;;; Code:

(defun psi-emacs--streaming-p ()
  "Return non-nil when the frontend is in streaming mode."
  (and psi-emacs--state
       (eq (psi-emacs-state-run-state psi-emacs--state) 'streaming)))

(defun psi-emacs--draft-end-position ()
  "Return end position of editable draft region.

When a projection/footer block is present, draft ends at projection start.
Otherwise, draft ends at `point-max'."
  (let* ((range (and psi-emacs--state
                     (psi-emacs-state-projection-range psi-emacs--state)))
         (projection-start (and (consp range) (car range))))
    (if (and (markerp projection-start)
             (marker-buffer projection-start))
        (marker-position projection-start)
      (point-max))))

(defun psi-emacs--tail-draft-text ()
  "Return compose text from draft anchor to draft end boundary."
  (let* ((anchor (and psi-emacs--state
                      (psi-emacs-state-draft-anchor psi-emacs--state)))
         (start (if (and (markerp anchor) (marker-buffer anchor))
                    (marker-position anchor)
                  (psi-emacs--draft-end-position)))
         (end   (psi-emacs--draft-end-position)))
    (buffer-substring-no-properties (min start end) end)))

(defun psi-emacs--composed-text ()
  "Return composed text using region-first, else tail draft."
  (if (use-region-p)
      (buffer-substring-no-properties (region-beginning) (region-end))
    (psi-emacs--tail-draft-text)))

(defun psi-emacs--draft-anchor-valid-p ()
  "Return non-nil when the current draft anchor marker is valid."
  (and psi-emacs--state
       (markerp (psi-emacs-state-draft-anchor psi-emacs--state))
       (marker-buffer (psi-emacs-state-draft-anchor psi-emacs--state))))

(defun psi-emacs--draft-anchor-at-end-p ()
  "Return non-nil when draft anchor is currently at draft end boundary."
  (and (psi-emacs--draft-anchor-valid-p)
       (= (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))
          (psi-emacs--draft-end-position))))

(defun psi-emacs--set-draft-anchor-to-end ()
  "Move/create draft anchor at draft end boundary."
  (when psi-emacs--state
    (let ((anchor (psi-emacs-state-draft-anchor psi-emacs--state))
          (end-pos (psi-emacs--draft-end-position)))
      (if (and (markerp anchor) (marker-buffer anchor))
          (set-marker anchor end-pos)
        (setf (psi-emacs-state-draft-anchor psi-emacs--state)
              (copy-marker end-pos nil))))))

(defun psi-emacs--consume-tail-draft (used-region-p)
  "Advance draft anchor when tail draft was consumed.

USED-REGION-P non-nil means compose came from region and anchor is untouched."
  (unless used-region-p
    (psi-emacs--set-draft-anchor-to-end)))

(defun psi-emacs--dispatch-request (op params &optional callback)
  "Dispatch OP PARAMS CALLBACK through configured request function."
  (when psi-emacs--state
    (funcall psi-emacs--send-request-function psi-emacs--state op params callback)))

(defun psi-emacs--request-frontend-exit ()
  "Request frontend exit for current psi buffer."
  (when (buffer-live-p (current-buffer))
    (kill-buffer (current-buffer))))

(defun psi-emacs--append-assistant-message (text)
  "Append assistant TEXT as a finalized transcript line."
  (psi-emacs--assistant-finalize text))

(defun psi-emacs-send-from-buffer (prefix)
  "Send composed text using canonical send semantics.

With PREFIX while streaming, queue override is used.
Without PREFIX while streaming, steer is used.
When idle, routes through slash interception then normal prompt fallback."
  (interactive "P")
  (let* ((used-region-p (use-region-p))
         (message (psi-emacs--composed-text)))
    (if (psi-emacs--streaming-p)
        (progn
          (psi-emacs--set-run-state psi-emacs--state 'streaming)
          (psi-emacs--reset-stream-watchdog psi-emacs--state)
          (psi-emacs--dispatch-request
           "prompt_while_streaming"
           `((:message . ,message)
             (:behavior . ,(if prefix "queue" "steer")))))
      (psi-emacs--dispatch-idle-compose-message message))
    (psi-emacs--consume-tail-draft used-region-p)))

(defun psi-emacs-queue-from-buffer ()
  "Queue composed text while streaming; use idle slash dispatch when idle."
  (interactive)
  (let* ((used-region-p (use-region-p))
         (message (psi-emacs--composed-text)))
    (if (psi-emacs--streaming-p)
        (progn
          (psi-emacs--set-run-state psi-emacs--state 'streaming)
          (psi-emacs--reset-stream-watchdog psi-emacs--state)
          (psi-emacs--dispatch-request
           "prompt_while_streaming"
           `((:message . ,message)
             (:behavior . "queue"))))
      (psi-emacs--dispatch-idle-compose-message message))
    (psi-emacs--consume-tail-draft used-region-p)))

(defun psi-emacs-abort ()
  "Abort active streaming request via RPC and transition to non-streaming UI state."
  (interactive)
  (psi-emacs--dispatch-request "abort" nil)
  (when psi-emacs--state
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
    (setf (psi-emacs-state-assistant-range psi-emacs--state) nil)
    (psi-emacs--disarm-stream-watchdog psi-emacs--state)
    (psi-emacs--set-run-state psi-emacs--state 'idle)))

(defun psi-emacs--ensure-newline-before-append ()
  "Ensure transcript append boundary starts on a fresh line.

When a projection/footer block exists, append before that block so transcript
content (assistant/tool/error rows) never renders below the footer."
  (goto-char (psi-emacs--draft-end-position))
  (unless (or (bobp)
              (eq (char-before) ?\n))
    (insert "\n")))

(provide 'psi-compose)

;;; psi-compose.el ends here
