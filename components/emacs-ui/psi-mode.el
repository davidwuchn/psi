;;; psi-mode.el --- Major mode + keymap for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted mode bootstrap helpers and keybindings used by psi.el.

;;; Code:

(require 'psi-globals)
(require 'psi-completion)

(declare-function markdown-mode "markdown-mode")

(defface psi-emacs-user-prompt-face
  '((t :inherit font-lock-keyword-face :weight bold))
  "Face for `User:' transcript prompt prefixes."
  :group 'psi-emacs)

(defface psi-emacs-assistant-reply-face
  '((t :inherit font-lock-function-name-face :weight ultra-bold))
  "Face for `ψ:' transcript reply prefixes."
  :group 'psi-emacs)

(defface psi-emacs-assistant-thinking-face
  '((t :inherit shadow :slant italic))
  "Face for incremental assistant thinking transcript lines."
  :group 'psi-emacs)

(defface psi-emacs-tool-call-face
  '((t :inherit font-lock-builtin-face))
  "Face for tool call summary text."
  :group 'psi-emacs)

(defface psi-emacs-tool-pending-face
  '((t :inherit warning))
  "Face for pending tool status labels."
  :group 'psi-emacs)

(defface psi-emacs-tool-running-face
  '((t :inherit mode-line-emphasis))
  "Face for running tool status labels."
  :group 'psi-emacs)

(defface psi-emacs-tool-success-face
  '((t :inherit success))
  "Face for successful tool status labels."
  :group 'psi-emacs)

(defface psi-emacs-tool-error-face
  '((t :inherit error))
  "Face for errored tool status labels."
  :group 'psi-emacs)

(defface psi-emacs-error-line-face
  '((t :inherit error))
  "Face for persistent transcript error lines."
  :group 'psi-emacs)

(defface psi-emacs-projection-separator-face
  '((t :inherit shadow))
  "Face for projection separator lines."
  :group 'psi-emacs)

(defface psi-emacs-projection-heading-face
  '((t :inherit font-lock-doc-face :weight bold))
  "Face for projection section headings."
  :group 'psi-emacs)

(defface psi-emacs-projection-extension-id-face
  '((t :inherit font-lock-constant-face))
  "Face for projection extension-id labels."
  :group 'psi-emacs)

(defface psi-emacs-projection-widget-face
  '((t :inherit default))
  "Face for projection widget content lines."
  :group 'psi-emacs)

(defface psi-emacs-projection-footer-face
  '((t :inherit shadow))
  "Face for projection footer summary lines."
  :group 'psi-emacs)

(defface psi-emacs-projection-footer-path-face
  '((t :inherit font-lock-string-face))
  "Face for projection footer path lines."
  :group 'psi-emacs)

(defface psi-emacs-projection-footer-stats-face
  '((t :inherit shadow))
  "Face for projection footer stats lines."
  :group 'psi-emacs)

(defface psi-emacs-projection-footer-status-face
  '((t :inherit mode-line-emphasis))
  "Face for projection footer status lines."
  :group 'psi-emacs)

(defun psi-emacs--markdown-mode-available-p ()
  "Return non-nil when `markdown-mode' is available."
  (fboundp 'markdown-mode))

(defun psi-emacs--preferred-major-mode ()
  "Return major mode symbol for dedicated psi buffer.

Uses `text-mode' so markdown fontification can be scoped to assistant replies
instead of the entire buffer."
  'text-mode)

(define-derived-mode psi-emacs-mode text-mode "psi"
  "Major mode for dedicated psi chat buffer.

Uses `text-mode' so markdown fontification can be applied selectively to
finalized assistant content only."
  (setq-local buffer-read-only nil)
  (psi-emacs--install-prompt-capf))

(let ((map psi-emacs-mode-map))
  (define-key map (kbd "RET") #'newline)
  (define-key map (kbd "C-c RET") #'psi-emacs-send-from-buffer)
  (define-key map (kbd "C-c C-q") #'psi-emacs-queue-from-buffer)
  (define-key map (kbd "C-c C-k") #'psi-emacs-abort)
  (define-key map (kbd "C-c C-r") #'psi-emacs-reconnect)
  (define-key map (kbd "C-c C-t") #'psi-emacs-toggle-tool-output-view)

  ;; Model/thinking controls.
  (define-key map (kbd "C-c m m") #'psi-emacs-set-model)
  (define-key map (kbd "C-c m n") #'psi-emacs-cycle-model-next)
  (define-key map (kbd "C-c m p") #'psi-emacs-cycle-model-prev)
  (define-key map (kbd "C-c m t") #'psi-emacs-set-thinking-level)
  (define-key map (kbd "C-c m c") #'psi-emacs-cycle-thinking-level))

(provide 'psi-mode)

;;; psi-mode.el ends here
