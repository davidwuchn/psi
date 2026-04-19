;;; psi-anthropic-rehydrate-regression-test.el --- Emacs regression for canonical rehydrate payloads -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)
(require 'subr-x)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi)

(ert-deftest psi-session-rehydrated-event-replays-canonical-agent-messages ()
  "Canonical session/rehydrated payloads from rpc-edn must render correctly in Emacs.

This guards the /new and /resume flow where the backend sends agent-history
messages shaped like role/content blocks rather than the old TUI projection
shape with role/text display rows."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--handle-rpc-event
     '((:event . "session/resumed")
       (:data . ((:session-id . "s-new")
                 (:session-file . "/tmp/s-new.ndedn")
                 (:message-count . 2)))))
    (psi-emacs--handle-rpc-event
     '((:event . "session/rehydrated")
       (:data . ((:session-id . "s-new")
                 (:messages . [((:role . "assistant")
                                (:content . [((:type . "text") (:text . "[New session started]"))]))
                               ((:role . "user")
                                (:content . [((:type . "text") (:text . "who are you?"))]))])
                 (:tool-calls . ())
                 (:tool-order . ())))))
    (let ((text (buffer-string)))
      (should (string-match-p (regexp-quote "ψ: [New session started]") text))
      (should (string-match-p (regexp-quote "User: who are you?") text))
      (should-not (string-match-p (regexp-quote "ψ: who are you?") text))
      (should (psi-emacs--input-separator-marker-valid-p)))))

(ert-deftest psi-session-rehydrated-picker-switch-renders-agent-child-messages ()
  "Picker-switch rehydrate events should render child-session transcript messages."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--handle-rpc-event
     '((:event . "session/resumed")
       (:data . ((:session-id . "child-1")
                 (:session-file . "/tmp/child-1.ndedn")
                 (:message-count . 2)))))
    (psi-emacs--handle-rpc-event
     '((:event . "session/rehydrated")
       (:data . ((:session-id . "child-1")
                 (:messages . [((:role . "user")
                                (:content . [((:type . "text") (:text . "hello child"))]))
                               ((:role . "assistant")
                                (:content . [((:type . "text") (:text . "child reply"))]))])
                 (:tool-calls . ())
                 (:tool-order . ())))))
    (let ((text (buffer-string)))
      (should (string-match-p (regexp-quote "User: hello child") text))
      (should (string-match-p (regexp-quote "ψ: child reply") text))
      (should-not (string-match-p (regexp-quote "ψ: hello child") text)))))

(provide 'psi-anthropic-rehydrate-regression-test)

;;; psi-anthropic-rehydrate-regression-test.el ends here
