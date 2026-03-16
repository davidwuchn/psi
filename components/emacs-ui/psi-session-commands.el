;;; psi-session-commands.el --- Slash commands and session switching for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted slash command routing and session lifecycle helpers used by psi.el.

;;; Code:

(require 'subr-x)
(require 'psi-globals)

(defcustom psi-emacs-model-selector-provider-scope 'all
  "Provider scope used by `psi-emacs-set-model` when opening the model picker.

When set to `all`, the picker lists all runtime models.
When set to `authenticated`, the picker only lists models whose provider
appears in `:psi.agent-session/authenticated-providers`."
  :type '(choice (const :tag "All providers" all)
                 (const :tag "Providers with configured auth" authenticated))
  :group 'psi-emacs)

(defun psi-emacs--trim-optional-input (value)
  "Return trimmed VALUE text, or nil when VALUE is blank."
  (let ((text (string-trim (format "%s" (or value "")))))
    (unless (string-empty-p text)
      text)))

(defun psi-emacs--normalize-provider-id (value)
  "Return canonical provider id string for VALUE, or nil."
  (when-let ((text (psi-emacs--trim-optional-input value)))
    (downcase (string-remove-prefix ":" text))))

(defun psi-emacs--alist-get-any (alist keys)
  "Return first non-nil value in ALIST for any of KEYS."
  (let ((value nil))
    (while (and keys (null value))
      (setq value (alist-get (car keys) alist nil nil #'equal)
            keys (cdr keys)))
    value))

(defun psi-emacs--model-provider (model)
  "Return normalized provider id text from MODEL entry."
  (psi-emacs--normalize-provider-id
   (psi-emacs--alist-get-any model '(:provider provider :model-provider model-provider))))

(defun psi-emacs--model-id (model)
  "Return normalized model id text from MODEL entry."
  (psi-emacs--trim-optional-input
   (psi-emacs--alist-get-any model '(:id id :model-id model-id))))

(defun psi-emacs--model-name (model)
  "Return optional display name text from MODEL entry."
  (psi-emacs--trim-optional-input
   (psi-emacs--alist-get-any model '(:name name))))

(defun psi-emacs--model-reasoning-p (model)
  "Return non-nil when MODEL reports reasoning support."
  (not (null (psi-emacs--alist-get-any
              model
              '(:reasoning reasoning :supports-reasoning supports-reasoning)))))

(defun psi-emacs--model-selector-query ()
  "Return canonical EQL query string for `/model` selector data."
  "[:psi.agent-session/model-catalog
    :psi.agent-session/authenticated-providers]")

(defun psi-emacs--query-result-from-frame (frame)
  "Extract `query_eql` result payload map from FRAME."
  (let ((data (alist-get :data frame nil nil #'equal)))
    (and (listp data)
         (alist-get :result data nil nil #'equal))))

(defun psi-emacs--model-catalog-from-query-frame (frame)
  "Extract model catalog list from `query_eql` FRAME."
  (let* ((result (psi-emacs--query-result-from-frame frame))
         (catalog (and (listp result)
                       (alist-get :psi.agent-session/model-catalog result nil nil #'equal))))
    (cond
     ((vectorp catalog) (append catalog nil))
     ((listp catalog) catalog)
     (t nil))))

(defun psi-emacs--authenticated-providers-from-query-frame (frame)
  "Extract authenticated provider id list from `query_eql` FRAME."
  (let* ((result (psi-emacs--query-result-from-frame frame))
         (providers (and (listp result)
                         (alist-get :psi.agent-session/authenticated-providers result nil nil #'equal))))
    (cond
     ((vectorp providers) (append providers nil))
     ((listp providers) providers)
     (t nil))))

(defun psi-emacs--normalize-provider-list (providers)
  "Return deduplicated normalized provider id list from PROVIDERS."
  (delete-dups
   (delq nil (mapcar #'psi-emacs--normalize-provider-id providers))))

(defun psi-emacs--filter-model-catalog (catalog authenticated-providers)
  "Return filtered CATALOG using AUTHENTICATED-PROVIDERS and user scope setting."
  (if (eq psi-emacs-model-selector-provider-scope 'authenticated)
      (let ((allowed (psi-emacs--normalize-provider-list authenticated-providers))
            (out nil))
        (dolist (model catalog)
          (when (member (psi-emacs--model-provider model) allowed)
            (push model out)))
        (nreverse out))
    catalog))

(defun psi-emacs--sort-model-catalog (catalog)
  "Return CATALOG sorted by provider id then model id."
  (sort (copy-sequence catalog)
        (lambda (a b)
          (let ((ap (or (psi-emacs--model-provider a) ""))
                (bp (or (psi-emacs--model-provider b) ""))
                (ai (or (psi-emacs--model-id a) ""))
                (bi (or (psi-emacs--model-id b) "")))
            (if (string= ap bp)
                (string< ai bi)
              (string< ap bp))))))

(defun psi-emacs--model-candidate-label (model)
  "Return deterministic completion label for MODEL entry."
  (let* ((provider (or (psi-emacs--model-provider model) "?"))
         (model-id (or (psi-emacs--model-id model) "?"))
         (name (psi-emacs--model-name model))
         (reasoning? (psi-emacs--model-reasoning-p model))
         (name-part (if name (format " — %s" name) ""))
         (reasoning-part (if reasoning? " • reasoning" "")))
    (format "(%s) %s%s%s" provider model-id name-part reasoning-part)))

(defun psi-emacs--model-selector-candidates (catalog)
  "Build deterministic completion candidates from model CATALOG.

Return list of (DISPLAY . MODEL-ENTRY)."
  (let ((seen (make-hash-table :test #'equal))
        (candidates nil))
    (dolist (model (psi-emacs--sort-model-catalog catalog))
      (let ((provider (psi-emacs--model-provider model))
            (model-id (psi-emacs--model-id model)))
        (when (and provider model-id)
          (let* ((base (psi-emacs--model-candidate-label model))
                 (count (1+ (gethash base seen 0)))
                 (label (if (= count 1)
                            base
                          (format "%s (%d)" base count))))
            (puthash base count seen)
            (push (cons label model) candidates)))))
    (nreverse candidates)))

(defun psi-emacs--model-selector-default-label (candidates)
  "Return default completion label from CANDIDATES based on active session model."
  (let ((provider (psi-emacs--normalize-provider-id (psi-emacs--session-model-default-provider)))
        (model-id (psi-emacs--trim-optional-input (psi-emacs--session-model-default-id)))
        (default nil))
    (dolist (candidate candidates)
      (let* ((model (cdr candidate))
             (candidate-provider (psi-emacs--model-provider model))
             (candidate-id (psi-emacs--model-id model)))
        (when (and (null default)
                   provider
                   model-id
                   (equal candidate-provider provider)
                   (equal candidate-id model-id))
          (setq default (car candidate)))))
    default))

(defun psi-emacs--select-model-candidate (candidates)
  "Prompt for model selection from CANDIDATES.

CANDIDATES is a list of (DISPLAY . MODEL-ENTRY).
Returns selected MODEL-ENTRY map or nil when cancelled/no selection."
  (condition-case _
      (let* ((labels (mapcar #'car candidates))
             (default (or (psi-emacs--model-selector-default-label candidates)
                          (car labels)))
             (chosen (completing-read "Model: " labels nil t nil nil default)))
        (when (and (stringp chosen)
                   (not (string-empty-p chosen)))
          (cdr (assoc chosen candidates))))
    (quit nil)))

(defun psi-emacs--model-selector-error-message (frame)
  "Return deterministic model-selector error text derived from FRAME."
  (let* ((data (alist-get :data frame nil nil #'equal))
         (details (or (alist-get :error-message frame nil nil #'equal)
                      (and (listp data)
                           (or (alist-get :error-message data nil nil #'equal)
                               (alist-get :message data nil nil #'equal))))))
    (if (and (stringp details) (not (string-empty-p details)))
        (format "Unable to open model selector: %s" details)
      "Unable to open model selector.")))

(defun psi-emacs--model-selector-empty-message ()
  "Return deterministic empty-model-selector message for current scope."
  (if (eq psi-emacs-model-selector-provider-scope 'authenticated)
      "No models available for providers with configured auth. Use /login or set provider API keys, or customize `psi-emacs-model-selector-provider-scope`."
    "No models available from backend model catalog."))

(defun psi-emacs--request-model-selector-data (callback)
  "Fetch model selector payload via `query_eql` and invoke CALLBACK."
  (psi-emacs--dispatch-request
   "query_eql"
   `((:query . ,(psi-emacs--model-selector-query)))
   callback))

(defun psi-emacs--handle-model-selector-response (frame)
  "Handle model selector `query_eql` FRAME and dispatch selected model."
  (if (and (eq (alist-get :kind frame) :response)
           (eq (alist-get :ok frame) t))
      (let* ((catalog (psi-emacs--model-catalog-from-query-frame frame))
             (authenticated-providers
              (psi-emacs--authenticated-providers-from-query-frame frame))
             (filtered (psi-emacs--filter-model-catalog catalog authenticated-providers))
             (candidates (psi-emacs--model-selector-candidates filtered)))
        (if (null candidates)
            (psi-emacs--append-assistant-message (psi-emacs--model-selector-empty-message))
          (when-let* ((selected (psi-emacs--select-model-candidate candidates))
                      (provider (psi-emacs--model-provider selected))
                      (model-id (psi-emacs--model-id selected)))
            (psi-emacs-set-model provider model-id))))
    (psi-emacs--append-assistant-message
     (psi-emacs--model-selector-error-message frame))))

(defun psi-emacs--open-model-selector ()
  "Open standard Emacs completion UI for selecting a runtime model."
  (let ((buffer (current-buffer))
        (state psi-emacs--state))
    (psi-emacs--request-model-selector-data
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (eq state psi-emacs--state)
             (psi-emacs--handle-model-selector-response frame))))))))

(defun psi-emacs--request-extension-command-names (callback)
  "Fetch extension command names via `query_eql` and invoke CALLBACK."
  (psi-emacs--dispatch-request
   "query_eql"
   '((:query . "[:psi.extension/command-names]"))
   callback))

(defun psi-emacs--extension-command-names-from-query-frame (frame)
  "Extract extension command names vector/list from `query_eql` FRAME."
  (let* ((result (psi-emacs--query-result-from-frame frame))
         (names (and (listp result)
                     (alist-get :psi.extension/command-names result nil nil #'equal))))
    (cond
     ((vectorp names) (append names nil))
     ((listp names) names)
     (t nil))))

(defun psi-emacs--refresh-extension-command-names ()
  "Refresh cached extension command names for slash completion."
  (let ((buffer (current-buffer))
        (state psi-emacs--state))
    (psi-emacs--request-extension-command-names
     (lambda (frame)
       (when (buffer-live-p buffer)
         (with-current-buffer buffer
           (when (eq state psi-emacs--state)
             (let ((names (psi-emacs--extension-command-names-from-query-frame frame)))
               (when names
                 (setf (psi-emacs-state-extension-command-names psi-emacs--state)
                       (mapcar (lambda (name)
                                 (string-trim (format "%s" (or name ""))))
                               names)))))))))))

(defun psi-emacs--slash-help-text ()
  "Return deterministic help text for supported slash commands."
  (string-join
   (list "Supported slash commands:"
         "/quit, /exit  Exit this psi buffer"
         "/resume [path] Resume a prior session (selector when path omitted)"
         "/tree         Switch between live sessions (completing-read picker)"
         "/new          Start a fresh backend session"
         "/status       Show frontend diagnostics"
         "/worktree     Show git worktree context"
         "/jobs [status ...]   List background jobs"
         "/job <job-id>        Inspect a background job"
         "/cancel-job <job-id> Request cancellation for a background job"
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
        ;; footer/updated + session/updated events arrive before the response frame
        ;; and correctly set projection-footer.  Capture it before reset clears it.
        (let ((saved-footer (and psi-emacs--state
                                 (psi-emacs-state-projection-footer psi-emacs--state))))
          (psi-emacs--reset-transcript-state t)
          (when (and saved-footer psi-emacs--state)
            (setf (psi-emacs-state-projection-footer psi-emacs--state) saved-footer)
            (when (fboundp 'psi-emacs--upsert-projection-block)
              (psi-emacs--upsert-projection-block))))
        ;; Only focus input — footer already correctly set from footer/updated event.
        (when (fboundp 'psi-emacs--focus-input-area)
          (psi-emacs--focus-input-area (current-buffer)))
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

(defun psi-emacs-set-model (&optional provider model-id)
  "Select PROVIDER/MODEL-ID via `set_model` RPC op.

When PROVIDER/MODEL-ID are omitted, open a completion picker backed by
runtime model catalog query data."
  (interactive)
  (let ((provider* (psi-emacs--normalize-provider-id provider))
        (model-id* (psi-emacs--trim-optional-input model-id)))
    (if (and provider* model-id*)
        (when (psi-emacs--dispatch-request
               "set_model"
               `((:provider . ,provider*)
                 (:model-id . ,model-id*)))
          (message "psi: requested model (%s) %s" provider* model-id*))
      (psi-emacs--open-model-selector))))

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
                        :psi.session-info/worktree-path
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

(defun psi-emacs--resume-session-worktree-path (session)
  "Return trimmed worktree path for SESSION, or empty string."
  (string-trim (or (alist-get :psi.session-info/worktree-path session nil nil #'equal)
                   (alist-get :psi.session-info/cwd session nil nil #'equal)
                   "")))

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
                 (worktree (psi-emacs--resume-session-worktree-path session))
                 (base (if (string-empty-p worktree)
                           (format "%s — %s" description path)
                         (format "%s — %s — %s" description worktree path)))
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

(defun psi-emacs--role-is-user-p (role-raw)
  "Return non-nil when ROLE-RAW represents the user role.
Handles string \"user\", bare symbol \\='user, and keyword :user,
since the backend serialises role as the string \"user\" which
`intern' converts to the bare symbol \\='user, not the keyword :user."
  (or (equal role-raw "user")
      (eq role-raw 'user)
      (eq role-raw :user)))

(defun psi-emacs--message->transcript-line (message)
  "Render MESSAGE as one deterministic transcript line."
  (let* ((role-raw (or (alist-get :role message nil nil #'equal)
                       (alist-get 'role message nil nil #'equal)
                       :assistant))
         (content (or (alist-get :content message nil nil #'equal)
                      (alist-get 'content message nil nil #'equal)))
         (text (or (alist-get :text message nil nil #'equal)
                   (alist-get 'text message nil nil #'equal)
                   (alist-get :message message nil nil #'equal)
                   (alist-get 'message message nil nil #'equal)
                   (psi-emacs--message-text-from-content content)
                   "")))
    (format "%s: %s\n"
            (if (psi-emacs--role-is-user-p role-raw) "User" "ψ")
            text)))

(defun psi-emacs--replay-session-messages (messages)
  "Replay MESSAGES into transcript in deterministic input order."
  (let ((follow-anchor (psi-emacs--draft-anchor-at-end-p)))
    (save-excursion
      (dolist (message messages)
        (when (listp message)
          (let* ((role-raw (or (alist-get :role message nil nil #'equal)
                               (alist-get 'role message nil nil #'equal)
                               :assistant)))
            (let ((inhibit-read-only t))
              (psi-emacs--ensure-newline-before-append)
              (let ((line-start (point)))
                (insert (psi-emacs--message->transcript-line message))
                (psi-emacs--mark-region-read-only line-start (point))
                (save-excursion
                  (goto-char line-start)
                  (if (psi-emacs--role-is-user-p role-raw)
                      (psi-emacs--apply-prefix-overlay line-start "User: " 'psi-emacs-user-prompt-face)
                    (psi-emacs--apply-prefix-overlay line-start "ψ: " 'psi-emacs-assistant-reply-face)))))))))
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
          ;; footer/updated + session/updated events arrive before the response frame
          ;; and correctly set projection-footer.  Capture it before reset-transcript-state
          ;; clears it, then restore after so the footer survives the buffer wipe.
          (let ((saved-footer (and psi-emacs--state
                                   (psi-emacs-state-projection-footer psi-emacs--state))))
            (psi-emacs--reset-transcript-state)
            (when (and saved-footer psi-emacs--state)
              (setf (psi-emacs-state-projection-footer psi-emacs--state) saved-footer)
              (when (fboundp 'psi-emacs--upsert-projection-block)
                (psi-emacs--upsert-projection-block))))
          ;; Only focus input — footer already correctly set from footer/updated event.
          (when (fboundp 'psi-emacs--focus-input-area)
            (psi-emacs--focus-input-area (current-buffer)))
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

;;; ── /tree session picker ─────────────────────────────────────────────────

(defun psi-emacs--tree-session-candidates (slots active-id)
  "Build completing-read candidates from context session SLOTS with ACTIVE-ID.

Returns an alist of (label . session-id)."
  (mapcar
   (lambda (slot)
     (let* ((id           (psi-emacs--event-data-get slot '(:id id)))
            (is-active*   (psi-emacs--event-data-get slot '(:is-active is-active :isActive isActive)))
            (is-active    (or is-active*
                              (and id active-id (equal id active-id))))
            (is-streaming (psi-emacs--event-data-get slot '(:is-streaming is-streaming :isStreaming isStreaming)))
            (parent-id    (psi-emacs--event-data-get slot '(:parent-session-id parent-session-id :parentSessionId parentSessionId)))
            (name         (if (and (listp slot)
                                   (fboundp 'psi-emacs--session-display-name))
                              (psi-emacs--session-display-name slot)
                            (or id "(unknown)")))
            (indent       (if parent-id "  " ""))
            (suffix       (concat
                           (when is-streaming " [streaming]")
                           (when is-active " ← active")))
            (label        (concat indent name suffix)))
       (cons label id)))
   slots))

(defun psi-emacs--request-switch-session-by-id (state session-id)
  "Dispatch `switch_session` for SESSION-ID (in-process context session) from STATE."
  (when (and state
             (stringp session-id)
             (not (string-empty-p session-id)))
    (let ((buffer (current-buffer)))
      (psi-emacs--dispatch-request
       "switch_session"
       `((:session-id . ,session-id))
       (lambda (frame)
         (when (buffer-live-p buffer)
           (with-current-buffer buffer
             (when (eq state psi-emacs--state)
               (psi-emacs--handle-switch-session-response state session-id frame)))))))))

(defun psi-emacs--tree-select-and-switch (state active-id slots)
  "Prompt from SLOTS (ACTIVE-ID default) and switch selected session."
  (let ((candidates (psi-emacs--tree-session-candidates slots active-id)))
    (if (null candidates)
        (psi-emacs--append-assistant-message "No live sessions available.")
      (let* ((selected-label
              (completing-read "Switch session: " candidates nil t nil nil
                               ;; default: active session label
                               (car (rassoc active-id candidates))))
             (selected-id (cdr (assoc selected-label candidates))))
        (cond
         ((null selected-id)
          nil)
         ((equal selected-id active-id)
          (psi-emacs--append-assistant-message
           (format "Already on session: %s" selected-label)))
         (t
          (psi-emacs--request-switch-session-by-id state selected-id)))))))

(defun psi-emacs--handle-idle-tree-command (state)
  "Handle `/tree` command.

When a live context snapshot is available locally, open the completing-read
picker immediately. When no snapshot is available yet, fall back to backend
`command` dispatch so the canonical frontend-action flow can provide the
selector."
  (let* ((snapshot  (and state (psi-emacs-state-context-snapshot state)))
         (active-id (and snapshot
                         (psi-emacs--event-data-get snapshot
                                                    '(:active-session-id active-session-id))))
         (slots     (and snapshot
                         (append (psi-emacs--event-data-get snapshot '(:sessions sessions)) nil))))
    (if slots
        (psi-emacs--tree-select-and-switch state active-id slots)
      (let ((sent? (psi-emacs--dispatch-request
                    "command"
                    '((:text . "/tree")))))
        (when sent?
          (psi-emacs--set-run-state state 'streaming)
          (psi-emacs--reset-stream-watchdog state))))))

(defun psi-emacs--default-handle-slash-command (state message)
  "Default slash handler.

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
      ("/tree"
       (let* ((trimmed* (string-trim (or message "")))
              (tail (string-trim (string-remove-prefix "/tree" trimmed*)))
              (session-id (when (and (stringp tail) (not (string-empty-p tail))) tail)))
         (if session-id
             (psi-emacs--request-switch-session-by-id state session-id)
           (psi-emacs--handle-idle-tree-command state)))
       t)
      ("/new"
       (psi-emacs--request-new-session state)
       t)
      ("/status"
       (psi-emacs--append-assistant-message
        (psi-emacs--status-diagnostics-string state))
       t)
      ("/worktree"
       (let ((sent? (psi-emacs--dispatch-request
                     "prompt"
                     `((:message . "/worktree")))))
         (when sent?
           (psi-emacs--set-run-state state 'streaming)
           (psi-emacs--reset-stream-watchdog state)))
       t)
      ((or "/jobs" "/job" "/cancel-job")
       (let ((sent? (psi-emacs--dispatch-request
                     "prompt"
                     `((:message . ,trimmed)))))
         (when sent?
           (psi-emacs--set-run-state state 'streaming)
           (psi-emacs--reset-stream-watchdog state)))
       t)
      ("/model"
       (psi-emacs--handle-idle-model-command state message)
       t)
      ("/thinking"
       (psi-emacs--handle-idle-thinking-command state message)
       t)
      ((or "/help" "/?")
       (psi-emacs--append-assistant-message
        (psi-emacs--slash-help-text))
       t)
      (_ nil))))

(defun psi-emacs--slash-command-candidate-p (message)
  "Return non-nil when MESSAGE is a slash command candidate."
  (let ((trimmed (string-trim (or message ""))))
    (and (not (string-empty-p trimmed))
         (string-prefix-p "/" trimmed))))

(defun psi-emacs--dispatch-compose-message (message &optional behavior)
  "Dispatch compose MESSAGE using slash-first routing.

Slash-prefixed input is always sent to backend `command` handling,
independent of frontend run-state. Non-slash input is sent via normal
`prompt` when idle, or `prompt_while_streaming` with BEHAVIOR when the
frontend is streaming.

Returns plist:
  :dispatched?  non-nil when dispatched remotely
  :local-only?  always nil in the backend-owned slash architecture."
  (let* ((slash-candidate? (and psi-emacs--state
                                (psi-emacs--slash-command-candidate-p message)))
         (streaming? (and psi-emacs--state
                          (memq (psi-emacs-state-run-state psi-emacs--state)
                                '(streaming interrupt_pending))))
         (sent? (cond
                 (slash-candidate?
                  (psi-emacs--dispatch-request "command" `((:text . ,message))))
                 (streaming?
                  (psi-emacs--dispatch-request
                   "prompt_while_streaming"
                   `((:message . ,message)
                     (:behavior . ,(or behavior "steer")))))
                 (t
                  (psi-emacs--dispatch-request "prompt" `((:message . ,message)))))))
    (when sent?
      (psi-emacs--set-run-state psi-emacs--state 'streaming)
      (psi-emacs--reset-stream-watchdog psi-emacs--state))
    (list :dispatched? sent? :local-only? nil)))

(provide 'psi-session-commands)

;;; psi-session-commands.el ends here
