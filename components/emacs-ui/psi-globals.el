;;; psi-globals.el --- Shared dynamic declarations for psi frontend modules  -*- lexical-binding: t; -*-

;;; Commentary:
;; Forward declarations used to keep byte-compilation warnings low across
;; split frontend modules.

;;; Code:

(defvar psi-emacs--state nil)
(defvar psi-emacs--owned-process nil)
(defvar psi-emacs--state-by-buffer nil)

(defvar psi-emacs--send-request-function nil)
(defvar psi-emacs--spawn-process-function nil)
(defvar psi-emacs--idle-slash-command-handler-function nil)

(defvar psi-emacs-command)
(defvar psi-emacs-buffer-name)
(defvar psi-emacs-working-directory)
(defvar psi-emacs-stream-timeout-seconds)
(defvar psi-emacs-notification-timeout-seconds)

(declare-function make-psi-emacs-state "psi" (&rest args))
(declare-function psi-emacs--clear-thinking-line "psi-assistant-render")

(declare-function markdown-mode "markdown-mode")
(declare-function markdown-fontify-region "markdown-mode" (begin end))

(provide 'psi-globals)

;;; psi-globals.el ends here
