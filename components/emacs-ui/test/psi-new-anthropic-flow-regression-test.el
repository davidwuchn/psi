;;; psi-new-anthropic-flow-regression-test.el --- Emacs /new flow regression -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)
(require 'subr-x)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi)

(ert-deftest psi-new-command-and-rehydrate-do-not-duplicate-assistant-turn ()
  "Sending /new should append the local user command once, then rebuild transcript from backend events.

Critically, Emacs must not add an extra assistant transcript line beyond the
backend-owned `command-result` + `session/resumed` + `session/rehydrated`
flow, otherwise the next Anthropic turn could see an extra assistant message in
frontend-derived state or confuse the user-facing transcript."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-min) nil))
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position))
    (insert "/new")
    (let ((rpc-calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) rpc-calls)
                   t)))
        (psi-emacs-send-from-buffer nil))
      (should (equal '(("command" ((:text . "/new"))))
                     (nreverse rpc-calls))))
    ;; Frontend local echo: only the user command is added immediately.
    (let ((text (buffer-string)))
      (should (string-match-p (regexp-quote "User: /new") text))
      (should-not (string-match-p (regexp-quote "ψ: [New session started]") text)))
    ;; Backend command result emits a visible assistant line, but the subsequent
    ;; resumed+rehydrated lifecycle clears and rebuilds transcript canonically.
    (psi-emacs--handle-rpc-event
     '((:event . "command-result")
       (:data . ((:type . "new_session")
                 (:message . "[New session started]")))))
    (should (string-match-p (regexp-quote "ψ: [New session started]") (buffer-string)))
    (psi-emacs--handle-rpc-event
     '((:event . "session/resumed")
       (:data . ((:session-id . "s-new")
                 (:session-file . "/tmp/s-new.ndedn")
                 (:message-count . 1)))))
    (psi-emacs--handle-rpc-event
     '((:event . "session/rehydrated")
       (:data . ((:messages . [((:role . "assistant")
                                (:content . [((:type . "text") (:text . "[New session started]"))]))])
                 (:tool-calls . ())
                 (:tool-order . ())))))
    (let ((text (buffer-string)))
      (should (= 1 (how-many (concat "^" (regexp-quote "ψ: [New session started]") "$")
                             (point-min) (point-max))))
      (should-not (string-match-p (concat "^" (regexp-quote "User: /new") "$") text))
      (should (psi-emacs--input-separator-marker-valid-p)))))

(provide 'psi-new-anthropic-flow-regression-test)

;;; psi-new-anthropic-flow-regression-test.el ends here
