;;; psi.el --- Emacs process-buffer frontend  -*- lexical-binding: t; -*-

;; Copyright (C)

;;; Commentary:
;; Dedicated psi chat buffer with one owned subprocess.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'ansi-color)
(require 'json)
(require 'psi-rpc)

(require 'psi-globals)

(declare-function psi-emacs--default-spawn-process "psi-lifecycle" (command))
(declare-function psi-emacs--default-send-request "psi-lifecycle" (state op params &optional callback))
(declare-function psi-emacs--default-handle-slash-command "psi-session-commands" (state message))

(defvar psi-emacs--spawn-process-function #'psi-emacs--default-spawn-process
  "Function used to spawn a psi subprocess.

The function receives one argument COMMAND (list of strings) and must return
an Emacs process object.")

(defvar psi-emacs--send-request-function #'psi-emacs--default-send-request
  "Function used by frontend commands to send RPC requests.

Called as (STATE OP PARAMS CALLBACK).")

(defvar psi-emacs--slash-command-handler-function #'psi-emacs--default-handle-slash-command
  "Function used to handle slash command input.

Called as (STATE MESSAGE). Return non-nil when MESSAGE was handled and should
not fall through to normal prompt dispatch.")

;;;###autoload (autoload 'psi-emacs-start "psi" "Start a dedicated psi buffer/session." t)
;;;###autoload (autoload 'psi-emacs-project "psi" "Start or switch to a project-scoped psi buffer/session." t)

(defun psi-emacs--frontend-module-directory ()
  "Return absolute directory containing frontend module files."
  (let ((file (or load-file-name (buffer-file-name))))
    (when file
      (file-name-directory file))))

(defun psi-emacs--purge-local-bytecode-artifacts ()
  "Delete local frontend `.elc` artifacts that can cause stale-load failures.

This guard is intentionally scoped to the development checkout layout
(`.../components/emacs-ui/`) where mixed source/bytecode edits are common."
  (when-let* ((dir (psi-emacs--frontend-module-directory))
              (_ (string-match-p "/components/emacs-ui/$" dir)))
    (let ((deleted 0))
      (dolist (elc (directory-files dir t "^psi.*\\.elc\\'" t))
        (when (ignore-errors (delete-file elc) t)
          (setq deleted (1+ deleted))))
      (when (> deleted 0)
        (message "psi-emacs: removed %d local .elc artifact(s)" deleted)))))

(defun psi-emacs--require-runtime-modules ()
  "Require split frontend modules with stale-bytecode guard applied."
  (psi-emacs--purge-local-bytecode-artifacts)
  (require 'psi-run-state)
  (require 'psi-regions)
  (require 'psi-assistant-render)
  (require 'psi-tool-rows)
  (require 'psi-session-commands)
  (require 'psi-projection)
  (require 'psi-dialogs)
  (require 'psi-events)
  (require 'psi-lifecycle)
  (require 'psi-compose)
  (require 'psi-mode)
  (require 'psi-entry))

(psi-emacs--require-runtime-modules)

(provide 'psi)

;;; psi.el ends here
