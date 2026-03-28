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
(defvar psi-emacs--slash-command-handler-function nil)

(defvar psi-emacs-command)
(defvar psi-emacs-buffer-name)
(defvar psi-emacs-working-directory)
(defvar psi-emacs-stream-timeout-seconds)
(defvar psi-emacs-notification-timeout-seconds)

(declare-function make-psi-emacs-state "psi" (&rest args))
(declare-function psi-emacs-state-extension-command-names "psi" (state))
(declare-function psi-emacs--clear-thinking-line "psi-assistant-render")
(declare-function psi-emacs--archive-thinking-line "psi-assistant-render")
(declare-function psi-emacs--assistant-before-tool-event "psi-assistant-render")
(declare-function psi-emacs--region-register "psi-regions" (kind id start end &optional props))
(declare-function psi-emacs--region-bounds "psi-regions" (kind id))
(declare-function psi-emacs--region-find-by-property "psi-regions" (kind id &optional start end))
(declare-function psi-emacs--region-unregister "psi-regions" (kind id))
(declare-function psi-emacs--regions-clear "psi-regions")
(declare-function psi-emacs--next-region-id "psi-regions" (kind))
(declare-function psi-emacs--ensure-input-area "psi-compose")
(declare-function psi-emacs--ensure-startup-banner "psi-compose")
(declare-function psi-emacs--mark-region-read-only "psi-compose" (start end))
(declare-function psi-emacs--input-range "psi-compose")
(declare-function psi-emacs--draft-end-position "psi-compose")
(declare-function psi-emacs--input-separator-marker-valid-p "psi-compose")
(declare-function psi-emacs--input-separator-needs-refresh-p "psi-compose")
(declare-function psi-emacs--refresh-input-separator-line "psi-compose")
(declare-function psi-emacs--input-separator-current-width "psi-compose")
(declare-function psi-emacs-previous-input "psi-compose")
(declare-function psi-emacs-next-input "psi-compose")
(declare-function psi-emacs--upsert-projection-block "psi-projection")
(declare-function psi-emacs--projection-widget-content-lines "psi-projection" (widget))
(declare-function psi-emacs--handle-window-configuration-change "psi-lifecycle")
(declare-function psi-emacs--request-frontend-exit "psi-session-commands")
(declare-function psi-emacs--request-switch-session-by-id "psi-session-commands" (state session-id))
(declare-function psi-emacs--resume-session-candidates "psi-session-commands" (sessions))
(declare-function psi-emacs--tree-session-candidates "psi-session-commands" (slots active-id))
(declare-function psi-emacs--replay-session-messages "psi-session-commands" (messages))
(declare-function psi-emacs--session-tree-line-label "psi-events" (slot))
(declare-function psi-emacs--append-assistant-message "psi-compose" (text))
(declare-function psi-emacs--focus-input-area "psi-entry" (&optional buffer window))
(declare-function psi-emacs--reset-transcript-state "psi-lifecycle" (&optional preserve-tool-output-view-mode))

(declare-function markdown-mode "markdown-mode")
(declare-function markdown-fontify-region "markdown-mode" (begin end))

(provide 'psi-globals)

;;; psi-globals.el ends here
