;;; psi-background-job-terminal-test.el --- Background job terminal rendering tests -*- lexical-binding: t; -*-

(require 'ert)
(require 'psi)

(ert-deftest psi-background-job-terminal-assistant-message-renders-text ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/message")
       (:data . ((:text . "── Background job ──────────────────────\n{:job-id \"job-1\" :status :completed}\n───────────────────────────────────────")))))
    (let ((buf (buffer-string)))
      (should (string-match-p "Background job" buf))
      (should (string-match-p "job-1" buf)))))

(provide 'psi-background-job-terminal-test)
;;; psi-background-job-terminal-test.el ends here
