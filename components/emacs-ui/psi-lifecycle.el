;;; psi-lifecycle.el --- Transport + buffer lifecycle helpers for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted state initialization, RPC transport callbacks, and reconnect flow.

;;; Code:

(require 'psi-rpc)
(require 'psi-globals)

(defvar psi-emacs-command)

(declare-function make-psi-emacs-state "psi" (&rest args))

(defun psi-emacs--initialize-state (process)
  "Create initial frontend state for PROCESS ownership."
  (make-psi-emacs-state
   :process process
   :process-state (if (and process (process-live-p process)) 'running 'starting)
   :transport-state 'disconnected
   :run-state 'idle
   :last-stream-progress-at nil
   :stream-watchdog-timer nil
   :last-error nil
   :error-line-range nil
   :assistant-in-progress nil
   :assistant-range nil
   :thinking-in-progress nil
   :thinking-range nil
   :tool-rows (make-hash-table :test #'equal)
   :projection-widgets nil
   :projection-statuses nil
   :projection-footer nil
   :projection-notifications nil
   :projection-notification-seq 0
   :projection-notification-timers (make-hash-table :test #'equal)
   :projection-range nil
   :draft-anchor nil
   :rpc-client nil
   :tool-output-view-mode 'collapsed
   :session-id nil
   :session-phase nil
   :session-is-streaming nil
   :session-is-compacting nil
   :session-pending-message-count 0
   :session-retry-attempt 0
   :session-model-provider nil
   :session-model-id nil
   :session-model-reasoning nil
   :session-thinking-level nil
   :header-model-label nil
   :transcript-hydrated? nil))

(defun psi-emacs--request-initial-transcript-hydration (buffer state)
  "Request and replay initial transcript once transport is ready."
  (unless (psi-emacs-state-transcript-hydrated? state)
    (setf (psi-emacs-state-transcript-hydrated? state) t)
    (psi-emacs--dispatch-request
     "get_messages"
     nil
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (eq state psi-emacs--state)
             (psi-emacs--replay-session-messages
              (psi-emacs--frame-messages-list frame)))))))))

