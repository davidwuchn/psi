;;; psi-mode.el --- Major mode + keymap for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted mode bootstrap helpers and keybindings used by psi.el.

;;; Code:

(defun psi-emacs--markdown-mode-available-p ()
  "Return non-nil when `markdown-mode' is available."
  (fboundp 'markdown-mode))

(defun psi-emacs--preferred-major-mode ()
  "Return major mode symbol for dedicated psi buffer.

Prefers `markdown-mode' when available, otherwise `text-mode'."
  (if (psi-emacs--markdown-mode-available-p)
      'markdown-mode
    'text-mode))

(if (psi-emacs--markdown-mode-available-p)
    (define-derived-mode psi-emacs-mode markdown-mode "psi"
      "Major mode for dedicated psi chat buffer.

Uses markdown as the parent mode when available so finalized assistant
content can be markdown-fontified in-place."
      (setq-local buffer-read-only nil))
  (define-derived-mode psi-emacs-mode text-mode "psi"
    "Major mode for dedicated psi chat buffer.

Fallback mode when markdown-mode is unavailable."
    (setq-local buffer-read-only nil)))

(let ((map psi-emacs-mode-map))
  (define-key map (kbd "RET") #'newline)
  (define-key map (kbd "C-c RET") #'psi-emacs-send-from-buffer)
  (define-key map (kbd "C-c C-q") #'psi-emacs-queue-from-buffer)
  (define-key map (kbd "C-c C-k") #'psi-emacs-abort)
  (define-key map (kbd "C-c C-r") #'psi-emacs-reconnect)
  (define-key map (kbd "C-c C-t") #'psi-emacs-toggle-tool-output-view))

(provide 'psi-mode)

;;; psi-mode.el ends here
