;;; psi-entry.el --- Buffer entry points for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted top-level buffer start/open/state API used by psi.el.

;;; Code:

(require 'psi-globals)

(defvar psi-emacs-buffer-name)

(defun psi-emacs-open-buffer (&optional buffer-name)
  "Open and initialize dedicated psi chat buffer BUFFER-NAME.

Creates/uses one dedicated buffer with one owned subprocess and initializes
MVP frontend state boundaries."
  (let ((buffer (get-buffer-create (or buffer-name psi-emacs-buffer-name))))
    (with-current-buffer buffer
      (let ((mode (psi-emacs--preferred-major-mode)))
        (funcall mode))
      (unless (derived-mode-p 'psi-emacs-mode)
        (psi-emacs-mode))
      (psi-emacs--install-buffer-lifecycle-hooks)
      (unless psi-emacs--state
        (setq psi-emacs--state (psi-emacs--initialize-state nil))
        (setf (psi-emacs-state-draft-anchor psi-emacs--state)
              (copy-marker (point-max) nil))
        (puthash buffer psi-emacs--state psi-emacs--state-by-buffer)
        (psi-emacs--refresh-header-line)
        (psi-emacs--start-rpc-client buffer)))
    buffer))

;;;###autoload
(defun psi-emacs-start ()
  "Start psi frontend in its dedicated process buffer."
  (interactive)
  (pop-to-buffer (psi-emacs-open-buffer)))

(defun psi-emacs-state-for-buffer (buffer)
  "Return frontend state tracked for BUFFER, or nil."
  (gethash buffer psi-emacs--state-by-buffer))

(provide 'psi-entry)

;;; psi-entry.el ends here
