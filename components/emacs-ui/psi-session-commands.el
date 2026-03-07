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
         "/model [provider model-id]    Open model selector or set directly"
         "/thinking [level]             Open thinking selector or set directly"
         "/help, /?     Show this help")
   "\n"))

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

(defun psi-emacs--handle-new-session-response (state frame)
  "Apply /new callback FRAME effects to the current frontend buffer."
  (if (and (eq (alist-get :kind frame) :response)
           (eq (alist-get :ok frame) t))
      (when (and state (eq state psi-emacs--state))
        ;; /new is a non-reconnect session operation, so keep current tool view.
        ;; Clear stale transcript state, then fetch canonical messages so startup
        ;; prompts are replayed in the frontend transcript.
        (psi-emacs--reset-transcript-state t)
        (psi-emacs--set-run-state state 'streaming)
        (psi-emacs--request-get-messages-for-switch state))
    (psi-emacs--append-assistant-message
     (psi-emacs--new-session-error-message frame))))

(defun psi-emacs--request-new-session (state)
  "Request a fresh backend session for /new and rehydrate transcript for STATE."
  (let ((buffer (current-buffer)))
    (psi-emacs--dispatch-request
     "new_session"
     nil
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (psi-emacs--handle-new-session-response state frame)))))))

(defun psi-emacs--session-model-default-provider ()
  "Return current session model provider for interactive defaults."
  (or (and psi-emacs--state
           (psi-emacs-state-session-model-provider psi-emacs--state))
      ""))

(defun psi-emacs--session-model-default-id ()
  "Return current session model id for interactive defaults."
  (or (and psi-emacs--state
           (psi-emacs-state-session-model-id psi-emacs--state))
      ""))

(defun psi-emacs--session-thinking-level-default-text ()
  "Return current session thinking level text for interactive defaults."
  (let ((level (and psi-emacs--state
                    (psi-emacs-state-session-thinking-level psi-emacs--state))))
    (if level
        (format "%s" level)
      "")))

(defun psi-emacs--trim-required-input (label value)
  "Return trimmed VALUE text; raise user error when blank for LABEL."
  (let ((text (string-trim (format "%s" (or value "")))))
    (when (string-empty-p text)
      (user-error "%s is required" label))
    text))

(defun psi-emacs-set-model (provider model-id)
  "Select PROVIDER/MODEL-ID via `set_model` RPC op."
  (interactive
   (list (read-string "Model provider: " (psi-emacs--session-model-default-provider))
         (read-string "Model id: " (psi-emacs--session-model-default-id))))
  (let ((provider* (psi-emacs--trim-required-input "Model provider" provider))
        (model-id* (psi-emacs--trim-required-input "Model id" model-id)))
    (when (psi-emacs--dispatch-request
           "set_model"
           `((:provider . ,provider*)
             (:model-id . ,model-id*)))
      (message "psi: requested model (%s) %s" provider* model-id*))))

(defun psi-emacs-cycle-model (&optional direction)
  "Cycle model in DIRECTION (`next` or `prev`) via `cycle_model` RPC."
  (interactive
   (list (completing-read "Cycle model direction: " '("next" "prev") nil t nil nil "next")))
  (let ((direction* (string-trim (format "%s" (or direction "next")))))
    (unless (member direction* '("next" "prev"))
      (user-error "Direction must be \"next\" or \"prev\""))
    (when (psi-emacs--dispatch-request
           "cycle_model"
           `((:direction . ,direction*)))
      (message "psi: requested model cycle (%s)" direction*))))

(defun psi-emacs-cycle-model-next ()
  "Cycle to the next available model via `cycle_model`."
  (interactive)
  (psi-emacs-cycle-model "next"))

(defun psi-emacs-cycle-model-prev ()
  "Cycle to the previous available model via `cycle_model`."
  (interactive)
  (psi-emacs-cycle-model "prev"))

