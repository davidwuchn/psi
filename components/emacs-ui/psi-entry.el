;;; psi-entry.el --- Buffer entry points for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted top-level buffer start/open/state API used by psi.el.

;;; Code:

(require 'psi-globals)

(defvar psi-emacs-buffer-name)
(defvar psi-emacs-working-directory)

(defun psi-emacs--normalize-directory (dir)
  "Return DIR as absolute directory path, or nil when unavailable."
  (when (and (stringp dir)
             (> (length dir) 0)
             (file-directory-p dir))
    (file-name-as-directory (expand-file-name dir))))

(defun psi-emacs--resolve-working-directory (&optional start-directory)
  "Return working directory for psi subprocess startup.

Prefer explicit `psi-emacs-working-directory`; otherwise use
START-DIRECTORY (the directory where `psi-emacs-start' was invoked)."
  (or (psi-emacs--normalize-directory psi-emacs-working-directory)
      (psi-emacs--normalize-directory start-directory)
      (psi-emacs--normalize-directory default-directory)))

(defun psi-emacs-open-buffer (&optional buffer-name start-directory)
  "Open and initialize dedicated psi chat buffer BUFFER-NAME.

START-DIRECTORY sets the startup cwd for the owned subprocess when
`psi-emacs-working-directory' is nil.

Creates/uses one dedicated buffer with one owned subprocess and initializes
frontend state boundaries."
  (let* ((start-directory* (or start-directory default-directory))
         (buffer (get-buffer-create (or buffer-name psi-emacs-buffer-name))))
    (with-current-buffer buffer
      (let ((mode (psi-emacs--preferred-major-mode)))
        (funcall mode))
      (unless (derived-mode-p 'psi-emacs-mode)
        (psi-emacs-mode))
      (psi-emacs--install-buffer-lifecycle-hooks)
      (setq-local default-directory
                  (psi-emacs--resolve-working-directory start-directory*))
      (if psi-emacs--state
          (let* ((state psi-emacs--state)
                 (process (psi-emacs-state-process state))
                 (process-live? (process-live-p process))
                 (client (psi-emacs-state-rpc-client state)))
            (unless (markerp (psi-emacs-state-draft-anchor state))
              (setf (psi-emacs-state-draft-anchor state)
                    (copy-marker (point-max) nil)))
            (psi-emacs--ensure-input-area)
            (goto-char (psi-emacs--draft-end-position))
            ;; Recover from stale buffer state where a process exists but no
            ;; rpc client is attached (e.g. after partial code reload).
            (unless (and client process-live?)
              (when process-live?
                (delete-process process))
              (psi-emacs--start-rpc-client buffer)))
        (setq psi-emacs--state (psi-emacs--initialize-state nil))
        (setf (psi-emacs-state-draft-anchor psi-emacs--state)
              (copy-marker (point-max) nil))
        (psi-emacs--ensure-input-area)
        (goto-char (psi-emacs--draft-end-position))
        (puthash buffer psi-emacs--state psi-emacs--state-by-buffer)
        (psi-emacs--refresh-header-line)
        (psi-emacs--start-rpc-client buffer)))
    buffer))

;;;###autoload
(defun psi-emacs-start (&optional prefix)
  "Start psi frontend in its dedicated process buffer.

With PREFIX, create and switch to a fresh dedicated buffer name."
  (interactive "P")
  (let ((buffer-name (when prefix
                       (generate-new-buffer-name psi-emacs-buffer-name))))
    (pop-to-buffer (psi-emacs-open-buffer buffer-name default-directory))))

(defun psi-emacs-state-for-buffer (buffer)
  "Return frontend state tracked for BUFFER, or nil."
  (gethash buffer psi-emacs--state-by-buffer))

(provide 'psi-entry)

;;; psi-entry.el ends here
