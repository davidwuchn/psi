;;; psi-background-job-terminal-test.el --- Background job projection rendering tests -*- lexical-binding: t; -*-

(require 'ert)
(require 'psi)

(ert-deftest psi-background-jobs-projection-renders-widget-and-statuses ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "below-editor")
                               (:extension-id . "psi-background-jobs")
                               (:widget-id . "background-jobs")
                               (:content-lines . [((:text . "job-1  [running]  agent-chain")
                                                   (:action . ((:type . "command") (:command . "/job job-1"))))
                                                  ((:text . "job-2  [pending-cancel]  agent-run")
                                                   (:action . ((:type . "command") (:command . "/job job-2"))))]))])))))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/status-updated")
       (:data . ((:statuses . [((:extension-id . "psi-background-jobs/job-1") (:text . "job-1 running"))
                               ((:extension-id . "psi-background-jobs/job-2") (:text . "job-2 pending-cancel"))])))))
    (let ((buf (buffer-string)))
      (should (string-match-p (regexp-quote "job-1  [running]  agent-chain") buf))
      (should (string-match-p (regexp-quote "job-2  [pending-cancel]  agent-run") buf))
      (should (string-match-p (regexp-quote "[psi-background-jobs/job-1] job-1 running") buf))
      (should (string-match-p (regexp-quote "[psi-background-jobs/job-2] job-2 pending-cancel") buf)))))

(provide 'psi-background-job-terminal-test)
;;; psi-background-job-terminal-test.el ends here
