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
      ((or "tool/start" "tool/delta" "tool/executing" "tool/update" "tool/result")
       (let* ((tool-id (psi-emacs--event-data-get data
                                                  '(:tool-id tool-id :toolCallId toolCallId :tool-call-id tool-call-id :id id)))
              (tool-name (psi-emacs--event-data-get data
                                                    '(:tool-name tool-name :toolName toolName :name name)))
              (arguments (psi-emacs--event-data-get data '(:arguments arguments)))
              (parsed-args (psi-emacs--event-data-get data '(:parsed-args parsed-args :parsedArgs parsedArgs)))
              (is-error (psi-emacs--event-data-get data '(:is-error is-error :isError isError)))
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
         (psi-emacs--upsert-tool-row tool-id stage body-text tool-name arguments parsed-args is-error)))
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