(defun psi-emacs-set-thinking-level (level)
  "Set thinking LEVEL via `set_thinking_level` RPC op."
  (interactive
   (list (read-string "Thinking level: " (psi-emacs--session-thinking-level-default-text))))
  (let ((level* (psi-emacs--trim-required-input "Thinking level" level)))
    (when (psi-emacs--dispatch-request
           "set_thinking_level"
           `((:level . ,level*)))
      (message "psi: requested thinking level %s" level*))))

(defun psi-emacs-cycle-thinking-level ()
  "Cycle thinking level via `cycle_thinking_level` RPC op."
  (interactive)
  (when (psi-emacs--dispatch-request "cycle_thinking_level" nil)
    (message "psi: requested thinking level cycle")))

(defun psi-emacs--slash-command-args (message)
  "Return MESSAGE slash command tail as token list."
  (cdr (split-string (string-trim (or message "")) "[ \t\n\r]+" t)))

(defun psi-emacs--handle-idle-model-command (_state message)
  "Handle idle `/model` MESSAGE."
  (let* ((args (psi-emacs--slash-command-args message))
         (argc (length args)))
    (cond
     ((= argc 0)
      (call-interactively #'psi-emacs-set-model))
     ((= argc 2)
      (let ((provider (nth 0 args))
            (model-id (nth 1 args)))
        (when (psi-emacs--dispatch-request
               "set_model"
               `((:provider . ,provider)
                 (:model-id . ,model-id)))
          (message "psi: requested model (%s) %s" provider model-id))))
     (t
      (psi-emacs--append-assistant-message
       "Usage: /model OR /model <provider> <model-id>")))))

(defun psi-emacs--handle-idle-thinking-command (_state message)
  "Handle idle `/thinking` MESSAGE."
  (let* ((args (psi-emacs--slash-command-args message))
         (argc (length args)))
    (cond
     ((= argc 0)
      (call-interactively #'psi-emacs-set-thinking-level))
     ((= argc 1)
      (let ((level (car args)))
        (when (psi-emacs--dispatch-request
               "set_thinking_level"
               `((:level . ,level)))
          (message "psi: requested thinking level %s" level))))
     (t
      (psi-emacs--append-assistant-message
       "Usage: /thinking OR /thinking <level>")))))

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
          (let* ((role-raw (or (alist-get :role message nil nil #'equal)
                               (alist-get 'role message nil nil #'equal)
                               :assistant))
                 (role (if (stringp role-raw) (intern role-raw) role-raw))
                 (line-start (point)))
            (insert (psi-emacs--message->transcript-line message))
            (save-excursion
              (goto-char line-start)
              (if (eq role :user)
                  (psi-emacs--apply-prefix-overlay line-start "User: " 'psi-emacs-user-prompt-face)
                (psi-emacs--apply-prefix-overlay line-start "ψ: " 'psi-emacs-assistant-reply-face)))))))
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
       (psi-emacs--request-new-session state)
       t)
      ("/status"
       (psi-emacs--append-assistant-message
        (psi-emacs--status-diagnostics-string state))
       t)
      ("/model"
       (psi-emacs--handle-idle-model-command state message)
       t)
      ("/thinking"
       (psi-emacs--handle-idle-thinking-command state message)
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

Returns plist:
  :dispatched?  non-nil when consumed locally or dispatched remotely
  :local-only?  non-nil when consumed by local slash interception." 
  (let ((handled (and psi-emacs--state
                      (psi-emacs--slash-command-candidate-p message)
                      (funcall psi-emacs--idle-slash-command-handler-function
                               psi-emacs--state
                               message))))
    (if handled
        (list :dispatched? t :local-only? t)
      (let ((sent? (psi-emacs--dispatch-request "prompt" `((:message . ,message)))))
        (when sent?
          (psi-emacs--set-run-state psi-emacs--state 'streaming)
          (psi-emacs--reset-stream-watchdog psi-emacs--state))
        (list :dispatched? sent? :local-only? nil)))))

(provide 'psi-session-commands)

;;; psi-session-commands.el ends here
