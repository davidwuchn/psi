;;; psi-run-state.el --- Run-state, watchdog, and error helpers for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted run-state/status/error/watchdog helpers used by psi.el.

;;; Code:

(require 'subr-x)
(require 'psi-globals)

(defvar psi-emacs-stream-timeout-seconds)

(defun psi-emacs--status-string (state)
  "Return minimal status string for STATE."
  (format "psi [%s/%s/%s] tools:%s"
          (or (psi-emacs-state-transport-state state) 'unknown)
          (or (psi-emacs-state-process-state state) 'unknown)
          (or (psi-emacs-state-run-state state) 'idle)
          (or (psi-emacs-state-tool-output-view-mode state) 'collapsed)))

(defun psi-emacs--status-diagnostics-string (state)
  "Return status diagnostics string for STATE, including last-error summary."
  (let ((base (psi-emacs--status-string state))
        (last-error (psi-emacs-state-last-error state)))
    (if (and (stringp last-error)
             (not (string-empty-p last-error)))
      (format "%s\nlast-error: %s" base last-error)
      base)))

(defun psi-emacs--refresh-header-line ()
  "Refresh minimal header-line status for current psi buffer."
  (setq-local header-line-format
              (when psi-emacs--state
                (psi-emacs--status-string psi-emacs--state))))

(defun psi-emacs--set-run-state (state run-state)
  "Set RUN-STATE on STATE and refresh header for the current psi buffer."
  (when state
    (setf (psi-emacs-state-run-state state) run-state)
    (when (eq state psi-emacs--state)
      (psi-emacs--refresh-header-line))))

(defun psi-emacs--error-line-text (message)
  "Render deterministic persistent error line for MESSAGE."
  (format "Error: %s\n" (or message "unknown")))

(defun psi-emacs--clear-error-line (state)
  "Remove persistent error line from current buffer for STATE."
  (when state
    (let ((range (psi-emacs-state-error-line-range state)))
      (when (and (consp range)
                 (markerp (car range))
                 (markerp (cdr range))
                 (marker-buffer (car range))
                 (marker-buffer (cdr range)))
        (save-excursion
          (delete-region (car range) (cdr range))))
      (when (and (consp range) (markerp (car range)))
        (set-marker (car range) nil))
      (when (and (consp range) (markerp (cdr range)))
        (set-marker (cdr range) nil)))
    (setf (psi-emacs-state-error-line-range state) nil)))

(defun psi-emacs--upsert-error-line (state message)
  "Insert or replace persistent error line for STATE with MESSAGE."
  (when state
    (let ((follow-anchor (psi-emacs--draft-anchor-at-end-p))
          (range (psi-emacs-state-error-line-range state))
          (line-text (psi-emacs--error-line-text message)))
      (if (and (consp range)
               (markerp (car range))
               (markerp (cdr range))
               (marker-buffer (car range))
               (marker-buffer (cdr range)))
          (save-excursion
            (goto-char (car range))
            (delete-region (car range) (cdr range))
            (insert line-text)
            (set-marker (cdr range) (point)))
        (save-excursion
          (psi-emacs--ensure-newline-before-append)
          (let ((start (copy-marker (point) nil))
                (end (copy-marker (point) t)))
            (insert line-text)
            (set-marker end (point))
            (setf (psi-emacs-state-error-line-range state) (cons start end)))))
      (when follow-anchor
        (psi-emacs--set-draft-anchor-to-end)))))

(defun psi-emacs--set-last-error (state message)
  "Persist MESSAGE as STATE last error and upsert transcript error line."
  (when state
    (setf (psi-emacs-state-last-error state) message)
    (if (and (stringp message) (not (string-empty-p message)))
        (psi-emacs--upsert-error-line state message)
      (psi-emacs--clear-error-line state))
    (when (eq state psi-emacs--state)
      (psi-emacs--refresh-header-line))))

(defun psi-emacs--clear-last-error (state)
  "Clear persistent error summary/line from STATE and refresh header."
  (when state
    (setf (psi-emacs-state-last-error state) nil)
    (psi-emacs--clear-error-line state)
    (when (eq state psi-emacs--state)
      (psi-emacs--refresh-header-line))))

(defun psi-emacs--stream-watchdog-timeout-message ()
  "Return deterministic timeout message for stalled streaming."
  (format "Streaming stalled after %.0fs without progress. Aborted current run."
          psi-emacs-stream-timeout-seconds))

(defun psi-emacs--disarm-stream-watchdog (state)
  "Cancel and clear the stream watchdog timer for STATE."
  (when state
    (when-let ((timer (psi-emacs-state-stream-watchdog-timer state)))
      (cancel-timer timer))
    (setf (psi-emacs-state-stream-watchdog-timer state) nil)))

(defun psi-emacs--on-stream-watchdog-timeout (buffer state)
  "Watchdog timeout callback for BUFFER/STATE.

When streaming has stalled, abort once, append deterministic feedback,
and transition to `error'."
  (when (and (buffer-live-p buffer)
             state)
    (with-current-buffer buffer
      (when (and psi-emacs--state
                 (eq psi-emacs--state state)
                 (eq (psi-emacs-state-run-state state) 'streaming))
        (let ((timeout-message (psi-emacs--stream-watchdog-timeout-message)))
          (psi-emacs--disarm-stream-watchdog state)
          (psi-emacs--dispatch-request "abort" nil)
          (setf (psi-emacs-state-assistant-in-progress state) nil)
          (setf (psi-emacs-state-assistant-range state) nil)
          (psi-emacs--set-last-error state timeout-message)
          (psi-emacs--set-run-state state 'error))))))

(defun psi-emacs--arm-stream-watchdog (state)
  "Arm stream watchdog timer for STATE using `psi-emacs-stream-timeout-seconds'."
  (when state
    (psi-emacs--disarm-stream-watchdog state)
    (setf (psi-emacs-state-last-stream-progress-at state) (float-time))
    (setf (psi-emacs-state-stream-watchdog-timer state)
          (run-at-time psi-emacs-stream-timeout-seconds nil
                       #'psi-emacs--on-stream-watchdog-timeout
                       (current-buffer)
                       state))))

(defun psi-emacs--reset-stream-watchdog (state)
  "Record streaming progress for STATE and reset watchdog timeout window."
  (when state
    (setf (psi-emacs-state-last-stream-progress-at state) (float-time))
    (when (eq (psi-emacs-state-run-state state) 'streaming)
      (psi-emacs--arm-stream-watchdog state))))

(provide 'psi-run-state)

;;; psi-run-state.el ends here