(defun psi-emacs--on-rpc-state-change (buffer client)
  "Apply CLIENT state changes to BUFFER-local frontend state."
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (when psi-emacs--state
        (setf (psi-emacs-state-process-state psi-emacs--state)
              (psi-rpc-client-process-state client))
        (setf (psi-emacs-state-transport-state psi-emacs--state)
              (psi-rpc-client-transport-state client))
        (setf (psi-emacs-state-process psi-emacs--state)
              (psi-rpc-client-process client))
        (when (and (eq (psi-emacs-state-run-state psi-emacs--state) 'reconnecting)
                   (eq (psi-rpc-client-transport-state client) 'ready))
          (psi-emacs--set-run-state psi-emacs--state 'idle))
        (when (eq (psi-rpc-client-transport-state client) 'ready)
          (psi-emacs--request-initial-transcript-hydration buffer psi-emacs--state))
        (setq psi-emacs--owned-process (psi-rpc-client-process client))
        (psi-emacs--refresh-header-line)))))

(defun psi-emacs--on-rpc-error (buffer code message-text frame)
  "Surface RPC error in minibuffer only for BUFFER."
  (ignore frame)
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (when psi-emacs--state
        (let ((summary (format "%s: %s" code message-text)))
          (psi-emacs--disarm-stream-watchdog psi-emacs--state)
          (psi-emacs--set-last-error psi-emacs--state summary)
          (psi-emacs--set-run-state psi-emacs--state 'error))))
    (message "psi rpc error [%s]: %s" code message-text)))

(defun psi-emacs--on-rpc-event (buffer frame)
  "Handle rpc event FRAME for BUFFER, keeping errors out of transcript."
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (let ((event (alist-get :event frame nil nil #'equal))
            (data (alist-get :data frame nil nil #'equal)))
        (if (equal event "error")
            (psi-emacs--on-rpc-error
             buffer
             (or (alist-get :code data nil nil #'equal)
                 (alist-get :error-code data nil nil #'equal)
                 "rpc/error")
             (or (alist-get :message data nil nil #'equal)
                 (alist-get :error-message data nil nil #'equal)
                 "rpc error")
             frame)
          (psi-emacs--handle-rpc-event frame))))))

(defun psi-emacs--start-rpc-client (buffer)
  "Start rpc-edn client for BUFFER and wire callbacks."
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (let ((client (psi-rpc-make-client
                     :on-state-change (lambda (rpc-client)
                                        (psi-emacs--on-rpc-state-change buffer rpc-client))
                     :on-event (lambda (frame)
                                 (psi-emacs--on-rpc-event buffer frame))
                     :on-rpc-error (lambda (code message-text frame)
                                     (psi-emacs--on-rpc-error buffer code message-text frame)))))
        (setf (psi-emacs-state-rpc-client psi-emacs--state) client)
        (psi-rpc-start! client
                        psi-emacs--spawn-process-function
                        psi-emacs-command
                        psi-rpc-default-topics)
        (setf (psi-emacs-state-process psi-emacs--state) (psi-rpc-client-process client))
        (setq psi-emacs--owned-process (psi-rpc-client-process client))
        (psi-emacs--refresh-header-line)))))

(defun psi-emacs--default-spawn-process (command)
  "Spawn psi subprocess from COMMAND.

COMMAND is a list suitable for `make-process'."
  (let* ((stderr-buffer (generate-new-buffer " *psi-rpc-stderr*"))
         (process (make-process
                   :name "psi-rpc-edn"
                   :command command
                   :buffer nil
                   :stderr stderr-buffer
                   :noquery t
                   :connection-type 'pipe)))
    (process-put process 'psi-rpc-stderr-buffer stderr-buffer)
    process))

(defun psi-emacs--default-send-request (state op params &optional callback)
  "Send OP with PARAMS using STATE rpc client, if available."
  (when-let ((client (psi-emacs-state-rpc-client state)))
    (psi-rpc-send-request! client op params callback)))

(defun psi-emacs--teardown-buffer ()
  "Stop owned subprocess and clear local/global frontend state for current buffer."
  (when (and psi-emacs--state
             (psi-rpc-client-p (psi-emacs-state-rpc-client psi-emacs--state)))
    (psi-rpc-stop! (psi-emacs-state-rpc-client psi-emacs--state)))
  (psi-emacs--disarm-stream-watchdog psi-emacs--state)
  (psi-emacs--clear-last-error psi-emacs--state)
  (when (process-live-p psi-emacs--owned-process)
    (delete-process psi-emacs--owned-process))
  (when (and psi-emacs--state
             (markerp (psi-emacs-state-draft-anchor psi-emacs--state)))
    (set-marker (psi-emacs-state-draft-anchor psi-emacs--state) nil))
  (when (and psi-emacs--state
             (consp (psi-emacs-state-assistant-range psi-emacs--state)))
    (set-marker (car (psi-emacs-state-assistant-range psi-emacs--state)) nil)
    (set-marker (cdr (psi-emacs-state-assistant-range psi-emacs--state)) nil))
  (when (and psi-emacs--state
             (consp (psi-emacs-state-thinking-range psi-emacs--state)))
    (set-marker (car (psi-emacs-state-thinking-range psi-emacs--state)) nil)
    (set-marker (cdr (psi-emacs-state-thinking-range psi-emacs--state)) nil))
  (when (and psi-emacs--state
             (consp (psi-emacs-state-projection-range psi-emacs--state)))
    (set-marker (car (psi-emacs-state-projection-range psi-emacs--state)) nil)
    (set-marker (cdr (psi-emacs-state-projection-range psi-emacs--state)) nil))
  (psi-emacs--clear-notification-lifecycle psi-emacs--state)
  (when psi-emacs--state
    (maphash (lambda (_ row)
               (when (markerp (plist-get row :start))
                 (set-marker (plist-get row :start) nil))
               (when (markerp (plist-get row :end))
                 (set-marker (plist-get row :end) nil)))
             (psi-emacs-state-tool-rows psi-emacs--state)))
  (remhash (current-buffer) psi-emacs--state-by-buffer)
  (setq-local header-line-format nil)
  (setq psi-emacs--owned-process nil)
  (setq psi-emacs--state nil))

(defun psi-emacs--install-buffer-lifecycle-hooks ()
  "Install local lifecycle hooks for dedicated psi buffer."
  (add-hook 'kill-buffer-hook #'psi-emacs--teardown-buffer nil t))

(defun psi-emacs--buffer-modified-p ()
  "Return non-nil when current buffer has pending user edits."
  (buffer-modified-p))

(defun psi-emacs--confirm-reconnect-p ()
  "Return non-nil when reconnect should proceed."
  (or (not (psi-emacs--buffer-modified-p))
      (yes-or-no-p "Buffer has unsaved edits. Reconnect and clear buffer? ")))

(defun psi-emacs--reset-transcript-state (&optional preserve-tool-output-view-mode)
  "Clear transcript buffer and reset in-buffer rendering state.

When PRESERVE-TOOL-OUTPUT-VIEW-MODE is non-nil, keep the current
`tool-output-view-mode' (used by /new). Otherwise reset to default
`collapsed' (used by reconnect)."
  (let ((inhibit-read-only t))
    (erase-buffer))
  (when psi-emacs--state
    (psi-emacs--disarm-stream-watchdog psi-emacs--state)
    (psi-emacs--clear-last-error psi-emacs--state)
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
    (setf (psi-emacs-state-assistant-range psi-emacs--state) nil)
    (setf (psi-emacs-state-thinking-in-progress psi-emacs--state) nil)
    (setf (psi-emacs-state-thinking-range psi-emacs--state) nil)
    (clrhash (psi-emacs-state-tool-rows psi-emacs--state))
    (setf (psi-emacs-state-projection-widgets psi-emacs--state) nil)
    (setf (psi-emacs-state-projection-statuses psi-emacs--state) nil)
    (setf (psi-emacs-state-projection-footer psi-emacs--state) nil)
    (psi-emacs--clear-notification-lifecycle psi-emacs--state)
    (when (consp (psi-emacs-state-projection-range psi-emacs--state))
      (set-marker (car (psi-emacs-state-projection-range psi-emacs--state)) nil)
      (set-marker (cdr (psi-emacs-state-projection-range psi-emacs--state)) nil))
    (setf (psi-emacs-state-projection-range psi-emacs--state) nil)
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (unless preserve-tool-output-view-mode
      (setf (psi-emacs-state-tool-output-view-mode psi-emacs--state) 'collapsed))
    (setf (psi-emacs-state-session-id psi-emacs--state) nil)
    (setf (psi-emacs-state-session-phase psi-emacs--state) nil)
    (setf (psi-emacs-state-session-is-streaming psi-emacs--state) nil)
    (setf (psi-emacs-state-session-is-compacting psi-emacs--state) nil)
    (setf (psi-emacs-state-session-pending-message-count psi-emacs--state) 0)
    (setf (psi-emacs-state-session-retry-attempt psi-emacs--state) 0)
    (setf (psi-emacs-state-session-model-provider psi-emacs--state) nil)
    (setf (psi-emacs-state-session-model-id psi-emacs--state) nil)
    (setf (psi-emacs-state-session-model-reasoning psi-emacs--state) nil)
    (setf (psi-emacs-state-session-thinking-level psi-emacs--state) nil)
    (setf (psi-emacs-state-header-model-label psi-emacs--state) nil)
    (setf (psi-emacs-state-transcript-hydrated? psi-emacs--state) nil)
    (psi-emacs--set-run-state psi-emacs--state 'idle)
    (psi-emacs--refresh-header-line))
  (set-buffer-modified-p nil))

(defun psi-emacs-reconnect ()
  "Manually reconnect by clearing transcript and starting a fresh rpc session."
  (interactive)
  (if (not psi-emacs--state)
      (user-error "psi buffer is not initialized")
    (when (psi-emacs--confirm-reconnect-p)
      (when (and (psi-emacs-state-rpc-client psi-emacs--state)
                 (psi-rpc-client-p (psi-emacs-state-rpc-client psi-emacs--state)))
        (psi-rpc-stop! (psi-emacs-state-rpc-client psi-emacs--state)))
      (when (process-live-p psi-emacs--owned-process)
        (delete-process psi-emacs--owned-process))
      (psi-emacs--reset-transcript-state)
      (setf (psi-emacs-state-transport-state psi-emacs--state) 'disconnected)
      (setf (psi-emacs-state-process-state psi-emacs--state) 'starting)
      (psi-emacs--set-run-state psi-emacs--state 'reconnecting)
      (psi-emacs--refresh-header-line)
      (psi-emacs--start-rpc-client (current-buffer)))))

(provide 'psi-lifecycle)

;;; psi-lifecycle.el ends here
