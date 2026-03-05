;;; psi-session-commands.el --- Idle slash commands and session switching for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted idle slash command routing and session lifecycle helpers used by psi.el.

;;; Code:

(require 'subr-x)
(require 'psi-globals)

(defun psi-emacs--idle-slash-help-text ()
  "Return deterministic help text for supported idle slash commands."
  (string-join
   (list "Supported slash commands:"
         "/quit, /exit  Exit this psi buffer"
         "/resume [path] Resume a prior session (selector when path omitted)"
         "/new          Start a fresh backend session"
         "/status       Show frontend diagnostics"
         "/help, /?     Show this help")
   "\n"))

(defun psi-emacs--reset-transcript-for-new-session ()
  "Clear transcript rendering state for a successful /new command."
  (let ((inhibit-read-only t))
    (erase-buffer))
  (when psi-emacs--state
    (psi-emacs--disarm-stream-watchdog psi-emacs--state)
    (psi-emacs--clear-last-error psi-emacs--state)
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
    (setf (psi-emacs-state-assistant-range psi-emacs--state) nil)
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
    (psi-emacs--set-run-state psi-emacs--state 'idle)
    (psi-emacs--refresh-header-line))
  (set-buffer-modified-p nil))

(defun psi-emacs--new-session-error-message (frame)
  "Return deterministic /new error text derived from FRAME."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (details (or (alist-get :error-message frame nil nil #'equal)
                      (and (listp data)
                           (or (alist-get :error-message data nil nil #'equal)
                               (alist-get :message data nil nil #'equal))))))
    (if (and (stringp details) (not (string-empty-p details)))
        (format "Unable to start a fresh backend session: %s" details)
      "Unable to start a fresh backend session.")))

(defun psi-emacs--handle-new-session-response (frame)
  "Apply /new callback FRAME effects to the current frontend buffer."
  (if (and (eq (alist-get :kind frame) :response)
           (eq (alist-get :ok frame) t))
      (progn
        (psi-emacs--reset-transcript-for-new-session)
        (psi-emacs--append-assistant-message "Started a fresh backend session."))
    (psi-emacs--append-assistant-message
     (psi-emacs--new-session-error-message frame))))

(defun psi-emacs--request-new-session ()
  "Request a fresh backend session for /new and render deterministic feedback."
  (let ((buffer (current-buffer)))
    (psi-emacs--dispatch-request
     "new_session"
     nil
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (psi-emacs--handle-new-session-response frame)))))))

(defun psi-emacs--resume-args-from-message (message)
  "Extract `/resume` argument tail from MESSAGE.

Return nil for no argument. Otherwise return trimmed argument string."
  (let* ((trimmed (string-trim (or message "")))
         (tail (string-trim (string-remove-prefix "/resume" trimmed))))
    (unless (string-empty-p tail)
      tail)))

(defun psi-emacs--resume-session-list-query ()
  "Return canonical EQL query string for `/resume` session discovery."
  "[{:psi.session/list [:psi.session-info/path
                        :psi.session-info/name
                        :psi.session-info/first-message
                        :psi.session-info/modified]}]")

(defun psi-emacs--resume-session-list-from-query-frame (frame)
  "Extract session list vector from `query_eql` FRAME."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (result (and (listp data) (alist-get :result data nil nil #'equal)))
         (sessions (and (listp result)
                        (alist-get :psi.session/list result nil nil #'equal))))
    (cond
     ((vectorp sessions) (append sessions nil))
     ((listp sessions) sessions)
     (t nil))))

(defun psi-emacs--resume-session-description (session)
  "Return description-first label seed for SESSION."
  (let ((name (string-trim (or (alist-get :psi.session-info/name session nil nil #'equal) "")))
        (first-message (string-trim (or (alist-get :psi.session-info/first-message session nil nil #'equal) "")))
        (path (string-trim (or (alist-get :psi.session-info/path session nil nil #'equal) ""))))
    (cond
     ((not (string-empty-p name)) name)
     ((not (string-empty-p first-message)) first-message)
     ((not (string-empty-p path)) (file-name-nondirectory path))
     (t "(unnamed session)"))))

(defun psi-emacs--resume-session-modified-seconds (session)
  "Return SESSION modified timestamp as seconds since epoch.

Unreadable/missing timestamps normalize to 0."
  (let ((modified (alist-get :psi.session-info/modified session nil nil #'equal)))
    (cond
     ((numberp modified) (float modified))
     ((stringp modified)
      (or (ignore-errors (float-time (date-to-time modified))) 0.0))
     (t
      (or (ignore-errors (float-time modified)) 0.0)))))

(defun psi-emacs--resume-session-path (session)
  "Return trimmed canonical session path for SESSION, or empty string."
  (string-trim (or (alist-get :psi.session-info/path session nil nil #'equal) "")))

(defun psi-emacs--sort-resume-sessions (sessions)
  "Sort SESSIONS by modified desc (newest first), then path asc."
  (sort (copy-sequence sessions)
        (lambda (a b)
          (let ((am (psi-emacs--resume-session-modified-seconds a))
                (bm (psi-emacs--resume-session-modified-seconds b))
                (ap (psi-emacs--resume-session-path a))
                (bp (psi-emacs--resume-session-path b)))
            (if (/= am bm)
                (> am bm)
              (string< ap bp))))))

(defun psi-emacs--resume-session-candidates (sessions)
  "Build deterministic selector candidates from SESSIONS.

Returns list of cons cells (DISPLAY . CANONICAL-PATH)."
  (let ((seen (make-hash-table :test #'equal))
        (candidates nil))
    (dolist (session (psi-emacs--sort-resume-sessions sessions))
      (let ((path (psi-emacs--resume-session-path session)))
        (when (not (string-empty-p path))
          (let* ((description (psi-emacs--resume-session-description session))
                 (base (format "%s — %s" description path))
                 (count (1+ (gethash base seen 0)))
                 (label (if (= count 1)
                            base
                          (format "%s (%d)" base count))))
            (puthash base count seen)
            (push (cons label path) candidates)))))
    (nreverse candidates)))

(defun psi-emacs--resume-select-session-path (candidates)
  "Prompt for session selection from CANDIDATES.

CANDIDATES is a list of (DISPLAY . CANONICAL-PATH).
Returns canonical path string, or nil when cancelled/no selection."
  (condition-case _
      (let* ((labels (mapcar #'car candidates))
             (chosen (completing-read "Resume session: " labels nil t)))
        (when (and (stringp chosen)
                   (not (string-empty-p chosen)))
          (cdr (assoc chosen candidates))))
    (quit nil)))

(defun psi-emacs--request-resume-session-list (callback)
  "Fetch session list via `query_eql` and invoke CALLBACK with response frame."
  (psi-emacs--dispatch-request
   "query_eql"
   `((:query . ,(psi-emacs--resume-session-list-query)))
   callback))

(defun psi-emacs--rpc-frame-success-p (frame)
  "Return non-nil when FRAME is a successful RPC response."
  (and (eq (alist-get :kind frame) :response)
       (eq (alist-get :ok frame) t)))

(defun psi-emacs--frame-messages-list (frame)
  "Extract `:messages` list from FRAME payload.

Returns a proper list in canonical order, or nil when missing/unreadable."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (messages (and (listp data)
                        (alist-get :messages data nil nil #'equal))))
    (cond
     ((vectorp messages) (append messages nil))
     ((listp messages) messages)
     (t nil))))

(defun psi-emacs--message-text-from-content (content)
  "Extract display text from message CONTENT payload."
  (cond
   ((stringp content) content)
   ((and (listp content)
         (or (alist-get :text content nil nil #'equal)
             (alist-get 'text content nil nil #'equal)))
    (or (alist-get :text content nil nil #'equal)
        (alist-get 'text content nil nil #'equal)
        ""))
   (t (psi-emacs--assistant-content->text content))))

(defun psi-emacs--message->transcript-line (message)
  "Render MESSAGE as one deterministic transcript line."
  (let* ((role-raw (or (alist-get :role message nil nil #'equal)
                       (alist-get 'role message nil nil #'equal)
                       :assistant))
         (role (if (stringp role-raw) (intern role-raw) role-raw))
         (content (or (alist-get :content message nil nil #'equal)
                      (alist-get 'content message nil nil #'equal)))
         (text (or (alist-get :text message nil nil #'equal)
                   (alist-get 'text message nil nil #'equal)
                   (alist-get :message message nil nil #'equal)
                   (alist-get 'message message nil nil #'equal)
                   (psi-emacs--message-text-from-content content)
                   "")))
    (format "%s: %s\n"
            (if (eq role :user) "User" "ψ")
            text)))

(defun psi-emacs--replay-session-messages (messages)
  "Replay MESSAGES into transcript in deterministic input order."
  (let ((follow-anchor (psi-emacs--draft-anchor-at-end-p)))
    (save-excursion
      (goto-char (point-max))
      (dolist (message messages)
        (when (listp message)
          (insert (psi-emacs--message->transcript-line message)))))
    (when follow-anchor
      (psi-emacs--set-draft-anchor-to-end))))

(defun psi-emacs--request-get-messages-for-switch (state)
  "Request `get_messages` and replay transcript for switched STATE."
  (let ((buffer (current-buffer)))
    (psi-emacs--dispatch-request
     "get_messages"
     nil
     (lambda (messages-frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (eq state psi-emacs--state)
             (psi-emacs--replay-session-messages
              (psi-emacs--frame-messages-list messages-frame))
             (psi-emacs--set-run-state state 'idle)
             (psi-emacs--refresh-header-line))))))))

(defun psi-emacs--switch-session-error-message (frame)
  "Return deterministic `/resume` switch failure text derived from FRAME."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (details (or (alist-get :error-message frame nil nil #'equal)
                      (alist-get :message frame nil nil #'equal)
                      (and (listp data)
                           (or (alist-get :error-message data nil nil #'equal)
                               (alist-get :message data nil nil #'equal))))))
    (if (and (stringp details) (not (string-empty-p details)))
        (format "Unable to switch session: %s" details)
      "Unable to switch session.")))

(defun psi-emacs--handle-switch-session-response (state _session-path frame)
  "Handle `switch_session` callback FRAME for STATE.

Success path clears stale transcript/render state, then requests and replays
messages for deterministic rehydration.

Failure path appends deterministic assistant-visible feedback, sets
`last-error`, and does not run success-only side effects."
  (when (and state (eq state psi-emacs--state))
    (if (psi-emacs--rpc-frame-success-p frame)
        (progn
          (psi-emacs--reset-transcript-state)
          (psi-emacs--set-run-state state 'streaming)
          (psi-emacs--request-get-messages-for-switch state))
      (let ((message (psi-emacs--switch-session-error-message frame)))
        (psi-emacs--append-assistant-message message)
        (psi-emacs--set-last-error state message)))))

(defun psi-emacs--request-switch-session (state session-path)
  "Dispatch `switch_session` for SESSION-PATH from STATE."
  (when (and state
             (stringp session-path)
             (not (string-empty-p session-path)))
    (let ((buffer (current-buffer)))
      (psi-emacs--dispatch-request
       "switch_session"
       `((:session-path . ,session-path))
       (lambda (frame)
         (when (buffer-live-p buffer)
           (with-current-buffer buffer
             (when (eq state psi-emacs--state)
               (psi-emacs--handle-switch-session-response state session-path frame)))))))))

(defun psi-emacs--handle-idle-resume-no-arg (state)
  "Handle `/resume` without explicit session path."
  (let ((buffer (current-buffer)))
    (psi-emacs--request-resume-session-list
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (eq state psi-emacs--state)
             (let* ((sessions (psi-emacs--resume-session-list-from-query-frame frame))
                    (candidates (psi-emacs--resume-session-candidates sessions))
                    (selected-path (psi-emacs--resume-select-session-path candidates)))
               (when selected-path
                 (psi-emacs--handle-idle-resume-explicit-path state selected-path))))))))))

(defun psi-emacs--handle-idle-resume-explicit-path (state session-path)
  "Handle `/resume <session-path>`."
  (psi-emacs--request-switch-session state session-path))

(defun psi-emacs--handle-idle-resume-command (state message)
  "Handle `/resume` MESSAGE by path (when provided) or interactive selector."
  (let ((session-path (psi-emacs--resume-args-from-message message)))
    (if session-path
        (psi-emacs--handle-idle-resume-explicit-path state session-path)
      (psi-emacs--handle-idle-resume-no-arg state))))

(defun psi-emacs--default-handle-idle-slash-command (state message)
  "Default idle slash handler.

Return non-nil when MESSAGE is handled and should not fall through to
normal prompt dispatch."
  (let* ((trimmed (string-trim (or message "")))
         (command (car (split-string trimmed "[ \t\n\r]+" t))))
    (pcase command
      ((or "/quit" "/exit")
       (psi-emacs--request-frontend-exit)
       t)
      ("/resume"
       (psi-emacs--handle-idle-resume-command state message)
       t)
      ("/new"
       (psi-emacs--request-new-session)
       t)
      ("/status"
       (psi-emacs--append-assistant-message
        (psi-emacs--status-diagnostics-string state))
       t)
      ((or "/help" "/?")
       (psi-emacs--append-assistant-message
        (psi-emacs--idle-slash-help-text))
       t)
      (_ nil))))

(defun psi-emacs--slash-command-candidate-p (message)
  "Return non-nil when MESSAGE is a slash command candidate."
  (let ((trimmed (string-trim (or message ""))))
    (and (not (string-empty-p trimmed))
         (string-prefix-p "/" trimmed))))

(defun psi-emacs--dispatch-idle-compose-message (message)
  "Dispatch idle compose MESSAGE through slash interception and fallback.

When MESSAGE is a slash command candidate, run
`psi-emacs--idle-slash-command-handler-function'. If not handled, fall through
to normal prompt dispatch.

Returns non-nil when MESSAGE was consumed locally or dispatched remotely."
  (let ((handled (and psi-emacs--state
                      (psi-emacs--slash-command-candidate-p message)
                      (funcall psi-emacs--idle-slash-command-handler-function
                               psi-emacs--state
                               message))))
    (if handled
        t
      (let ((sent? (psi-emacs--dispatch-request "prompt" `((:message . ,message)))))
        (when sent?
          (psi-emacs--set-run-state psi-emacs--state 'streaming)
          (psi-emacs--reset-stream-watchdog psi-emacs--state))
        sent?))))

(provide 'psi-session-commands)

;;; psi-session-commands.el ends here
