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
      (setf (psi-emacs-state-session-model-provider psi-emacs--state) model-provider)
      (setf (psi-emacs-state-session-model-id psi-emacs--state) model-id)
      (setf (psi-emacs-state-session-model-reasoning psi-emacs--state) model-reasoning)
      (setf (psi-emacs-state-session-thinking-level psi-emacs--state) thinking-level)
      (setf (psi-emacs-state-session-effective-reasoning-effort psi-emacs--state)
            effective-reasoning-effort)
      (setf (psi-emacs-state-header-model-label psi-emacs--state) header-model-label)
      (unless (memq (psi-emacs-state-run-state psi-emacs--state) '(error reconnecting))
        (psi-emacs--set-run-state psi-emacs--state (if is-streaming 'streaming 'idle)))
      (psi-emacs--refresh-header-line))))

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
      ((or "tool/start" "tool/delta" "tool/executing" "tool/update" "tool/result")
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
              ;; tool/delta carries argument-stream snapshots in :arguments.
              ;; Keep body output focused on execution/update/result text.
              (body-text (if (and (equal stage "delta")
                                  (stringp arguments))
                             ""
                           raw-text)))
         (psi-emacs--reset-stream-watchdog psi-emacs--state)
         (psi-emacs--upsert-tool-row tool-id stage body-text tool-name arguments parsed-args is-error details)))
      ("ui/dialog-requested"
       (psi-emacs--handle-dialog-requested data))
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
