;;; psi-events.el --- RPC event decoding/dispatch for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted event payload helpers + inbound event routing used by psi.el.

;;; Code:

(require 'subr-x)
(require 'psi-globals)

(defun psi-emacs--event-data-get (data keys)
  "Return first non-nil value from DATA for KEYS.

DATA is expected to be an alist map."
  (catch 'hit
    (dolist (k keys)
      (let ((v (alist-get k data nil nil #'equal)))
        (when v
          (throw 'hit v))))
    nil))

(defun psi-emacs--session-normalize-text (value)
  "Normalize VALUE to a compact text representation, or nil when blank."
  (let ((text (cond
               ((null value) nil)
               ((stringp value) value)
               ((keywordp value) (string-remove-prefix ":" (symbol-name value)))
               ((symbolp value) (symbol-name value))
               (t (format "%s" value)))))
    (when (and (stringp text)
               (not (string-empty-p (string-trim text))))
      (string-trim text))))

(defun psi-emacs--session-model-label (provider model-id model-reasoning thinking-level effective-effort)
  "Return optional compact model label from session metadata.

When MODEL-REASONING is non-nil, append an explicit thinking/effort suffix."
  (let* ((provider* (psi-emacs--session-normalize-text provider))
         (model-id* (psi-emacs--session-normalize-text model-id))
         (thinking* (psi-emacs--session-normalize-text thinking-level))
         (effort* (psi-emacs--session-normalize-text effective-effort))
         (base (when model-id*
                 (if provider*
                     (format "(%s) %s" provider* model-id*)
                   model-id*))))
    (when base
      (if model-reasoning
          (let ((label (or effort* thinking* "off")))
            (format "%s • thinking %s" base label))
        base))))

(defun psi-emacs--handle-session-updated-event (data)
  "Project `session/updated` DATA into frontend session/header state."
  (when psi-emacs--state
    (let* ((session-id (psi-emacs--session-normalize-text
                        (psi-emacs--event-data-get data '(:session-id session-id :sessionId sessionId))))
           (phase (psi-emacs--session-normalize-text
                   (psi-emacs--event-data-get data '(:phase phase))))
           (is-streaming (not (null (psi-emacs--event-data-get data
                                                                '(:is-streaming is-streaming :isStreaming isStreaming)))))
           (is-compacting (not (null (psi-emacs--event-data-get data
                                                                 '(:is-compacting is-compacting :isCompacting isCompacting)))))
           (pending (or (psi-emacs--event-data-get data
                                                   '(:pending-message-count pending-message-count :pendingMessageCount pendingMessageCount))
                        0))
           (retry (or (psi-emacs--event-data-get data
                                                 '(:retry-attempt retry-attempt :retryAttempt retryAttempt))
                      0))
           (interrupt-pending (not (null (psi-emacs--event-data-get data
                                                                     '(:interrupt-pending interrupt-pending :interruptPending interruptPending)))))
           (model-provider (psi-emacs--session-normalize-text
                            (psi-emacs--event-data-get data
                                                       '(:model-provider model-provider :modelProvider modelProvider))))
           (model-id (psi-emacs--session-normalize-text
                      (psi-emacs--event-data-get data
                                                 '(:model-id model-id :modelId modelId))))
           (model-reasoning (not (null (psi-emacs--event-data-get data
                                                                   '(:model-reasoning model-reasoning :modelReasoning modelReasoning)))))
           (thinking-level (psi-emacs--session-normalize-text
                            (psi-emacs--event-data-get data
                                                       '(:thinking-level thinking-level :thinkingLevel thinkingLevel))))
           (effective-reasoning-effort (psi-emacs--session-normalize-text
                                        (psi-emacs--event-data-get data
                                                                   '(:effective-reasoning-effort effective-reasoning-effort :effectiveReasoningEffort effectiveReasoningEffort))))
           (header-model-label (psi-emacs--session-model-label
                                model-provider
                                model-id
                                model-reasoning
                                thinking-level
                                effective-reasoning-effort)))
      (setf (psi-emacs-state-session-id psi-emacs--state) session-id)
      (setf (psi-emacs-state-session-phase psi-emacs--state) phase)
      (setf (psi-emacs-state-session-is-streaming psi-emacs--state) is-streaming)
      (setf (psi-emacs-state-session-is-compacting psi-emacs--state) is-compacting)
      (setf (psi-emacs-state-session-pending-message-count psi-emacs--state) pending)
      (setf (psi-emacs-state-session-retry-attempt psi-emacs--state) retry)
      (setf (psi-emacs-state-session-interrupt-pending psi-emacs--state) interrupt-pending)
      (setf (psi-emacs-state-session-model-provider psi-emacs--state) model-provider)
      (setf (psi-emacs-state-session-model-id psi-emacs--state) model-id)
      (setf (psi-emacs-state-session-model-reasoning psi-emacs--state) model-reasoning)
      (setf (psi-emacs-state-session-thinking-level psi-emacs--state) thinking-level)
      (setf (psi-emacs-state-session-effective-reasoning-effort psi-emacs--state)
            effective-reasoning-effort)
      (setf (psi-emacs-state-header-model-label psi-emacs--state) header-model-label)
      (unless (memq (psi-emacs-state-run-state psi-emacs--state) '(error reconnecting))
        (psi-emacs--set-run-state
         psi-emacs--state
         (cond
          ((and is-streaming interrupt-pending) 'interrupt_pending)
          (is-streaming                         'streaming)
          (t                                    'idle))))
      (psi-emacs--refresh-header-line))))

(defun psi-emacs--session-display-name (slot)
  "Return display name for session SLOT map.

Uses :name if non-empty, else falls back to \"(session <8-char id prefix>)\"."
  (let ((name (psi-emacs--event-data-get slot '(:name name))))
    (if (and (stringp name) (not (string-empty-p (string-trim name))))
        (string-trim name)
      (let ((id (or (psi-emacs--event-data-get slot '(:id id)) "")))
        (format "(session %s)" (substring id 0 (min 8 (length id))))))))

(defun psi-emacs--session-tree-widget-lines (slots active-id)
  "Build structured widget content-lines for SLOTS with ACTIVE-ID marked.

Returns a list of line maps with :text and optionally :action.
Child sessions (non-nil parent-session-id that matches a slot) are indented."
  (let* ((slot-ids (mapcar (lambda (s)
                             (psi-emacs--event-data-get s '(:id id)))
                           slots))
         (slot-id-set (and (listp slot-ids)
                           (make-hash-table :test 'equal))))
    (dolist (id slot-ids)
      (when id (puthash id t slot-id-set)))
    (mapcar
     (lambda (slot)
       (let* ((id          (psi-emacs--event-data-get slot '(:id id)))
              (is-active   (psi-emacs--event-data-get slot '(:is-active is-active :isActive isActive)))
              (is-streaming (psi-emacs--event-data-get slot '(:is-streaming is-streaming :isStreaming isStreaming)))
              (parent-id   (psi-emacs--event-data-get slot '(:parent-session-id parent-session-id :parentSessionId parentSessionId)))
              (indent      (if (and parent-id (gethash parent-id slot-id-set))
                               "  " ""))
              (base-name   (psi-emacs--session-display-name slot))
              (suffix      (concat
                            (when is-streaming " [streaming]")
                            (when is-active " ← active")))
              (text        (concat indent base-name suffix)))
         (if is-active
             `((:text . ,text))
           `((:text . ,text)
             (:action . ((:type . "command")
                         (:command . ,(concat "/tree " id))))))))
     slots)))

(defun psi-emacs--handle-host-updated-event (data)
  "Handle `host/updated` DATA: store snapshot and refresh session tree widget."
  (when psi-emacs--state
    (let* ((active-id (psi-emacs--event-data-get data '(:active-session-id active-session-id :activeSessionId activeSessionId)))
           (slots     (append (psi-emacs--event-data-get data '(:sessions sessions)) nil))
           (snapshot  `((:active-session-id . ,active-id) (:sessions . ,slots)))
           (lines     (psi-emacs--session-tree-widget-lines slots active-id))
           (widget    `((:placement . "left")
                        (:extension-id . "psi-session")
                        (:widget-id . "session-tree")
                        (:content-lines . ,lines)))
           (existing  (psi-emacs-state-projection-widgets psi-emacs--state))
           (others    (seq-remove
                       (lambda (w)
                         (and (equal (psi-emacs--event-data-get w '(:extension-id extension-id :extensionId extensionId))
                                     "psi-session")
                              (equal (psi-emacs--event-data-get w '(:widget-id widget-id :widgetId widgetId))
                                     "session-tree")))
                       (or existing [])))
           (updated   (if (> (length slots) 1)
                          (cons widget others)
                        others)))
      (setf (psi-emacs-state-host-snapshot psi-emacs--state) snapshot)
      (setf (psi-emacs-state-projection-widgets psi-emacs--state)
            (psi-emacs--projection-sort-widgets updated))
      (psi-emacs--upsert-projection-block))))

(defun psi-emacs--handle-command-result-event (data)
  "Handle backend `command-result` event DATA."
  (let ((type (psi-emacs--event-data-get data '(:type type))))
    (pcase type
      ("quit"
       (psi-emacs--request-frontend-exit))
      ("session_switch"
       (let ((sid (psi-emacs--event-data-get data '(:session-id session-id :sessionId sessionId))))
         (when (and (stringp sid) (not (string-empty-p sid)))
           (psi-emacs--request-switch-session-by-id psi-emacs--state sid))))
      (_
       (let ((message (psi-emacs--event-data-get data '(:message message))))
         (when (and (stringp message) (not (string-empty-p message)))
           (psi-emacs--append-assistant-message message))))))
  (when psi-emacs--state
    (psi-emacs--set-run-state psi-emacs--state 'idle)))

(defun psi-emacs--frontend-action-map-candidates (action-name payload)
  "Return completing-read alist candidates for ACTION-NAME from PAYLOAD."
  (pcase action-name
    ("resume-selector"
     (let* ((query (or (alist-get :query payload nil nil #'equal) '()))
            (result (and (listp query) (alist-get :psi.session/list query nil nil #'equal)))
            (sessions (cond ((vectorp result) (append result nil))
                            ((listp result) result)
                            (t nil))))
       (when (fboundp 'psi-emacs--resume-session-candidates)
         (psi-emacs--resume-session-candidates sessions))))
    ("session-tree-selector"
     (let ((active-id (psi-emacs--event-data-get payload '(:active-session-id active-session-id :activeSessionId activeSessionId)))
           (sessions (append (psi-emacs--event-data-get payload '(:sessions sessions)) nil)))
       (when (fboundp 'psi-emacs--tree-session-candidates)
         (psi-emacs--tree-session-candidates sessions active-id))))
    ("model-picker"
     (let ((models (append (psi-emacs--event-data-get payload '(:models models)) nil)))
       (mapcar (lambda (m)
                 (let ((provider (psi-emacs--event-data-get m '(:provider provider)))
                       (model-id (psi-emacs--event-data-get m '(:id id)))
                       (reasoning (psi-emacs--event-data-get m '(:reasoning reasoning))))
                   (cons (format "%s %s%s"
                                 provider model-id
                                 (if reasoning " [reasoning]" ""))
                         `((:provider . ,provider) (:id . ,model-id)))))
               models)))
    ("thinking-picker"
     (let ((levels (append (psi-emacs--event-data-get payload '(:levels levels)) nil)))
       (mapcar (lambda (level)
                 (cons (format "%s" level) level))
               levels)))
    (_ nil)))

(defun psi-emacs--handle-frontend-action-requested (data)
  "Handle backend `ui/frontend-action-requested` event DATA."
  (when psi-emacs--state
    (setf (psi-emacs-state-pending-frontend-action psi-emacs--state) data)
    (let* ((action-name (psi-emacs--event-data-get data '(:action-name action-name :actionName actionName)))
           (request-id (psi-emacs--event-data-get data '(:request-id request-id :requestId requestId)))
           (prompt (or (psi-emacs--event-data-get data '(:prompt prompt)) "Select:"))
           (payload (or (psi-emacs--event-data-get data '(:payload payload)) '()))
           (candidates (psi-emacs--frontend-action-map-candidates action-name payload))
           (selected (condition-case nil
                         (when candidates
                           (let ((label (completing-read (concat prompt " ") candidates nil t)))
                             (cdr (assoc label candidates))))
                       (quit :cancelled))))
      (cond
       ((eq selected :cancelled)
        (psi-emacs--dispatch-request
         "frontend_action_result"
         `((:request-id . ,request-id)
           (:action-name . ,action-name)
           (:status . "cancelled")
           (:value . nil))))
       (selected
        (psi-emacs--dispatch-request
         "frontend_action_result"
         `((:request-id . ,request-id)
           (:action-name . ,action-name)
           (:status . "submitted")
           (:value . ,selected))))))
    (setf (psi-emacs-state-pending-frontend-action psi-emacs--state) nil)))

(defun psi-emacs--handle-rpc-event (frame)
  "Handle inbound rpc-edn event FRAME for transcript rendering."
  (let* ((event (alist-get :event frame nil nil #'equal))
         (data (or (alist-get :data frame nil nil #'equal) '())))
    (pcase event
      ("assistant/delta"
       (psi-emacs--assistant-delta
        (or (psi-emacs--event-data-get data '(:text text :delta delta)) "")))
      ("assistant/message"
       (psi-emacs--assistant-finalize
        (or (psi-emacs--event-data-get data '(:text text :message message))
            (psi-emacs--assistant-content->text
             (psi-emacs--event-data-get data '(:content content))))))
      ("assistant/thinking-delta"
       (psi-emacs--assistant-thinking-delta
        (or (psi-emacs--event-data-get data '(:text text :delta delta)) "")))
      ("session/updated"
       (psi-emacs--handle-session-updated-event data))
      ("host/updated"
       (psi-emacs--handle-host-updated-event data))
      ((or "tool/start" "tool/executing" "tool/update" "tool/result")
       (psi-emacs--assistant-before-tool-event)
       (let* ((tool-id (psi-emacs--event-data-get data
                                                  '(:tool-id tool-id :toolCallId toolCallId :tool-call-id tool-call-id :id id)))
              (tool-name (psi-emacs--event-data-get data
                                                    '(:tool-name tool-name :toolName toolName :name name)))
              (arguments (psi-emacs--event-data-get data '(:arguments arguments)))
              (parsed-args (psi-emacs--event-data-get data '(:parsed-args parsed-args :parsedArgs parsedArgs)))
              (is-error (psi-emacs--event-data-get data '(:is-error is-error :isError isError)))
              (details (psi-emacs--event-data-get data '(:details details)))
              (stage (replace-regexp-in-string "^tool/" "" event))
              (raw-text (or (psi-emacs--event-data-get data
                                                       '(:result-text result-text :text text :output output :delta delta :message message))
                            ""))
              (body-text raw-text))
         (psi-emacs--reset-stream-watchdog psi-emacs--state)
         (psi-emacs--upsert-tool-row tool-id stage body-text tool-name arguments parsed-args is-error details)))
      ("command-result"
       (psi-emacs--handle-command-result-event data))
      ("ui/dialog-requested"
       (psi-emacs--handle-dialog-requested data))
      ("ui/frontend-action-requested"
       (psi-emacs--handle-frontend-action-requested data))
      ("ui/widgets-updated"
       (when psi-emacs--state
         (setf (psi-emacs-state-projection-widgets psi-emacs--state)
               (psi-emacs--projection-sort-widgets
                (psi-emacs--projection-seq
                 (or (psi-emacs--event-data-get data '(:widgets widgets))
                     (psi-emacs--event-data-get data '(:items items))))))
         (psi-emacs--upsert-projection-block)))
      ("ui/status-updated"
       (when psi-emacs--state
         (setf (psi-emacs-state-projection-statuses psi-emacs--state)
               (psi-emacs--projection-sort-statuses
                (psi-emacs--projection-seq
                 (or (psi-emacs--event-data-get data '(:statuses statuses))
                     (psi-emacs--event-data-get data '(:items items))))))
         (psi-emacs--upsert-projection-block)))
      ("ui/notification"
       (psi-emacs--handle-notification-event data))
      ("footer/updated"
       (when psi-emacs--state
         (setf (psi-emacs-state-projection-footer psi-emacs--state)
               (psi-emacs--projection-footer-text data))
         (psi-emacs--upsert-projection-block)))
      (_ nil))))

(provide 'psi-events)

;;; psi-events.el ends here
