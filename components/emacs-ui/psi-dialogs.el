;;; psi-dialogs.el --- Extension UI dialog handlers for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted dialog request/response helpers used by psi.el.

;;; Code:

(require 'subr-x)
(require 'psi-globals)

(defun psi-emacs--dialog-result-normalize (value)
  "Normalize dialog VALUE to the shared RPC result domain."
  (cond
   ((or (null value) (booleanp value) (stringp value)) value)
   (t (format "%s" value))))

(defun psi-emacs--dialog-send-resolve (dialog-id result)
  "Send `resolve_dialog` for DIALOG-ID with RESULT."
  (psi-emacs--dispatch-request
   "resolve_dialog"
   `((:dialog-id . ,dialog-id)
     (:result . ,(psi-emacs--dialog-result-normalize result)))))

(defun psi-emacs--dialog-send-cancel (dialog-id)
  "Send `cancel_dialog` for DIALOG-ID."
  (psi-emacs--dispatch-request
   "cancel_dialog"
   `((:dialog-id . ,dialog-id))))

(defun psi-emacs--dialog-prompt-text (data fallback)
  "Return deterministic prompt text from DATA, else FALLBACK."
  (let ((value (psi-emacs--event-data-get data
                                          '(:prompt prompt :message message :text text :title title))))
    (if (and (stringp value)
             (not (string-empty-p value)))
        value
      fallback)))

(defun psi-emacs--dialog-option-candidates (options)
  "Convert OPTIONS into selector candidates of (LABEL . VALUE)."
  (let ((candidates nil))
    (dolist (item (psi-emacs--projection-seq options))
      (when (listp item)
        (let ((label (psi-emacs--event-data-get item '(:label label :name name :text text)))
              (value (psi-emacs--event-data-get item '(:value value :id id))))
          (when (and (stringp label)
                     (not (string-empty-p label)))
            (push (cons label (psi-emacs--dialog-result-normalize value))
                  candidates)))))
    (nreverse candidates)))

(defun psi-emacs--handle-dialog-requested (data)
  "Handle `ui/dialog-requested` DATA with one deterministic response op."
  (let* ((dialog-id (psi-emacs--event-data-get data '(:dialog-id dialog-id :dialogId dialogId)))
         (kind* (psi-emacs--event-data-get data '(:kind kind)))
         (kind (if (symbolp kind*) (symbol-name kind*) kind*)))
    (if (not (and (stringp dialog-id) (not (string-empty-p dialog-id))))
        (psi-emacs--append-assistant-message
         "Ignored malformed ui/dialog-requested event: missing dialog-id.")
      (condition-case _
          (pcase kind
            ("confirm"
             (let ((answer (y-or-n-p
                            (format "%s "
                                    (psi-emacs--dialog-prompt-text data "Confirm?")))))
               (psi-emacs--dialog-send-resolve dialog-id answer)))
            ("select"
             (let* ((candidates (psi-emacs--dialog-option-candidates
                                 (or (psi-emacs--event-data-get data '(:options options))
                                     (psi-emacs--event-data-get data '(:items items)))))
                    (labels (mapcar #'car candidates)))
               (if (null candidates)
                   (psi-emacs--dialog-send-cancel dialog-id)
                 (let* ((choice (completing-read
                                 (format "%s "
                                         (psi-emacs--dialog-prompt-text data "Select an option:"))
                                 labels nil t))
                        (selected (assoc choice candidates)))
                   (if selected
                       (psi-emacs--dialog-send-resolve dialog-id (cdr selected))
                     (psi-emacs--dialog-send-cancel dialog-id))))))
            ("input"
             (let ((answer (read-string
                            (format "%s "
                                    (psi-emacs--dialog-prompt-text data "Input:")))))
               (psi-emacs--dialog-send-resolve dialog-id answer)))
            (_
             (psi-emacs--dialog-send-cancel dialog-id)))
        (quit
         (psi-emacs--dialog-send-cancel dialog-id))))))

(provide 'psi-dialogs)

;;; psi-dialogs.el ends here
