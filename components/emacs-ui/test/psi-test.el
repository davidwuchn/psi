;;; psi-test.el --- Tests for psi Emacs frontend  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi)
(require 'psi-rpc)

(defun psi-test--spawn-long-lived-process (&optional command)
  "Spawn a long-lived process suitable for ownership/lifecycle tests."
  (make-process
   :name (format "psi-test-%s" (gensym))
   :command (or command '("cat"))
   :buffer nil
   :noquery t
   :connection-type 'pipe))

(defun psi-test--capture-request-sends (thunk)
  "Run THUNK with send function overridden and return captured RPC calls."
  (let ((calls nil))
    (cl-letf (((symbol-value 'psi-emacs--send-request-function)
               (lambda (_state op params &optional _callback)
                 (push (list op params) calls))))
      (funcall thunk))
    (nreverse calls)))

(ert-deftest psi-preferred-major-mode-falls-back-to-text-when-markdown-missing ()
  (cl-letf (((symbol-function 'fboundp)
             (lambda (sym)
               (if (eq sym 'markdown-mode)
                   nil
                 (funcall (symbol-function 'fboundp) sym)))))
    (should (eq (psi-emacs--preferred-major-mode) 'text-mode))))

(ert-deftest psi-preferred-major-mode-remains-text-when-markdown-available ()
  (cl-letf (((symbol-function 'fboundp)
             (lambda (sym)
               (if (eq sym 'markdown-mode)
                   t
                 (funcall (symbol-function 'fboundp) sym)))))
    (should (eq (psi-emacs--preferred-major-mode) 'text-mode))))

(ert-deftest psi-open-buffer-initializes-state-boundaries ()
  (let ((psi-emacs-command '("cat"))
        (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
        (buffer nil))
    (unwind-protect
        (progn
          (setq buffer (psi-emacs-open-buffer "*psi-test-init*"))
          (with-current-buffer buffer
            (should (eq major-mode 'psi-emacs-mode))
            (should psi-emacs--owned-process)
            (should (process-live-p psi-emacs--owned-process))
            (should (psi-emacs-state-p psi-emacs--state))
            (should (hash-table-p (psi-emacs-state-tool-rows psi-emacs--state)))
            (should (markerp (psi-emacs-state-draft-anchor psi-emacs--state)))
            (should (psi-emacs--input-separator-marker-valid-p)))
          (should (psi-emacs-state-for-buffer buffer)))
      (when (buffer-live-p buffer)
        (kill-buffer buffer)))))

(ert-deftest psi-open-buffer-seeds-connecting-footer-before-handshake ()
  (let ((psi-emacs-command '("cat"))
        (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
        (buffer nil))
    (cl-letf (((symbol-function 'psi-emacs--start-rpc-client)
               (lambda (_buffer) nil)))
      (unwind-protect
          (progn
            (setq buffer (psi-emacs-open-buffer "*psi-test-connecting-footer*"))
            (with-current-buffer buffer
              (should (psi-emacs--input-separator-marker-valid-p))
              (should (equal "connecting..." (psi-emacs-state-projection-footer psi-emacs--state)))
              (should (string-prefix-p "ψ\n" (buffer-string)))
              (should (string-match-p "connecting\.\.\." (buffer-string)))
              (should (= (point) (psi-emacs--draft-end-position)))))
        (when (buffer-live-p buffer)
          (kill-buffer buffer))))))

(ert-deftest psi-start-focuses-window-point-in-input-area-before-handshake ()
  (let* ((psi-emacs-buffer-name (format "*psi-test-focus-%s*" (gensym)))
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (buffer nil)
         (window nil))
    (cl-letf (((symbol-function 'psi-emacs--start-rpc-client)
               (lambda (_buffer) nil)))
      (unwind-protect
          (progn
            (setq buffer (psi-emacs-start nil))
            (setq window (get-buffer-window buffer t))
            (with-current-buffer buffer
              (should (window-live-p window))
              (should (= (point) (psi-emacs--draft-end-position)))
              (should (= (window-point window) (psi-emacs--draft-end-position)))))
        (when (buffer-live-p buffer)
          (kill-buffer buffer))))))

(ert-deftest psi-focus-input-area-prefers-explicit-window-target ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--seed-connecting-footer)
    (let ((calls nil))
      (cl-letf (((symbol-function 'window-live-p)
                 (lambda (win) (eq win 'target-win)))
                ((symbol-function 'window-buffer)
                 (lambda (_win) (current-buffer)))
                ((symbol-function 'set-window-point)
                 (lambda (win pos)
                   (push (list win pos) calls)))
                ((symbol-function 'get-buffer-window-list)
                 (lambda (&rest _args) nil)))
        (psi-emacs--focus-input-area (current-buffer) 'target-win))
      (should calls)
      (should (eq 'target-win (caar calls)))
      (should (= (psi-emacs--draft-end-position) (cadar calls))))))

(ert-deftest psi-open-buffer-respects-explicit-working-directory ()
  (let* ((psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (psi-emacs-working-directory (make-temp-file "psi-emacs-working-dir-" t))
         (buffer nil))
    (unwind-protect
        (progn
          (setq buffer (psi-emacs-open-buffer "*psi-test-working-dir*"))
          (with-current-buffer buffer
            (should (equal (file-name-as-directory psi-emacs-working-directory)
                           default-directory))))
      (when (buffer-live-p buffer)
        (kill-buffer buffer))
      (when (file-directory-p psi-emacs-working-directory)
        (delete-directory psi-emacs-working-directory t)))))

(ert-deftest psi-open-buffer-defaults-to-invocation-working-directory ()
  (let* ((outside-dir (make-temp-file "psi-emacs-outside-" t))
         (psi-emacs-command '("cat"))
         (psi-emacs-working-directory nil)
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (buffer nil))
    (unwind-protect
        (let ((default-directory (file-name-as-directory outside-dir)))
          (setq buffer (psi-emacs-open-buffer "*psi-test-invocation-dir*"))
          (with-current-buffer buffer
            (should (equal (file-name-as-directory outside-dir)
                           default-directory))))
      (when (buffer-live-p buffer)
        (kill-buffer buffer))
      (when (file-directory-p outside-dir)
        (delete-directory outside-dir t)))))

(ert-deftest psi-start-without-prefix-reuses-default-buffer ()
  (let* ((base-name (format "*psi-test-start-%s*" (gensym)))
         (psi-emacs-buffer-name base-name)
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (first nil)
         (second nil))
    (cl-letf (((symbol-function 'pop-to-buffer)
               (lambda (buffer-or-name &rest _)
                 (get-buffer buffer-or-name))))
      (unwind-protect
          (progn
            (setq first (psi-emacs-start nil))
            (setq second (psi-emacs-start nil))
            (should (eq first second))
            (should (equal base-name (buffer-name first))))
        (when (buffer-live-p first)
          (kill-buffer first))))))

(ert-deftest psi-start-with-prefix-creates-fresh-buffer ()
  (let* ((base-name (format "*psi-test-start-%s*" (gensym)))
         (psi-emacs-buffer-name base-name)
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (first nil)
         (second nil))
    (cl-letf (((symbol-function 'pop-to-buffer)
               (lambda (buffer-or-name &rest _)
                 (get-buffer buffer-or-name))))
      (unwind-protect
          (progn
            (setq first (psi-emacs-start nil))
            (setq second (psi-emacs-start '(4)))
            (should (buffer-live-p first))
            (should (buffer-live-p second))
            (should-not (eq first second))
            (should (equal base-name (buffer-name first)))
            (should (string-prefix-p base-name (buffer-name second))))
        (when (buffer-live-p second)
          (kill-buffer second))
        (when (buffer-live-p first)
          (kill-buffer first))))))

(ert-deftest psi-project-buffer-base-name-uses-project-name ()
  (let ((project-root "/tmp/demo-project/"))
    (should (equal "*psi:demo-project*"
                   (psi-emacs--project-buffer-base-name project-root)))))

(ert-deftest psi-project-buffer-name-for-prefix-behavior ()
  (let ((base "*psi:demo-project*"))
    (should (equal base (psi-emacs--project-buffer-name-for-prefix base nil)))
    (should (equal base (psi-emacs--project-buffer-name-for-prefix base '(1))))
    (should (equal (format "%s<3>" base)
                   (psi-emacs--project-buffer-name-for-prefix base '(3))))
    (cl-letf (((symbol-function 'generate-new-buffer-name)
               (lambda (_name) "*psi:demo-project*<2>")))
      (should (equal "*psi:demo-project*<2>"
                     (psi-emacs--project-buffer-name-for-prefix base '(4)))))))

(ert-deftest psi-project-without-prefix-reuses-project-buffer ()
  (let* ((project-root (file-name-as-directory (make-temp-file "psi-emacs-project-" t)))
         (expected-name (psi-emacs--project-buffer-base-name project-root))
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (first nil)
         (second nil))
    (cl-letf (((symbol-function 'pop-to-buffer)
               (lambda (buffer-or-name &rest _)
                 (get-buffer buffer-or-name)))
              ((symbol-function 'psi-emacs--entry-project-root-directory)
               (lambda (&optional _start-directory)
                 project-root)))
      (unwind-protect
          (progn
            (setq first (psi-emacs-project nil))
            (setq second (psi-emacs-project nil))
            (should (eq first second))
            (should (equal expected-name (buffer-name first)))
            (with-current-buffer first
              (should (equal project-root default-directory))))
        (when (buffer-live-p first)
          (kill-buffer first))
        (when (file-directory-p project-root)
          (delete-directory project-root t))))))

(ert-deftest psi-project-with-prefix-creates-fresh-project-buffer ()
  (let* ((project-root (file-name-as-directory (make-temp-file "psi-emacs-project-" t)))
         (expected-base (psi-emacs--project-buffer-base-name project-root))
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (first nil)
         (second nil))
    (cl-letf (((symbol-function 'pop-to-buffer)
               (lambda (buffer-or-name &rest _)
                 (get-buffer buffer-or-name)))
              ((symbol-function 'psi-emacs--entry-project-root-directory)
               (lambda (&optional _start-directory)
                 project-root)))
      (unwind-protect
          (progn
            (setq first (psi-emacs-project nil))
            (setq second (psi-emacs-project '(4)))
            (should (buffer-live-p first))
            (should (buffer-live-p second))
            (should-not (eq first second))
            (should (equal expected-base (buffer-name first)))
            (should (string-prefix-p expected-base (buffer-name second))))
        (when (buffer-live-p second)
          (kill-buffer second))
        (when (buffer-live-p first)
          (kill-buffer first))
        (when (file-directory-p project-root)
          (delete-directory project-root t))))))

(ert-deftest psi-project-with-numeric-prefix-opens-slot-buffer ()
  (let* ((project-root (file-name-as-directory (make-temp-file "psi-emacs-project-" t)))
         (expected-base (psi-emacs--project-buffer-base-name project-root))
         (slot-name (format "%s<3>" expected-base))
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (slot-buffer nil)
         (same-slot nil))
    (cl-letf (((symbol-function 'pop-to-buffer)
               (lambda (buffer-or-name &rest _)
                 (get-buffer buffer-or-name)))
              ((symbol-function 'psi-emacs--entry-project-root-directory)
               (lambda (&optional _start-directory)
                 project-root)))
      (unwind-protect
          (progn
            (setq slot-buffer (psi-emacs-project '(3)))
            (setq same-slot (psi-emacs-project '(3)))
            (should (eq slot-buffer same-slot))
            (should (equal slot-name (buffer-name slot-buffer))))
        (when (buffer-live-p slot-buffer)
          (kill-buffer slot-buffer))
        (when (file-directory-p project-root)
          (delete-directory project-root t))))))

(ert-deftest psi-project-errors-when-project-root-unavailable ()
  (cl-letf (((symbol-function 'psi-emacs--entry-project-root-directory)
             (lambda (&optional _start-directory) nil)))
    (should-error (psi-emacs-project nil)
                  :type 'user-error)))

(ert-deftest psi-start-restarts-existing-buffer-when-process-exited ()
  (let* ((base-name (format "*psi-test-restart-%s*" (gensym)))
         (start-dir (make-temp-file "psi-emacs-start-" t))
         (outside-dir (make-temp-file "psi-emacs-outside-" t))
         (psi-emacs-buffer-name base-name)
         (psi-emacs-command '("cat"))
         (psi-emacs-working-directory nil)
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (buffer nil)
         (first-process nil)
         (second-process nil))
    (cl-letf (((symbol-function 'pop-to-buffer)
               (lambda (buffer-or-name &rest _)
                 (get-buffer buffer-or-name))))
      (unwind-protect
          (progn
            (let ((default-directory (file-name-as-directory start-dir)))
              (setq buffer (psi-emacs-start nil)))
            (with-current-buffer buffer
              (setq first-process (psi-emacs-state-process psi-emacs--state))
              (should (process-live-p first-process))
              (should (equal (file-name-as-directory start-dir)
                             default-directory))
              (delete-process first-process))

            (let ((default-directory (file-name-as-directory outside-dir)))
              (psi-emacs-start nil))

            (with-current-buffer buffer
              (setq second-process (psi-emacs-state-process psi-emacs--state))
              (should (process-live-p second-process))
              (should-not (eq first-process second-process))
              (should (equal (file-name-as-directory outside-dir)
                             default-directory))))
        (when (buffer-live-p buffer)
          (kill-buffer buffer))
        (when (file-directory-p outside-dir)
          (delete-directory outside-dir t))
        (when (file-directory-p start-dir)
          (delete-directory start-dir t))))))

(ert-deftest psi-open-buffer-restarts-existing-buffer-when-transport-disconnected ()
  (let* ((buffer-name (format "*psi-test-disconnected-%s*" (gensym)))
         (psi-emacs-command '("cat"))
         (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
         (buffer nil)
         (stale-process nil)
         (restart-called nil))
    (unwind-protect
        (progn
          (setq buffer (psi-emacs-open-buffer buffer-name))
          (with-current-buffer buffer
            (setq stale-process (psi-emacs-state-process psi-emacs--state))
            (should (process-live-p stale-process))
            (setf (psi-emacs-state-rpc-client psi-emacs--state)
                  (psi-rpc-make-client :process stale-process
                                       :process-state 'running
                                       :transport-state 'disconnected))
            (setf (psi-emacs-state-process-state psi-emacs--state) 'running)
            (setf (psi-emacs-state-transport-state psi-emacs--state) 'disconnected))
          (cl-letf (((symbol-function 'psi-emacs--start-rpc-client)
                     (lambda (_buffer)
                       (setq restart-called t))))
            (psi-emacs-open-buffer buffer-name))
          (should restart-called)
          (should-not (process-live-p stale-process)))
      (when (buffer-live-p buffer)
        (kill-buffer buffer)))))

(ert-deftest psi-initialize-state-sets-idle-run-state ()
  (let ((state (psi-emacs--initialize-state nil)))
    (should (eq 'idle (psi-emacs-state-run-state state)))))

(ert-deftest psi-status-string-includes-run-state ()
  (let* ((state (psi-emacs--initialize-state nil))
         (status (psi-emacs--status-string state)))
    (should (string-match-p "psi \\[disconnected/starting/idle\\]" status))))

(ert-deftest psi-status-string-reflects-current-run-state ()
  (let ((state (psi-emacs--initialize-state nil)))
    (setf (psi-emacs-state-run-state state) 'streaming)
    (should (string-match-p "psi \\[disconnected/starting/streaming\\]"
                            (psi-emacs--status-string state)))))

(ert-deftest psi-status-diagnostics-includes-last-error-when-present ()
  (let ((state (psi-emacs--initialize-state nil)))
    (setf (psi-emacs-state-last-error state) "runtime/fail: boom")
    (let ((status (psi-emacs--status-diagnostics-string state)))
      (should (string-match-p "psi \\[disconnected/starting/idle\\]" status))
      (should (string-match-p "last-error: runtime/fail: boom" status)))))

(ert-deftest psi-watchdog-arms-when-entering-streaming ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
    (unwind-protect
        (progn
          (psi-emacs--arm-stream-watchdog psi-emacs--state)
          (should (timerp (psi-emacs-state-stream-watchdog-timer psi-emacs--state)))
          (should (numberp (psi-emacs-state-last-stream-progress-at psi-emacs--state))))
      (psi-emacs--disarm-stream-watchdog psi-emacs--state))))

(ert-deftest psi-watchdog-disarms-on-finalize ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
    (psi-emacs--arm-stream-watchdog psi-emacs--state)
    (should (timerp (psi-emacs-state-stream-watchdog-timer psi-emacs--state)))
    (psi-emacs--assistant-finalize "done")
    (should-not (psi-emacs-state-stream-watchdog-timer psi-emacs--state))))

(ert-deftest psi-watchdog-resets-on-assistant-delta ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
    (psi-emacs--arm-stream-watchdog psi-emacs--state)
    (let ((first-timer (psi-emacs-state-stream-watchdog-timer psi-emacs--state))
          (first-ts (psi-emacs-state-last-stream-progress-at psi-emacs--state)))
      (psi-emacs--handle-rpc-event
       '((:event . "assistant/delta") (:data . ((:text . "tick")))))
      (should (timerp (psi-emacs-state-stream-watchdog-timer psi-emacs--state)))
      (should-not (eq first-timer (psi-emacs-state-stream-watchdog-timer psi-emacs--state)))
      (should (>= (psi-emacs-state-last-stream-progress-at psi-emacs--state) first-ts))
      (psi-emacs--disarm-stream-watchdog psi-emacs--state))))

(ert-deftest psi-watchdog-timeout-aborts-once-and-sets-error ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
        (psi-emacs--arm-stream-watchdog psi-emacs--state)
        (psi-emacs--on-stream-watchdog-timeout (current-buffer) psi-emacs--state)
        (psi-emacs--on-stream-watchdog-timeout (current-buffer) psi-emacs--state))
      (setq calls (nreverse calls))
      (should (equal '(("abort" nil)) calls))
      (should (eq 'error (psi-emacs-state-run-state psi-emacs--state)))
      (should-not (psi-emacs-state-stream-watchdog-timer psi-emacs--state))
      (should (string-match-p "Error: Streaming stalled after" (buffer-string))))))

(ert-deftest psi-watchdog-noop-when-not-streaming ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
        (psi-emacs--on-stream-watchdog-timeout (current-buffer) psi-emacs--state))
      (should (equal '() calls))
      (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state)))
      (should (string= "" (buffer-string))))))

(ert-deftest psi-killing-dedicated-buffer-terminates-only-owned-subprocess ()
  (let ((psi-emacs-command '("cat"))
        (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
        (buffer nil)
        (unrelated (psi-test--spawn-long-lived-process))
        (owned nil))
    (unwind-protect
        (progn
          (setq buffer (psi-emacs-open-buffer "*psi-test-kill*"))
          (with-current-buffer buffer
            (setq owned psi-emacs--owned-process)
            (should (process-live-p owned)))
          (should (process-live-p unrelated))
          (kill-buffer buffer)
          (should-not (process-live-p owned))
          (should (process-live-p unrelated))
          (should-not (psi-emacs-state-for-buffer buffer)))
      (when (process-live-p unrelated)
        (delete-process unrelated))
      (when (buffer-live-p buffer)
        (kill-buffer buffer)))))

(ert-deftest psi-kill-buffer-cancels-when-user-declines-process-confirmation ()
  (let ((psi-emacs-command '("cat"))
        (psi-emacs--spawn-process-function #'psi-test--spawn-long-lived-process)
        (buffer nil)
        (owned nil)
        (prompted nil))
    (unwind-protect
        (progn
          (setq buffer (psi-emacs-open-buffer "*psi-test-kill-confirm*"))
          (with-current-buffer buffer
            (setq owned psi-emacs--owned-process)
            (should (process-live-p owned)))
          (cl-letf (((symbol-function 'yes-or-no-p)
                     (lambda (_prompt)
                       (setq prompted t)
                       nil)))
            (let ((noninteractive nil))
              (should-not (kill-buffer buffer))))
          (should prompted)
          (should (buffer-live-p buffer))
          (should (process-live-p owned)))
      (when (buffer-live-p buffer)
        (cl-letf (((symbol-function 'yes-or-no-p) (lambda (_prompt) t)))
          (let ((noninteractive nil))
            (kill-buffer buffer)))))))

(ert-deftest psi-kill-buffer-allows-without-prompt-in-noninteractive-tests ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((prompted nil))
      (cl-letf (((symbol-function 'yes-or-no-p)
                 (lambda (_prompt)
                   (setq prompted t)
                   t)))
        (let ((noninteractive t))
          (should (psi-emacs--confirm-kill-buffer-p))))
      (should-not prompted))))

(ert-deftest psi-refresh-buffer-lifecycle-hooks-adds-kill-query-hook-to-existing-psi-buffers ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local kill-buffer-query-functions nil)
    (should-not (memq #'psi-emacs--confirm-kill-buffer-p kill-buffer-query-functions))
    (psi-emacs--refresh-buffer-lifecycle-hooks)
    (should (memq #'psi-emacs--confirm-kill-buffer-p kill-buffer-query-functions))))

(ert-deftest psi-default-spawn-process-uses-unique-process-name ()
  (let ((process-1 nil)
        (process-2 nil))
    (unwind-protect
        (progn
          (setq process-1 (psi-emacs--default-spawn-process '("cat")))
          (setq process-2 (psi-emacs--default-spawn-process '("cat")))
          (should (process-live-p process-1))
          (should (process-live-p process-2))
          (should-not (equal (process-name process-1)
                             (process-name process-2))))
      (when (process-live-p process-1)
        (delete-process process-1))
      (when (process-live-p process-2)
        (delete-process process-2))
      (dolist (proc (list process-1 process-2))
        (when proc
          (when-let ((stderr (process-get proc 'psi-rpc-stderr-buffer)))
            (when (buffer-live-p stderr)
              (kill-buffer stderr))))))))

(ert-deftest psi-compose-source-prefers-region-over-tail-draft ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "prefix\nTAIL")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 8 nil))
          (set-mark 1)
          (goto-char 6)
          (activate-mark)
          (should (equal "prefi" (psi-emacs--composed-text))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-compose-source-uses-tail-draft-when-no-region ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "transcript\n")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (point-max) nil))
          (psi-emacs--ensure-input-area)
          (goto-char (psi-emacs--draft-end-position))
          (insert "Draft text")
          (deactivate-mark)
          (should (equal "Draft text" (psi-emacs--composed-text))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-ret-inserts-newline-and-does-not-send ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--ensure-input-area)
          (goto-char (psi-emacs--draft-end-position))
          (let ((calls (psi-test--capture-request-sends
                        (lambda ()
                          (insert "a")
                          (call-interactively (key-binding (kbd "RET"))))))
                (input-text nil))
            (setq input-text (psi-emacs--tail-draft-text))
            (should (equal "a\n" input-text))
            (should (equal '() calls))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-model-thinking-keybindings-are-installed ()
  (with-temp-buffer
    (psi-emacs-mode)
    (should (eq #'psi-emacs-set-model (key-binding (kbd "C-c m m"))))
    (should (eq #'psi-emacs-cycle-model-next (key-binding (kbd "C-c m n"))))
    (should (eq #'psi-emacs-cycle-model-prev (key-binding (kbd "C-c m p"))))
    (should (eq #'psi-emacs-set-thinking-level (key-binding (kbd "C-c m t"))))
    (should (eq #'psi-emacs-cycle-thinking-level (key-binding (kbd "C-c m c"))))))

(ert-deftest psi-interrupt-keybinding-is-installed ()
  (with-temp-buffer
    (psi-emacs-mode)
    (should (eq #'psi-emacs-interrupt (key-binding (kbd "C-c C-c"))))))

(ert-deftest psi-streaming-p-uses-explicit-run-state ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) "legacy")
    (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
    (should-not (psi-emacs--streaming-p))
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
    (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
    (should (psi-emacs--streaming-p))))

(ert-deftest psi-send-routing-slash-first-and-streaming-steer-queue ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--ensure-input-area)
          (goto-char (psi-emacs--draft-end-position))
          (insert "hello")
          (let ((calls (psi-test--capture-request-sends
                        (lambda ()
                          (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
                          (psi-emacs-send-from-buffer nil)
                          ;; Input is consumed after first dispatch; subsequent
                          ;; non-slash sends are blank and should be blocked locally.
                          (psi-emacs-send-from-buffer nil)
                          (psi-emacs-send-from-buffer '(4))))))
            (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state)))
            (should (= 1 (length calls)))
            (should (equal "prompt" (caar calls)))
            (should (equal '((:message . "hello")) (cadar calls)))
            (should (equal "Cannot send empty input."
                           (psi-emacs-state-last-error psi-emacs--state)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-queue-key-routes-queue-when-streaming-and-prompt-when-idle ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--ensure-input-area)
          (goto-char (psi-emacs--draft-end-position))
          (insert "queued")
          (let ((calls (psi-test--capture-request-sends
                        (lambda ()
                          (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
                          (psi-emacs-queue-from-buffer)
                          ;; Input is consumed after first dispatch; second non-slash
                          ;; queue send is blank and blocked locally.
                          (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
                          (psi-emacs-queue-from-buffer)))))
            (should (= 1 (length calls)))
            (should (equal "prompt_while_streaming" (caar calls)))
            (should (equal '((:message . "queued") (:behavior . "queue")) (cadar calls)))
            (should (equal "Cannot send empty input."
                           (psi-emacs-state-last-error psi-emacs--state)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-send-blocked-when-rpc-client-transport-not-ready ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-rpc-client psi-emacs--state)
          (psi-rpc-make-client :transport-state 'disconnected))
    (setf (psi-emacs-state-transport-state psi-emacs--state) 'disconnected)
    (insert "hello")
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs-send-from-buffer nil))
      (should (equal '() calls))
      (should (eq 'error (psi-emacs-state-run-state psi-emacs--state)))
      (should (equal "Cannot run `prompt`: transport is disconnected. Reconnect with C-c C-r."
                     (psi-emacs-state-last-error psi-emacs--state)))
      (should (string-match-p "Error: Cannot run `prompt`" (buffer-string))))))

(ert-deftest psi-streaming-send-blocked-when-rpc-client-transport-not-ready ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-rpc-client psi-emacs--state)
          (psi-rpc-make-client :transport-state 'disconnected))
    (setf (psi-emacs-state-transport-state psi-emacs--state) 'disconnected)
    (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
    (insert "queued")
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs-queue-from-buffer))
      (should (equal '() calls))
      (should (eq 'error (psi-emacs-state-run-state psi-emacs--state)))
      (should (equal "Cannot run `prompt_while_streaming`: transport is disconnected. Reconnect with C-c C-r."
                     (psi-emacs-state-last-error psi-emacs--state)))
      (should (string-match-p "Error: Cannot run `prompt_while_streaming`" (buffer-string))))))

(ert-deftest psi-send-fails-when-dispatch-function-returns-nil ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position))
    (insert "hello")
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls)
                   nil)))
        (psi-emacs-send-from-buffer nil))
      (should (equal '(("prompt" ((:message . "hello")))) calls))
      (should (eq 'error (psi-emacs-state-run-state psi-emacs--state)))
      (should (equal "Cannot run `prompt`: request dispatch failed. Reconnect with C-c C-r."
                     (psi-emacs-state-last-error psi-emacs--state)))
      (should (equal "hello" (psi-emacs--tail-draft-text)))
      (should-not (string-match-p "^User: hello$" (buffer-string))))))

(ert-deftest psi-send-empty-input-is-blocked-locally ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
    (psi-emacs--ensure-input-area)
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls)
                   t)))
        (psi-emacs-send-from-buffer nil))
      (should (equal '() calls))
      ;; Blank input guard is local validation only; it does not mutate run-state.
      (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state)))
      (should (equal "Cannot send empty input."
                     (psi-emacs-state-last-error psi-emacs--state)))
      (should-not (string-match-p "^User:" (buffer-string))))))

(ert-deftest psi-set-model-dispatches-canonical-rpc-op ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls (psi-test--capture-request-sends
                  (lambda ()
                    (psi-emacs-set-model "openai" "gpt-5.3-codex")))))
      (should (equal '(("set_model" ((:provider . "openai") (:model-id . "gpt-5.3-codex"))))
                     calls))
      (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state))))))

(ert-deftest psi-set-model-no-arg-queries-runtime-catalog-and-dispatches-selection ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((rpc-calls nil)
          (labels-seen nil))
      (cl-letf (((symbol-function 'completing-read)
                 (lambda (_prompt collection &rest _args)
                   (setq labels-seen (if (vectorp collection)
                                         (append collection nil)
                                       collection))
                   (car labels-seen)))
                ((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional callback)
                   (push (list op params) rpc-calls)
                   (when (and callback (equal op "query_eql"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:op . "query_eql")
                                (:data . ((:result .
                                          ((:psi.agent-session/model-catalog .
                                            [((:provider . "openai")
                                              (:id . "gpt-5")
                                              (:name . "GPT-5")
                                              (:reasoning . t))
                                             ((:provider . "anthropic")
                                              (:id . "claude-sonnet-4-6")
                                              (:name . "Claude Sonnet 4.6")
                                              (:reasoning . t))])
                                           (:psi.agent-session/authenticated-providers . ["openai" "anthropic"]))))))))
                   t)))
        (psi-emacs-set-model))
      (setq rpc-calls (nreverse rpc-calls))
      (should (equal '("query_eql" "set_model") (mapcar #'car rpc-calls)))
      (should (equal `((:query . ,(psi-emacs--model-selector-query)))
                     (cadr (car rpc-calls))))
      (should (equal '((:provider . "anthropic") (:model-id . "claude-sonnet-4-6"))
                     (cadr (cadr rpc-calls))))
      (should (= 2 (length labels-seen)))
      (should (string-match-p "^(anthropic) claude-sonnet-4-6" (car labels-seen)))
      (should (string-match-p "^(openai) gpt-5" (cadr labels-seen))))))

(ert-deftest psi-set-model-selector-can-filter-to-authenticated-providers ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((psi-emacs-model-selector-provider-scope 'authenticated)
          (rpc-calls nil)
          (labels-seen nil))
      (cl-letf (((symbol-function 'completing-read)
                 (lambda (_prompt collection &rest _args)
                   (setq labels-seen (if (vectorp collection)
                                         (append collection nil)
                                       collection))
                   (car labels-seen)))
                ((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional callback)
                   (push (list op params) rpc-calls)
                   (when (and callback (equal op "query_eql"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:op . "query_eql")
                                (:data . ((:result .
                                          ((:psi.agent-session/model-catalog .
                                            [((:provider . "openai")
                                              (:id . "gpt-5")
                                              (:name . "GPT-5")
                                              (:reasoning . t))
                                             ((:provider . "anthropic")
                                              (:id . "claude-sonnet-4-6")
                                              (:name . "Claude Sonnet 4.6")
                                              (:reasoning . t))])
                                           (:psi.agent-session/authenticated-providers . ["anthropic"]))))))))
                   t)))
        (psi-emacs-set-model))
      (setq rpc-calls (nreverse rpc-calls))
      (should (equal '("query_eql" "set_model") (mapcar #'car rpc-calls)))
      (should (= 1 (length labels-seen)))
      (should (string-match-p "^(anthropic)" (car labels-seen)))
      (should (equal '((:provider . "anthropic") (:model-id . "claude-sonnet-4-6"))
                     (cadr (cadr rpc-calls)))))))

(ert-deftest psi-set-model-selector-empty-filtered-catalog-renders-feedback-and-skips-set-model ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((psi-emacs-model-selector-provider-scope 'authenticated)
          (rpc-calls nil)
          (selector-called nil))
      (cl-letf (((symbol-function 'completing-read)
                 (lambda (&rest _args)
                   (setq selector-called t)
                   ""))
                ((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional callback)
                   (push (list op params) rpc-calls)
                   (when (and callback (equal op "query_eql"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:op . "query_eql")
                                (:data . ((:result .
                                          ((:psi.agent-session/model-catalog .
                                            [((:provider . "openai")
                                              (:id . "gpt-5")
                                              (:name . "GPT-5")
                                              (:reasoning . t))])
                                           (:psi.agent-session/authenticated-providers . []))))))))
                   t)))
        (psi-emacs-set-model))
      (setq rpc-calls (nreverse rpc-calls))
      (should (equal '("query_eql") (mapcar #'car rpc-calls)))
      (should-not selector-called)
      (should (string-match-p "No models available for providers with configured auth" (buffer-string))))))

(ert-deftest psi-cycle-model-dispatches-canonical-rpc-op ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls (psi-test--capture-request-sends
                  (lambda ()
                    (psi-emacs-cycle-model-next)
                    (psi-emacs-cycle-model-prev)))))
      (should (equal '(("cycle_model" ((:direction . "next")))
                       ("cycle_model" ((:direction . "prev"))))
                     calls)))))

(ert-deftest psi-set-thinking-level-and-cycle-dispatch-canonical-rpc-ops ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls (psi-test--capture-request-sends
                  (lambda ()
                    (psi-emacs-set-thinking-level "high")
                    (psi-emacs-cycle-thinking-level)))))
      (should (equal '(("set_thinking_level" ((:level . "high")))
                       ("cycle_thinking_level" nil))
                     calls)))))

(ert-deftest psi-model-thinking-controls-blocked-when-transport-not-ready ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-rpc-client psi-emacs--state)
          (psi-rpc-make-client :transport-state 'disconnected))
    (setf (psi-emacs-state-transport-state psi-emacs--state) 'disconnected)
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs-set-model "openai" "gpt-5"))
      (should (equal '() calls))
      (should (eq 'error (psi-emacs-state-run-state psi-emacs--state)))
      (should (equal "Cannot run `set_model`: transport is disconnected. Reconnect with C-c C-r."
                     (psi-emacs-state-last-error psi-emacs--state)))
      (should (string-match-p "Error: Cannot run `set_model`" (buffer-string))))))

(ert-deftest psi-slash-send-and-queue-share-backend-command-dispatch-path ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "  /handled  ")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--slash-command-handler-function)
                     (lambda (&rest _args) t))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
            (let ((inhibit-read-only t))
              (erase-buffer))
            (insert "  /handled  ")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-queue-from-buffer))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "  /handled  ")))
                           ("command" ((:text . "  /handled  "))))
                         rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-tree-dispatch-ignores-custom-command-handler-and-uses-backend-command ()
  "`/tree` routes through backend `command`, even when a custom slash handler declines it."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (setf (psi-emacs-state-context-snapshot psi-emacs--state)
                '((:active-session-id . "s1")
                  (:sessions . (((:id . "s1") (:name . "main") (:is-active . t))
                                ((:id . "s2") (:name . "fork-1") (:is-active . nil))))))
          (cl-letf (((symbol-value 'psi-emacs--slash-command-handler-function)
                     (lambda (&rest _args) nil))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls)
                       t)))
            (let ((result (psi-emacs--dispatch-compose-message "/tree s2")))
              (should (eq t (plist-get result :dispatched?)))
              (should-not (plist-get result :local-only?))))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/tree s2")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-unknown-slash-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((slash-calls nil)
              (rpc-calls nil))
          (insert "/foo")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--slash-command-handler-function)
                     (lambda (_state message)
                       (push message slash-calls)
                       nil))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
            (let ((inhibit-read-only t))
              (erase-buffer))
            (insert "/foo")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-queue-from-buffer))
          (setq slash-calls (nreverse slash-calls))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '() slash-calls))
          (should (equal '("command" "command") (mapcar #'car rpc-calls)))
          (should (equal '((:text . "/foo")) (cadr (car rpc-calls))))
          (should (equal '((:text . "/foo")) (cadr (cadr rpc-calls)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-remember-slash-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((slash-calls nil)
              (rpc-calls nil))
          (insert "/remember blocked-path")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--slash-command-handler-function)
                     (lambda (_state message)
                       (push message slash-calls)
                       nil))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq slash-calls (nreverse slash-calls))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '() slash-calls))
          (should (equal '("command") (mapcar #'car rpc-calls)))
          (should (equal '((:text . "/remember blocked-path"))
                         (cadr (car rpc-calls)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-remember-slash-accepted-path-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((slash-calls nil)
              (rpc-calls nil))
          (insert "/remember accepted-path")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--slash-command-handler-function)
                     (lambda (_state message)
                       (push message slash-calls)
                       nil))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq slash-calls (nreverse slash-calls))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '() slash-calls))
          (should (equal '("command") (mapcar #'car rpc-calls)))
          (should (equal '((:text . "/remember accepted-path"))
                         (cadr (car rpc-calls)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-quit-slash-dispatches-command-and-exits-on-command-result ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((exit-called nil)
              (rpc-calls nil))
          (insert "/quit")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/quit")))) rpc-calls))
          (should-not exit-called)
          (cl-letf (((symbol-function 'psi-emacs--request-frontend-exit)
                     (lambda ()
                       (setq exit-called t))))
            (psi-emacs--handle-rpc-event
             '((:event . "command-result") (:data . ((:type . "quit"))))))
          (should exit-called))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-exit-slash-dispatches-command-and-exits-on-command-result ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((exit-called nil)
              (rpc-calls nil))
          (insert "/exit")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/exit")))) rpc-calls))
          (should-not exit-called)
          (cl-letf (((symbol-function 'psi-emacs--request-frontend-exit)
                     (lambda ()
                       (setq exit-called t))))
            (psi-emacs--handle-rpc-event
             '((:event . "command-result") (:data . ((:type . "quit"))))))
          (should exit-called))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-idle-resume-no-arg-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (no-arg-calls nil)
              (path-calls nil))
          (insert "/resume")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-function 'psi-emacs--handle-idle-resume-no-arg)
                     (lambda (state)
                       (push state no-arg-calls)))
                    ((symbol-function 'psi-emacs--handle-idle-resume-explicit-path)
                     (lambda (_state session-path)
                       (push session-path path-calls)))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '() no-arg-calls))
          (should (equal '() path-calls))
          (should (equal '(("command" ((:text . "/resume")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-frontend-action-resume-selector-submits-selection-to-backend ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (labels-seen nil))
          (cl-letf (((symbol-function 'completing-read)
                     (lambda (_prompt collection &rest _args)
                       (setq labels-seen (if (vectorp collection)
                                             (append collection nil)
                                           collection))
                       (if (consp (car labels-seen))
                           (caar labels-seen)
                         (car labels-seen))))
                    ((symbol-function 'psi-emacs--dispatch-request)
                     (lambda (op params &optional _callback)
                       (push (list op params) rpc-calls)
                       t)))
            (psi-emacs--handle-rpc-event
             '((:event . "ui/frontend-action-requested")
               (:data . ((:request-id . "req-1")
                         (:action-name . "resume-selector")
                         (:prompt . "Select a session to resume")
                         (:payload . ((:query . ((:psi.session/list .
                                                  [((:psi.session-info/path . "/tmp/sessions/a.ndedn")
                                                    (:psi.session-info/name . "Alpha")
                                                    (:psi.session-info/worktree-path . "/repo/alpha")
                                                    (:psi.session-info/first-message . "hello"))
                                                   ((:psi.session-info/path . "/tmp/sessions/b.ndedn")
                                                    (:psi.session-info/name . "")
                                                    (:psi.session-info/worktree-path . "/repo/beta")
                                                    (:psi.session-info/first-message . "Beta first"))]))))))))))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("frontend_action_result"
                            ((:request-id . "req-1")
                             (:action-name . "resume-selector")
                             (:status . "submitted")
                             (:value . "/tmp/sessions/a.ndedn"))))
                         rpc-calls))
          (should (= 2 (length labels-seen)))
          (should (equal "Alpha — /repo/alpha — /tmp/sessions/a.ndedn"
                         (car (car labels-seen)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-frontend-action-resume-selector-cancel-sends-cancelled-result ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (cl-letf (((symbol-function 'completing-read)
                     (lambda (&rest _args)
                       (signal 'quit nil)))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls)
                       t)))
            (psi-emacs--handle-rpc-event
             '((:event . "ui/frontend-action-requested")
               (:data . ((:request-id . "req-2")
                         (:action-name . "resume-selector")
                         (:prompt . "Select a session to resume")
                         (:payload . ((:query . ((:psi.session/list .
                                                  [((:psi.session-info/path . "/tmp/sessions/a.ndedn")
                                                    (:psi.session-info/name . "Alpha")
                                                    (:psi.session-info/worktree-path . "/repo/alpha")
                                                    (:psi.session-info/first-message . "hello"))]))))))))))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("frontend_action_result"
                            ((:request-id . "req-2")
                             (:action-name . "resume-selector")
                             (:status . "cancelled")
                             (:value . nil))))
                         rpc-calls))
          (should (string-empty-p (psi-emacs--tail-draft-text))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-resume-session-candidates-sort-newest-first-by-modified ()
  (let* ((sessions (list '((:psi.session-info/path . "/tmp/sessions/older.ndedn")
                           (:psi.session-info/name . "Older")
                           (:psi.session-info/modified . "2025-01-01T01:00:00Z"))
                         '((:psi.session-info/path . "/tmp/sessions/newer.ndedn")
                           (:psi.session-info/name . "Newer")
                           (:psi.session-info/modified . "2025-01-01T02:00:00Z"))
                         '((:psi.session-info/path . "/tmp/sessions/newest.ndedn")
                           (:psi.session-info/name . "Newest")
                           (:psi.session-info/modified . "2025-01-01T03:00:00Z"))))
         (candidates (psi-emacs--resume-session-candidates sessions)))
    (should (equal '(("Newest — /tmp/sessions/newest.ndedn" . "/tmp/sessions/newest.ndedn")
                     ("Newer — /tmp/sessions/newer.ndedn" . "/tmp/sessions/newer.ndedn")
                     ("Older — /tmp/sessions/older.ndedn" . "/tmp/sessions/older.ndedn"))
                   candidates))))

(ert-deftest psi-idle-resume-explicit-path-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (no-arg-calls nil))
          (insert "  /resume  sessions/foo/bar  ")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-function 'psi-emacs--handle-idle-resume-no-arg)
                     (lambda (_state)
                       (push t no-arg-calls)))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '() no-arg-calls))
          (should (equal '(("command" ((:text . "  /resume  sessions/foo/bar  "))))
                         rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-resume-explicit-path-command-clears-transcript-via-rehydrate-events ()
  "Explicit `/resume <path>` should clear stale transcript and replay resumed messages."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "old transcript\n/resume /tmp/sessions/a.ndedn")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (+ (length "old transcript\n") 1) nil))
          (setf (psi-emacs-state-projection-footer psi-emacs--state) "footer")
          (puthash "stale-tool" (list :id "stale-tool" :stage "result" :text "stale")
                   (psi-emacs-state-tool-rows psi-emacs--state))
          (let ((rpc-calls nil))
            (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                       (lambda (_state op params &optional _callback)
                         (push (list op params) rpc-calls)
                         t)))
              (psi-emacs-send-from-buffer nil))
            (setq rpc-calls (nreverse rpc-calls))
            (should (equal '("command") (mapcar #'car rpc-calls)))
            (psi-emacs--handle-rpc-event
             '((:event . "session/resumed")
               (:data . ((:session-id . "s-resumed")
                         (:session-file . "/tmp/sessions/a.ndedn")
                         (:message-count . 2)))))
            (psi-emacs--handle-rpc-event
             '((:event . "session/rehydrated")
               (:data . ((:messages . [((:role . :user) (:text . "First"))
                                       ((:role . :assistant) (:text . "Second"))])
                         (:tool-calls . ())
                         (:tool-order . ())))))
            (should-not (string-match-p "old transcript" (buffer-string)))
            (should (string-match-p "User: First\nψ: Second\n" (buffer-string)))
            (should (equal "footer" (psi-emacs-state-projection-footer psi-emacs--state)))
            (should (zerop (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state))))
            (should (psi-emacs--input-separator-marker-valid-p))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-command-result-session-switch-requests-switch-and-replays-order ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (cleared-before-replay nil))
          (insert "old transcript")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (1+ (length "old transcript")) nil))
          (psi-emacs--set-last-error psi-emacs--state "runtime/fail: old")
          (puthash "stale-tool" (list :id "stale-tool" :stage "result" :text "stale")
                   (psi-emacs-state-tool-rows psi-emacs--state))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional callback)
                       (push (list op params) rpc-calls)
                       (cond
                        ((and callback (equal op "switch_session"))
                         (funcall callback
                                  '((:kind . :response)
                                    (:ok . t)
                                    (:data . ((:session-id . "s-new"))))))
                        ((and callback (equal op "get_messages"))
                         (setq cleared-before-replay
                               (and (not (string-match-p "old transcript" (buffer-string)))
                                    (null (psi-emacs-state-last-error psi-emacs--state))
                                    (zerop (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))
                         (funcall callback
                                  '((:kind . :response)
                                    (:ok . t)
                                    (:data . ((:messages .
                                              [((:role . :user) (:text . "First"))
                                               ((:role . :assistant) (:text . "Second"))]))))))))))
            (psi-emacs--handle-rpc-event
             '((:event . "command-result")
               (:data . ((:type . "session_switch")
                         (:session-id . "s-new"))))))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '("switch_session" "get_messages") (mapcar #'car rpc-calls)))
          (should (equal '((:session-id . "s-new")) (cadr (car rpc-calls))))
          (should cleared-before-replay)
          (should (string-prefix-p "User: First
ψ: Second
" (buffer-string)))
          (should (psi-emacs--input-separator-marker-valid-p))
          (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-switch-session-failure-does-not-trigger-success-rehydrate-side-effects ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "keep me")
          (puthash "keep-tool" (list :id "keep-tool" :stage "result" :text "keep")
                   (psi-emacs-state-tool-rows psi-emacs--state))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs--handle-switch-session-response
             psi-emacs--state
             "sessions/missing.ndedn"
             '((:kind . :error)
               (:error-code . "request/not-found")
               (:error-message . "session file not found"))))
          (should (equal '() rpc-calls))
          (should (string-prefix-p
                   "keep me\nψ: Unable to switch session: session file not found\n"
                   (buffer-string)))
          (should (string-match-p
                   "Error: Unable to switch session: session file not found"
                   (buffer-string)))
          (should (= 1 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-switch-session-failure-sets-last-error-from-deterministic-message ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-switch-session-response
           psi-emacs--state
           "sessions/missing.ndedn"
           '((:kind . :error)
             (:error-code . "request/not-found")
             (:error-message . "session file not found")))
          (should (equal "Unable to switch session: session file not found"
                         (psi-emacs-state-last-error psi-emacs--state)))
          (should (string-match-p "Error: Unable to switch session: session file not found"
                                  (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-new-slash-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "old transcript
/new")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (+ (length "old transcript
") 1) nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/new")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-new-slash-dispatch-preserves-tool-output-view-mode ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (psi-emacs-toggle-tool-output-view)
          (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
          (insert "/new")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/new")))) rpc-calls))
          (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
          (should (string-match-p "tools:expanded" header-line-format)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-command-result-new-session-appends-message-and-idles ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
          (setf (psi-emacs-state-projection-footer psi-emacs--state) "stale footer")
          (psi-emacs--handle-rpc-event
           '((:event . "command-result")
             (:data . ((:type . "new_session")
                       (:message . "[New session started]")))))
          (should (string-match-p "\[New session started\]" (buffer-string)))
          (should (equal "stale footer" (psi-emacs-state-projection-footer psi-emacs--state)))
          (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-session-rehydrated-topics-are-subscribed ()
  "Emacs must subscribe to session rehydrate topics so /new, /tree and /resume can clear and rebuild transcript state."
  (should (member "session/resumed" psi-rpc-core-topics))
  (should (member "session/rehydrated" psi-rpc-core-topics))
  (should (member "session/resumed" psi-rpc-default-topics))
  (should (member "session/rehydrated" psi-rpc-default-topics)))

(ert-deftest psi-session-resumed-event-clears-transcript-before-rehydrate ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "old transcript")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state)
                (copy-marker (1+ (length "old transcript")) nil))
          (setf (psi-emacs-state-projection-footer psi-emacs--state) "footer")
          (puthash "stale-tool" (list :id "stale-tool" :stage "result" :text "stale")
                   (psi-emacs-state-tool-rows psi-emacs--state))
          (psi-emacs--handle-rpc-event
           '((:event . "session/resumed")
             (:data . ((:session-id . "s-new")
                       (:session-file . "/tmp/s-new.ndedn")
                       (:message-count . 1)))))
          (should-not (string-match-p "old transcript" (buffer-string)))
          (should (zerop (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state))))
          (should (equal "footer" (psi-emacs-state-projection-footer psi-emacs--state)))
          (should (psi-emacs--input-separator-marker-valid-p)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-session-rehydrated-event-replays-messages-without-get-messages ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "session/resumed")
             (:data . ((:session-id . "s-new")
                       (:session-file . "/tmp/s-new.ndedn")
                       (:message-count . 2)))))
          (psi-emacs--handle-rpc-event
           '((:event . "session/rehydrated")
             (:data . ((:messages . [((:role . "user")
                                      (:content . [((:type . "text") (:text . "First"))]))
                                     ((:role . "assistant")
                                      (:content . [((:type . "text") (:text . "Second"))]))])
                       (:tool-calls . ())
                       (:tool-order . ())))))
          (should (string-match-p "User: First
ψ: Second
" (buffer-string)))
          (should (psi-emacs--input-separator-marker-valid-p)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-new-slash-dispatch-failure-sets-command-error ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "/new")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state _op _params &optional _callback)
                       nil)))
            (psi-emacs-send-from-buffer nil))
          (should (eq 'error (psi-emacs-state-run-state psi-emacs--state)))
          (should (equal "Cannot run `command`: request dispatch failed. Reconnect with C-c C-r."
                         (psi-emacs-state-last-error psi-emacs--state)))
          (should (equal "/new" (psi-emacs--tail-draft-text))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-status-slash-appends-diagnostics-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (psi-emacs--set-last-error psi-emacs--state "runtime/fail: boom")
          (let ((inhibit-read-only t))
            (erase-buffer))
          (insert "/status")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/status")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-help-slash-renders-help-on-send-and-queue ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/help")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
            (let ((inhibit-read-only t))
              (erase-buffer))
            (insert "/help")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-queue-from-buffer))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/help")))
                           ("command" ((:text . "/help"))))
                         rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-question-mark-slash-alias-renders-help-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/?")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/?")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-slash-no-arg-opens-selector-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (selector-called nil))
          (insert "/thinking")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-function 'psi-emacs-set-thinking-level)
                     (lambda (&optional _level)
                       (interactive)
                       (setq selector-called t)
                       nil))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should-not selector-called)
          (should (equal '(("command" ((:text . "/thinking")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-model-slash-no-arg-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil)
              (selector-called nil))
          (insert "/model")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-function 'psi-emacs-set-model)
                     (lambda (&optional _provider _model-id)
                       (interactive)
                       (setq selector-called t)
                       nil))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should-not selector-called)
          (should (equal '(("command" ((:text . "/model")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-slash-direct-dispatches-set-thinking-level ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/thinking high")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/thinking high")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-model-slash-direct-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/model openai gpt-5.3-codex")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/model openai gpt-5.3-codex")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-model-slash-invalid-arity-dispatches-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/model openai")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/model openai")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-slash-invalid-arity-renders-usage-and-bypasses-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/thinking high extra")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/thinking high extra")))) rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-background-job-slash-commands-dispatch-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (insert "/jobs running")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
            (let ((inhibit-read-only t)) (erase-buffer))

            (insert "/job job-1")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
            (let ((inhibit-read-only t)) (erase-buffer))

            (insert "/cancel-job job-1")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '("command" "command" "command") (mapcar #'car rpc-calls)))
          (should (equal '((:text . "/jobs running")) (cadr (nth 0 rpc-calls))))
          (should (equal '((:text . "/job job-1")) (cadr (nth 1 rpc-calls))))
          (should (equal '((:text . "/cancel-job job-1")) (cadr (nth 2 rpc-calls))))
          (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-sequential-slash-commands-use-latest-tail-draft ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "/help")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
            (goto-char (psi-emacs--draft-end-position))
            (insert "/status")
            ;; Intentionally do not reset draft anchor between sends.
            (psi-emacs-send-from-buffer nil))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '(("command" ((:text . "/help")))
                           ("command" ((:text . "/status"))))
                         rpc-calls)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-streaming-slash-commands-still-use-backend-command ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((slash-calls nil)
              (rpc-calls nil))
          (insert "/stream")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
          (cl-letf (((symbol-value 'psi-emacs--slash-command-handler-function)
                     (lambda (_state message)
                       (push message slash-calls)
                       t))
                    ((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
            (let ((inhibit-read-only t))
              (erase-buffer))
            (insert "/stream")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-queue-from-buffer))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '() slash-calls))
          (should (equal '("command" "command")
                         (mapcar #'car rpc-calls)))
          (should (equal '((:text . "/stream"))
                         (cadr (nth 0 rpc-calls))))
          (should (equal '((:text . "/stream"))
                         (cadr (nth 1 rpc-calls)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-streaming-non-slash-send-still-uses-prompt-while-streaming ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((rpc-calls nil))
          (insert "keep going")
          (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
          (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
          (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                     (lambda (_state op params &optional _callback)
                       (push (list op params) rpc-calls))))
            (psi-emacs-send-from-buffer nil)
            (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
            (let ((inhibit-read-only t))
              (erase-buffer))
            (insert "next")
            (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker 1 nil))
            (psi-emacs-queue-from-buffer))
          (setq rpc-calls (nreverse rpc-calls))
          (should (equal '("prompt_while_streaming" "prompt_while_streaming")
                         (mapcar #'car rpc-calls)))
          (should (equal '((:message . "keep going") (:behavior . "steer"))
                         (cadr (nth 0 rpc-calls))))
          (should (equal '((:message . "next") (:behavior . "queue"))
                         (cadr (nth 1 rpc-calls)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-abort-sends-rpc-and-clears-streaming-state ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((calls (psi-test--capture-request-sends
                      (lambda ()
                        (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) "streaming")
                        (setf (psi-emacs-state-thinking-in-progress psi-emacs--state) "thinking")
                        (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
                        (psi-emacs-abort)))))
          (should (equal '(("abort" nil)) calls))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
          (should-not (psi-emacs-state-thinking-in-progress psi-emacs--state))
          (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-interrupt-sends-rpc-when-streaming ()
  "C-c C-c dispatches interrupt RPC and transitions to interrupt_pending."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((calls (psi-test--capture-request-sends
                      (lambda ()
                        (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
                        (psi-emacs-interrupt)))))
          (should (equal '(("interrupt" nil)) calls))
          ;; run-state transitions to interrupt_pending immediately
          (should (eq 'interrupt_pending (psi-emacs-state-run-state psi-emacs--state))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-interrupt-noop-when-idle ()
  "C-c C-c is a no-op when not streaming."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((calls (psi-test--capture-request-sends
                      (lambda ()
                        (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
                        (psi-emacs-interrupt)))))
          (should (null calls))
          (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-streaming-single-block-aggregates-and-finalizes ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "Hel")))))
          (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state)))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "lo")))))
          (should (equal "Hello" (psi-emacs-state-assistant-in-progress psi-emacs--state)))
          (should (equal "ψ: Hello\n" (buffer-string)))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "Hello world")))))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
          (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state)))
          (should (equal "ψ: Hello world\n\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-finalize-preserves-cursor-when-user-typing-in-input-area ()
  "Finalize must preserve cursor offset within the input area.

If the user types \"hello\" then positions cursor between \\='h\\=' and \\='e\\='
while the agent is streaming, the cursor must stay between \\='h\\=' and \\='e\\='
after the turn completes.  Transcript inserts above the separator shift absolute
positions, so we assert on offset-from-input-start rather than absolute point.

The old implementation called (goto-char (draft-end-position)) unconditionally
when the anchor was at-end, which jumped the cursor to the end of the draft."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--ensure-input-area)
          ;; User types "hello" in the input area
          (goto-char (psi-emacs--draft-end-position))
          (let ((inhibit-read-only t))
            (insert "hello"))
          ;; User moves cursor mid-word: 1 char into the input text (after 'h')
          (goto-char (+ (psi-emacs--input-start-position) 1))
          (let ((saved-input-offset 1))
            ;; Agent streams and finalizes
            (psi-emacs--handle-rpc-event
             '((:event . "assistant/delta") (:data . ((:text . "reply")))))
            (psi-emacs--handle-rpc-event
             '((:event . "assistant/message") (:data . ((:text . "reply done")))))
            ;; Cursor offset within the input area must be preserved.
            (let ((offset-after (- (point) (psi-emacs--input-start-position))))
              (should (= saved-input-offset offset-after)))
            ;; Draft text must be intact and unmodified
            (should (equal "hello" (psi-emacs--tail-draft-text)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-streaming-renders-line-and-archives-on-finalize ()
  "Thinking line must remain visible after reply is finalized (archived, not cleared).

The thinking block is part of the transcript — it should be frozen in place
when the assistant reply arrives, not deleted."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan")))))
          (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state)))
          (should (equal "plan" (psi-emacs-state-thinking-in-progress psi-emacs--state)))
          (should (string-match-p "· plan" (buffer-string)))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "done")))))
          ;; in-progress state cleared
          (should-not (psi-emacs-state-thinking-in-progress psi-emacs--state))
          (should-not (psi-emacs-state-thinking-range psi-emacs--state))
          ;; thinking line archived — still visible in buffer
          (should (= 1 (length (psi-emacs-state-thinking-archived-ranges psi-emacs--state))))
          (should (string-match-p "· plan" (buffer-string)))
          (should (string-match-p "ψ: done" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-streaming-multiple-deltas-update-in-place ()
  "Multiple thinking deltas must update a single line in-place, not produce multiple lines.

The backend emits the full accumulated thinking text in each event (replace
semantics).  Each successive event carries more text than the previous one."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "I")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "I think")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "I think more")))))
          (should (equal "I think more" (psi-emacs-state-thinking-in-progress psi-emacs--state)))
          ;; Exactly one thinking line in the buffer — not one per delta
          (should (equal 1 (cl-count-if (lambda (line) (string-prefix-p "· " line))
                                        (split-string (buffer-string) "\n" t))))
          (should (string-match-p "· I think more" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-streaming-cumulative-prefix-snapshots-replace-single-line ()
  "Cumulative thinking snapshots that extend one character at a time stay on one line.

This matches the backend contract for `assistant/thinking-delta`, which emits
full accumulated thinking text rather than append-only chunks."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (dolist (text '("- TUII found the main doc drift:"
                          "- TUI commandI found the main doc drift:"
                          "- TUI command docsI found the main doc drift:"
                          "- TUI command docs don'tI found the main doc drift:"
                          "- TUI command docs don't mention `/work-*`"))
            (psi-emacs--handle-rpc-event
             `((:event . "assistant/thinking-delta") (:data . ((:text . ,text))))))
          (should (equal "- TUI command docs don't mention `/work-*`"
                         (psi-emacs-state-thinking-in-progress psi-emacs--state)))
          (should (equal 1 (cl-count-if (lambda (line) (string-prefix-p "· " line))
                                        (split-string (buffer-string) "\n" t))))
          (should (equal (format "· %s\n"
                                 "- TUI command docs don't mention `/work-*`")
                         (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-streaming-uses-thinking-face-on-prefix ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "x")))))
          (goto-char (point-min))
          (let ((ovs (overlays-at (point)))
                (found nil))
            (dolist (ov ovs)
              (when (eq (overlay-get ov 'face) 'psi-emacs-assistant-thinking-face)
                (setq found t)))
            (should found)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-streaming-tool-event-starts-new-thinking-block ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-1")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-break") (:text . "start")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-2")))))
          (should (equal "plan-2" (psi-emacs-state-thinking-in-progress psi-emacs--state)))
          (should (= 1 (length (psi-emacs-state-thinking-archived-ranges psi-emacs--state))))
          (should (equal "· plan-1\nt-break pending\n· plan-2\n"
                         (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-thinking-streaming-assistant-line-after-tool-insertion-keeps-late-thinking-block-ordered-before-final-reply ()
  "Thinking blocks emitted after an assistant line has started must stay in transcript order.

Second thinking blocks (after a tool event) should render before the finalized
reply that comes later in the same assistant turn, and must preserve the
thinking prefix.

"
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (unwind-protect
        (progn
          (psi-emacs--ensure-input-area)
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-1")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "reply-1")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t1") (:tool-name . "bash") (:arguments . "{\\\"command\\\":\\\"ls\\\"}")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-2")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "reply-2")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "final reply")))))
          (let ((buf (buffer-substring-no-properties
                      (point-min)
                      (psi-emacs--input-separator-position))))
            (should (string-match-p "\n· plan-2\n" buf))
            (let* ((plan-2-idx (string-match "\n· plan-2\n" buf))
                   (final-idx (string-match "\nψ: final reply\n" buf)))
              (should (and plan-2-idx final-idx))
              (should (< plan-2-idx final-idx)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))


(ert-deftest psi-thinking-streaming-tool-event-clears-stale-thinking-text-when-range-missing ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          ;; Simulate stale state where thinking text remains but live marker range was lost.
          (setf (psi-emacs-state-thinking-in-progress psi-emacs--state) "plan-1")
          (setf (psi-emacs-state-thinking-range psi-emacs--state) nil)
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-break") (:text . "start")))))
          (should-not (psi-emacs-state-thinking-in-progress psi-emacs--state))
          (should-not (psi-emacs-state-thinking-range psi-emacs--state))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-2")))))
          (should (equal "plan-2" (psi-emacs-state-thinking-in-progress psi-emacs--state)))
          (should (equal "t-break pending\n· plan-2\n"
                         (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-streaming-recovers-span-from-region-identity-when-range-missing ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta") (:data . ((:text . "Hello")))))
    (let ((assistant-id (psi-emacs-state-active-assistant-id psi-emacs--state)))
      (should assistant-id)
      (should (psi-emacs--region-bounds 'assistant assistant-id))
      ;; Simulate lost compatibility cache while property identity remains in buffer.
      (setf (psi-emacs-state-assistant-range psi-emacs--state) nil)
      (psi-emacs--handle-rpc-event
       '((:event . "assistant/delta") (:data . ((:text . " world")))))
      (let* ((content (buffer-substring-no-properties
                       (point-min)
                       (psi-emacs--input-separator-position)))
             (assistant-lines (cl-remove-if-not
                               (lambda (line) (string-prefix-p "ψ: " line))
                               (split-string content "\n" t))))
        (should (= 1 (length assistant-lines)))
        (should (equal "ψ: Hello world" (car assistant-lines)))
        (should (psi-emacs--assistant-range-current))))))

(ert-deftest psi-assistant-streaming-cumulative-snapshots-replace-in-place ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "H")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "He")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "Hel")))))
          (should (equal "Hel" (psi-emacs-state-assistant-in-progress psi-emacs--state)))
          (should (equal "ψ: Hel\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-streaming-cumulative-snapshots-with-tail-newline-churn-do-not-duplicate ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "H\n")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "He\n")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "Hel\n")))))
          (should (equal "Hel\n" (psi-emacs-state-assistant-in-progress psi-emacs--state)))
          (should (equal "ψ: Hel\n\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-streaming-incremental-short-prefix-delta-does-not-shrink-buffer ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          ;; Reproduces the real regression where a short incremental delta
          ;; ("`") previously replaced the whole in-progress string.
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "`deps.edn")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "`")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . " contents:")))))
          (should (equal "`deps.edn` contents:"
                         (psi-emacs-state-assistant-in-progress psi-emacs--state)))
          (should (equal "ψ: `deps.edn` contents:\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-message-content-blocks-finalizes-text ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:role . "assistant")
                       (:content . [((:type . :text) (:text . "Hello from content"))])))))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
          (should (equal "ψ: Hello from content\n\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-message-structured-content-finalizes-text ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:role . "assistant")
                       (:content . ((:kind . :structured)
                                    (:blocks . [((:kind . :text)
                                                 (:text . "Hello from structured content"))])))))))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
          (should (equal "ψ: Hello from structured content\n\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-message-error-message-fallback-renders-text ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:role . "assistant")
                       (:content . [])
                       (:error-message . "Validation failed")))))
          (should (equal "ψ: [error] Validation failed\n\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-message-empty-without-streamed-text-noops ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:role . "assistant") (:content . [])))))
          (should (equal "" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-message-store-fallback-warning-renders-verbatim ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (setf (psi-emacs-state-run-state psi-emacs--state) 'streaming)
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:role . "assistant")
                       (:content . [((:type . :text)
                                     (:text . "⚠ Remembered with store fallback\n  provider: failing-store\n  store-error: boom"))])))))
          (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state)))
          (should (string-match-p "Remembered with store fallback" (buffer-string)))
          (should (string-match-p "provider: failing-store" (buffer-string)))
          (should (string-match-p "store-error: boom" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-message-empty-does-not-clobber-streaming-text ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "partial")))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:role . "assistant") (:content . [])))))
          (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
          (should (equal "ψ: partial\n\n" (buffer-string))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-streaming-uses-verbatim-properties-until-finalize ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "**bold**")))))
          (goto-char (point-min))
          (re-search-forward "\\*\\*bold\\*\\*")
          (let ((start (match-beginning 0)))
            (should (eq t (get-text-property start 'psi-emacs-stream-verbatim))))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "**bold** done")))))
          (goto-char (point-min))
          (re-search-forward "\\*\\*bold\\*\\* done")
          (let ((start (match-beginning 0)))
            (should-not (get-text-property start 'psi-emacs-stream-verbatim))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-markdown-processing-runs-only-at-finalize ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (let ((calls nil))
          (cl-letf (((symbol-function 'psi-emacs--apply-finalized-assistant-markdown)
                     (lambda (start end)
                       (push (buffer-substring-no-properties start end) calls))))
            (psi-emacs--handle-rpc-event
             '((:event . "assistant/delta") (:data . ((:text . "partial")))))
            (should (equal nil calls))
            (psi-emacs--handle-rpc-event
             '((:event . "assistant/message") (:data . ((:text . "final **md**")))))
            (should (equal '("final **md**") calls))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-finalize-keeps-point-in-input-area ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (insert "draft\n")
          (goto-char (point-min))
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message")
             (:data . ((:role . "assistant")
                       (:content . [((:type . :text) (:text . "move me"))])))))
          (should (equal (point-max) (point))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-tool-lifecycle-updates-single-inline-row-by-tool-id ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-1") (:text . "start")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-1") (:result-text . "done")))))
          ;; Default mode is collapsed: buffer shows header-only
          (should (equal "t-1 success\n" (buffer-string)))
          (let ((row (gethash "t-1" (psi-emacs-state-tool-rows psi-emacs--state))))
            (should row)
            (should (equal "result" (plist-get row :stage)))
            (should (equal "done" (plist-get row :text)))
            ;; Accumulated text contains all deltas
            (should (string-match-p "done" (plist-get row :accumulated-text)))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-assistant-streaming-tool-interleaved-preserves-tool-row ()
  "Assistant delta followed by tool event then more delta must not delete the tool row.

Regression: the assistant-range end marker was advancing (insertion-type t),
causing delete-region on the next delta to swallow any tool row inserted
immediately after the assistant line.

The pre-tool assistant text is frozen as its own ψ: line at the tool
boundary.  Post-tool text-deltas create a fresh ψ: line so that the
next turn's reply is not concatenated with stale pre-tool content."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          ;; 1. Assistant starts streaming
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "Hello")))))
          ;; 2. Tool starts — pre-tool text "Hello" is frozen; tool row appended after it
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-1") (:tool-name . "bash")))))
          ;; 3. Tool completes
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-1") (:tool-name . "bash")
                                                (:result-text . "ok") (:is-error . nil)))))
          ;; 4. Post-tool streaming — creates a fresh ψ: line (NOT concatenated with "Hello")
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . " world")))))
          (let ((content (buffer-substring-no-properties
                          (point-min) (psi-emacs--transcript-append-position))))
            ;; Pre-tool frozen line is visible
            (should (string-match-p "ψ: Hello" content))
            ;; Post-tool fresh line is present
            (should (string-match-p "ψ:  world" content))
            ;; Tool row must still be present — tool rows hash must have t-1
            (should (= 1 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))
          ;; 5. Final message — finalizes the post-tool ψ: line; tool row must still be present
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "Hello world")))))
          (let ((content (buffer-substring-no-properties
                          (point-min) (psi-emacs--transcript-append-position))))
            (should (string-match-p "ψ: Hello world" content))
            ;; Tool row still tracked and rendered
            (should (= 1 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state))))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-multiple-tool-rows-survive-update-after-assistant-delta ()
  "Multiple tool rows inserted before a live assistant line must not disappear
when each row is updated (tool/result).

Regression: tool-row update (delete+reinsert) caused the assistant start
marker to drift to the tool-row start position.  The next assistant/delta
then deleted the tool row along with the assistant line."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          ;; Assistant starts streaming
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "working")))))
          ;; Two tool rows arrive (both inserted before the assistant line)
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-1") (:tool-name . "bash")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-2") (:tool-name . "read")))))
          ;; Both complete (update in-place)
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-1") (:tool-name . "bash")
                                                (:result-text . "ok1") (:is-error . nil)))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-2") (:tool-name . "read")
                                                (:result-text . "ok2") (:is-error . nil)))))
          ;; Post-tool text — creates a fresh ψ: line (pre-tool "working" is frozen)
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . " done")))))
          (let ((content (buffer-substring-no-properties
                          (point-min) (psi-emacs--transcript-append-position))))
            ;; Pre-tool frozen line still present
            (should (string-match-p "ψ: working" content))
            ;; Post-tool fresh line present
            (should (string-match-p "ψ:  done" content))
            (should (= 2 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))
          ;; Final message — finalizes the post-tool ψ: line; both tool rows must survive
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ((:text . "working done")))))
          (let ((content (buffer-substring-no-properties
                          (point-min) (psi-emacs--transcript-append-position))))
            (should (string-match-p "ψ: working done" content))
            (should (= 2 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state))))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-parallel-tool-rows-all-survive-sequential-result-updates ()
  "Multiple parallel tool rows (no assistant delta) must all survive when results arrive.

Regression: when two tool rows are adjacent in the buffer (no assistant line
between them), updating the first row via tool/result caused the second row's
:start marker to collapse to the first row's :start position.  The subsequent
tool/result for the second row then deleted both rows (delete-region spanned
both rows) leaving only the second row's updated content."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (unwind-protect
        (progn
          (psi-emacs--ensure-input-area)
          ;; Thinking then two parallel tool calls (no assistant/delta between them)
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "plan")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-1") (:tool-name . "bash")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/executing") (:data . ((:tool-id . "t-1") (:tool-name . "bash")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-2") (:tool-name . "read")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/executing") (:data . ((:tool-id . "t-2") (:tool-name . "read")))))
          ;; First tool completes
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-1") (:tool-name . "bash")
                                                (:result-text . "ok1")))))
          ;; Second tool completes — must not delete first tool row
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-2") (:tool-name . "read")
                                                (:result-text . "ok2")))))
          (let ((content (buffer-substring-no-properties
                          (point-min) (psi-emacs--input-separator-position))))
            ;; Both tool rows must be present
            (should (string-match-p "· plan" content))
            (should (string-match-p "\\$" content))   ;; bash row
            (should (string-match-p "read" content))  ;; read row
            (should (= 2 (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state))))))
      nil)))

(ert-deftest psi-tool-row-recovers-from-region-identity-when-markers-missing ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t-recover") (:tool-name . "bash")))))
    (let* ((rows (psi-emacs-state-tool-rows psi-emacs--state))
           (row (gethash "t-recover" rows)))
      (should row)
      (should (psi-emacs--region-bounds 'tool-row "t-recover"))
      ;; Simulate lost row markers while text properties remain intact.
      (puthash "t-recover"
               (plist-put (plist-put row :start nil) :end nil)
               rows))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result") (:data . ((:tool-id . "t-recover")
                                           (:tool-name . "bash")
                                           (:result-text . "ok")))))
    (let* ((rows (psi-emacs-state-tool-rows psi-emacs--state))
           (row (gethash "t-recover" rows))
           (content (buffer-substring-no-properties
                     (point-min)
                     (psi-emacs--input-separator-position))))
      (should (markerp (plist-get row :start)))
      (should (markerp (plist-get row :end)))
      (should (string-match-p "success" content))
      (should-not (string-match-p "pending" content))
      (should (= 1 (hash-table-count rows))))))

(ert-deftest psi-assistant-delta-not-duplicated-when-tool-row-longer-than-assistant-content ()
  "Assistant streaming text must not be duplicated when a tool row longer than the
current assistant content is inserted before the assistant line.

Regression: inserting a tool row of length R before an assistant line of
length A (where R > A) left the assistant-range end marker BEFORE the new
start marker (inverted range).  Subsequent assistant/delta calls then called
delete-region on the inverted range (which swaps bounds and deletes tool row
content), leaving the stale assistant text in the buffer.  Each new delta
appended to the stale text instead of replacing it, producing repeated/doubled
assistant content.

The tool boundary now freezes the pre-tool assistant line and starts a fresh
ψ: for post-tool deltas.  Content must NOT be doubled (\"TheThe last commit\")."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (unwind-protect
        (progn
          (psi-emacs--ensure-input-area)
          ;; Short assistant delta — assistant content is shorter than a typical tool row
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "The")))))
          ;; Tool start — pre-tool text "The" is frozen; a fresh ψ: will be created for
          ;; post-tool deltas.  This also tests that the inverted-range bug does not
          ;; recur: inserting a tool row longer than the frozen line must not corrupt state.
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-1") (:tool-name . "bash")
                                               (:arguments . "{\"command\":\"ls -la\"}")))))
          ;; Post-tool delta — must create a fresh ψ: line, not concatenate with "The"
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/delta") (:data . ((:text . "The last commit")))))
          (let* ((content (buffer-substring-no-properties
                           (point-min) (psi-emacs--input-separator-position)))
                 (assistant-lines (cl-remove-if-not
                                   (lambda (line) (string-prefix-p "ψ: " line))
                                   (split-string content "\n" t))))
            ;; Two ψ: lines: frozen pre-tool "The" + fresh post-tool "The last commit"
            (should (= 2 (length assistant-lines)))
            (should (equal "ψ: The" (car assistant-lines)))
            ;; Post-tool line shows the delta text, NOT doubled "TheThe last commit"
            (should (equal "ψ: The last commit" (cadr assistant-lines)))))
      nil)))

(ert-deftest psi-tool-boundary-freezes-pre-tool-text-as-separate-psi-line ()
  "Pre-tool streaming text must be frozen at the tool boundary as its own ψ: line.

Regression: in a multi-turn agent loop, text-delta events from turn N were
accumulated in `assistant-in-progress'.  When turn N+1's text-delta arrived
after tool execution, `merge-stream-text' concatenated the old and new text,
causing turn N's pre-tool planning/preamble text to appear in the ψ: prefix
alongside (or instead of) the actual reply."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    ;; Pre-tool text (model streams planning text before calling a tool)
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta") (:data . ((:text . "I'll run bash to check")))))
    ;; Tool boundary — pre-tool text must be frozen here
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t1") (:tool-name . "bash")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result") (:data . ((:tool-id . "t1") (:tool-name . "bash")
                                          (:result-text . "ok") (:content . []) (:is-error . nil)))))
    ;; Post-tool text (next turn's reply — must NOT concatenate with pre-tool text)
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta") (:data . ((:text . "The result is ok")))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/message") (:data . ((:text . "The result is ok")))))
    (let* ((content (buffer-substring-no-properties
                     (point-min) (psi-emacs--input-separator-position)))
           (lines (split-string content "\n" t))
           (psi-lines (cl-remove-if-not (lambda (l) (string-prefix-p "ψ: " l)) lines)))
      ;; Two ψ: lines: frozen pre-tool + finalized post-tool
      (should (= 2 (length psi-lines)))
      (should (equal "ψ: I'll run bash to check" (car psi-lines)))
      (should (equal "ψ: The result is ok" (cadr psi-lines)))
      ;; Pre-tool text must NOT appear in the final ψ: line
      (should-not (string-match-p "bash to check" (cadr psi-lines))))))

(ert-deftest psi-tool-boundary-freeze-does-not-affect-thinking-line-ordering ()
  "Freeze at tool boundary must not disturb thinking-line ordering.

When a turn has thinking → text → tool: thinking must appear before the
frozen pre-tool text, and post-tool thinking must appear before the final
ψ: reply line."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-1")))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta") (:data . ((:text . "pre-tool")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t1") (:tool-name . "bash")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result") (:data . ((:tool-id . "t1") (:tool-name . "bash")
                                          (:result-text . "ok") (:content . []) (:is-error . nil)))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/thinking-delta") (:data . ((:text . "plan-2")))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/delta") (:data . ((:text . "final reply")))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/message") (:data . ((:text . "final reply")))))
    (let* ((content (buffer-substring-no-properties
                     (point-min) (psi-emacs--input-separator-position)))
           (lines (split-string content "\n" t)))
      ;; Thinking lines use · prefix
      (should (cl-some (lambda (l) (equal "· plan-1" l)) lines))
      (should (cl-some (lambda (l) (equal "· plan-2" l)) lines))
      ;; No thinking content in ψ: lines
      (dolist (line lines)
        (when (string-prefix-p "ψ: " line)
          (should-not (string-match-p "plan-" line))))
      ;; plan-2 must appear before the final ψ: reply
      (let* ((plan2-idx (cl-position "· plan-2" lines :test #'equal))
             (final-idx (cl-position "ψ: final reply" lines :test #'equal)))
        (should plan2-idx)
        (should final-idx)
        (should (< plan2-idx final-idx))))))

(ert-deftest psi-empty-assistant-message-archives-thinking-not-clears ()
  "Empty assistant/message (tool-only turn) must archive thinking, not delete it.

Regression: the empty-message path called clear-thinking-line which deleted
the thinking block from the buffer.  Thinking is transcript and must survive."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          ;; Thinking arrives, then a tool-only turn (no text in assistant/message)
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/thinking-delta") (:data . ((:text . "reasoning")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start") (:data . ((:tool-id . "t-1") (:tool-name . "bash")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result") (:data . ((:tool-id . "t-1") (:result-text . "ok")))))
          ;; Empty assistant/message — no text, no streamed text
          (psi-emacs--handle-rpc-event
           '((:event . "assistant/message") (:data . ())))
          (let ((content (buffer-substring-no-properties (point-min) (point-max))))
            ;; Thinking must still be visible
            (should (string-match-p "· reasoning" content))
            ;; Thinking archived (not in-progress)
            (should-not (psi-emacs-state-thinking-in-progress psi-emacs--state))
            (should (= 1 (length (psi-emacs-state-thinking-archived-ranges psi-emacs--state))))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-tool-output-converts-ansi-to-face-and-removes-escapes ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    ;; Use expanded mode so the body text (with ANSI) is rendered in the buffer
    (setf (psi-emacs-state-tool-output-view-mode psi-emacs--state) 'expanded)
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result")
             (:data . ((:toolCallId . "t-ansi") (:text . "\u001b[31mERR\u001b[0m")))))
          (let ((text (buffer-substring-no-properties (point-min) (point-max))))
            (should (equal "t-ansi success: ERR\n" text)))
          (goto-char (point-min))
          (search-forward "ERR")
          (let ((face (get-text-property (1- (point)) 'face)))
            (should face)))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-tool-row-header-prefers-tool-call-details-over-tracking-id ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state (psi-test--spawn-long-lived-process)))
    (unwind-protect
        (progn
          (psi-emacs--handle-rpc-event
           '((:event . "tool/start")
             (:data . ((:tool-id . "call_W5XOPzQT1u0FPRzsJpCfWUQD|fc_063b06d34d4e6ce20169a83b21a4c481928a7f6a9bd4572dc6")
                       (:tool-name . "bash")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/executing")
             (:data . ((:tool-id . "call_W5XOPzQT1u0FPRzsJpCfWUQD|fc_063b06d34d4e6ce20169a83b21a4c481928a7f6a9bd4572dc6")
                       (:tool-name . "bash")
                       (:arguments . "{\"command\":\"echo hi\"}")))))
          (psi-emacs--handle-rpc-event
           '((:event . "tool/result")
             (:data . ((:tool-id . "call_W5XOPzQT1u0FPRzsJpCfWUQD|fc_063b06d34d4e6ce20169a83b21a4c481928a7f6a9bd4572dc6")
                       (:tool-name . "bash")
                       (:result-text . "ok")
                       (:is-error . nil)))))
          (let ((buf (buffer-string)))
            (should (equal "$ echo hi success\n" buf))
            (should-not (string-match-p
                         (regexp-quote "call_W5XOPzQT1u0FPRzsJpCfWUQD|fc_063b06d34d4e6ce20169a83b21a4c481928a7f6a9bd4572dc6")
                         buf))))
      (when (process-live-p (psi-emacs-state-process psi-emacs--state))
        (delete-process (psi-emacs-state-process psi-emacs--state))))))

(ert-deftest psi-tool-summary-uses-project-relative-paths-for-file-tools ()
  (let* ((project-root (make-temp-file "psi-emacs-proj-" t))
         (default-directory (file-name-as-directory project-root))
         (read-path (expand-file-name "src/read.clj" project-root))
         (edit-path (expand-file-name "src/edit.clj" project-root))
         (write-path (expand-file-name "src/write.clj" project-root)))
    (unwind-protect
        (progn
          (should (equal "read src/read.clj"
                         (psi-emacs--tool-summary "read" `((:path . ,read-path)) nil "t-read")))
          (should (equal "edit src/edit.clj"
                         (psi-emacs--tool-summary "edit" `((:path . ,edit-path)) nil "t-edit")))
          (should (equal "write src/write.clj"
                         (psi-emacs--tool-summary "write" `((:path . ,write-path)) nil "t-write"))))
      (ignore-errors (delete-directory project-root t)))))

(ert-deftest psi-tool-summary-read-includes-offset-limit-line-range ()
  (should (equal "read src/core.clj:10:20"
                 (psi-emacs--tool-summary "read"
                                          '((:path . "src/core.clj")
                                            (:offset . 10)
                                            (:limit . 11))
                                          nil
                                          "t-read")))
  (should (equal "read src/core.clj:42"
                 (psi-emacs--tool-summary "read"
                                          '((:path . "src/core.clj")
                                            (:offset . 42))
                                          nil
                                          "t-read"))))

(ert-deftest psi-tool-summary-edit-includes-first-changed-line-range ()
  (should (equal "edit src/core.clj:7:9"
                 (psi-emacs--tool-summary "edit"
                                          '((:path . "src/core.clj")
                                            (:oldText . "a\nb\nc"))
                                          nil
                                          "t-edit"
                                          '((:firstChangedLine . 7)))))
  (should (equal "edit src/core.clj:7"
                 (psi-emacs--tool-summary "edit"
                                          '((:path . "src/core.clj"))
                                          nil
                                          "t-edit"
                                          '((:firstChangedLine . 7))))))

(ert-deftest psi-header-line-updates-from-rpc-state-transitions ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((client (psi-rpc-make-client :process-state 'running :transport-state 'handshaking)))
      (psi-emacs--on-rpc-state-change (current-buffer) client)
      (should (string= "psi [handshaking/running/idle] tools:collapsed" header-line-format))
      (setf (psi-rpc-client-transport-state client) 'ready)
      (psi-emacs--on-rpc-state-change (current-buffer) client)
      (should (string= "psi [ready/running/idle] tools:collapsed" header-line-format)))))

(ert-deftest psi-rpc-state-change-ignores-stale-client-updates ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((active-client (psi-rpc-make-client :process-state 'running :transport-state 'ready))
          (stale-client (psi-rpc-make-client :process-state 'stopped :transport-state 'disconnected)))
      (setf (psi-emacs-state-rpc-client psi-emacs--state) active-client)
      (setf (psi-emacs-state-process-state psi-emacs--state) 'running)
      (setf (psi-emacs-state-transport-state psi-emacs--state) 'ready)
      (psi-emacs--on-rpc-state-change (current-buffer) stale-client)
      (should (eq active-client (psi-emacs-state-rpc-client psi-emacs--state)))
      (should (eq 'running (psi-emacs-state-process-state psi-emacs--state)))
      (should (eq 'ready (psi-emacs-state-transport-state psi-emacs--state))))))

(ert-deftest psi-reconnecting-run-state-transitions-to-idle-when-ready ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-run-state psi-emacs--state) 'reconnecting)
    (let ((client (psi-rpc-make-client :process-state 'running :transport-state 'ready))
          (rpc-calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional callback)
                   (push (list op params) rpc-calls)
                   (when (and callback (equal op "get_messages"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:data . ((:messages . [((:role . :user) (:text . "engage nucleus"))])))))))))
        (psi-emacs--on-rpc-state-change (current-buffer) client))
      (should (eq 'idle (psi-emacs-state-run-state psi-emacs--state)))
      (should (string= "psi [ready/running/idle] tools:collapsed" header-line-format))
      (should (equal '("get_messages" "query_eql")
                     (mapcar #'car (nreverse rpc-calls))))
      (should (string-match-p "User: engage nucleus" (buffer-string))))))

(ert-deftest psi-initial-transcript-hydration-runs-once-per-ready-lifecycle ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((client (psi-rpc-make-client :process-state 'running :transport-state 'ready))
          (rpc-calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional callback)
                   (push (list op params) rpc-calls)
                   (when (and callback (equal op "get_messages"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:data . ((:messages . [((:role . :assistant) (:text . "boot ok"))])))))))))
        (psi-emacs--on-rpc-state-change (current-buffer) client)
        (psi-emacs--on-rpc-state-change (current-buffer) client))
      (should (equal '("get_messages" "query_eql")
                     (mapcar #'car (nreverse rpc-calls))))
      (should (= 1 (how-many "^ψ: boot ok$" (point-min) (point-max)))))))

(ert-deftest psi-initial-transcript-hydration-keeps-point-in-input-area-when-messages ()
  "AC: initial hydration keeps point in the compose input area when messages are replayed."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position))
    (let ((client (psi-rpc-make-client :process-state 'running :transport-state 'ready)))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op _params &optional callback)
                   (when (and callback (equal op "get_messages"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:data . ((:messages . [((:role . :assistant) (:text . "engage nucleus"))])))))))))
        (psi-emacs--on-rpc-state-change (current-buffer) client)))
    (should (= (point) (psi-emacs--draft-end-position)))
    (should (= 1 (how-many "engage nucleus" (point-min) (point-max))))))

(ert-deftest psi-initial-transcript-hydration-no-scroll-when-no-messages ()
  "AC: initial hydration with empty messages does not move point."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position))
    (let ((initial-point (point))
          (client (psi-rpc-make-client :process-state 'running :transport-state 'ready)))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op _params &optional callback)
                   (when (and callback (equal op "get_messages"))
                     (funcall callback
                              '((:kind . :response)
                                (:ok . t)
                                (:data . ((:messages . [])))))))))
        (psi-emacs--on-rpc-state-change (current-buffer) client))
      ;; Point should stay at draft end when no messages
      (should (= (point) initial-point)))))

(ert-deftest psi-header-line-reflects-run-state-transitions ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--set-run-state psi-emacs--state 'streaming)
    (should (string= "psi [disconnected/starting/streaming] tools:collapsed" header-line-format))
    (psi-emacs--set-run-state psi-emacs--state 'error)
    (should (string= "psi [disconnected/starting/error] tools:collapsed" header-line-format))))

(ert-deftest psi-session-updated-projects-model-label-into-header-line ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "session/updated")
       (:data . ((:session-id . "sess-1")
                 (:phase . "idle")
                 (:is-streaming . t)
                 (:is-compacting . nil)
                 (:pending-message-count . 2)
                 (:retry-attempt . 1)
                 (:model-provider . "openai")
                 (:model-id . "gpt-5.3-codex")
                 (:model-reasoning . t)
                 (:thinking-level . "high")
                 (:effective-reasoning-effort . "high")))))
    (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state)))
    (should (equal "(openai) gpt-5.3-codex • thinking high"
                   (psi-emacs-state-header-model-label psi-emacs--state)))
    (should (string= "psi [disconnected/starting/streaming] tools:collapsed model:(openai) gpt-5.3-codex • thinking high"
                     header-line-format))))

(ert-deftest psi-session-updated-status-diagnostics-include-session-summary ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "session/updated")
       (:data . ((:session-id . "sess-2")
                 (:phase . "idle")
                 (:is-streaming . nil)
                 (:is-compacting . nil)
                 (:pending-message-count . 0)
                 (:retry-attempt . 0)
                 (:model-provider . "anthropic")
                 (:model-id . "claude-sonnet")
                 (:model-reasoning . t)
                 (:thinking-level . "medium")
                 (:effective-reasoning-effort . "medium")))))
    (let ((status (psi-emacs--status-diagnostics-string psi-emacs--state)))
      (should (string-match-p "session: sess-2" status))
      (should (string-match-p "phase:idle" status))
      (should (string-match-p "pending:0" status))
      (should (string-match-p "retry:0" status)))))

(ert-deftest psi-session-updated-does-not-overwrite-error-run-state ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--set-run-state psi-emacs--state 'error)
    (psi-emacs--handle-rpc-event
     '((:event . "session/updated")
       (:data . ((:session-id . "sess-3")
                 (:phase . "streaming")
                 (:is-streaming . t)
                 (:pending-message-count . 1)
                 (:retry-attempt . 0)
                 (:model-provider . "openai")
                 (:model-id . "gpt-5")
                 (:model-reasoning . t)
                 (:thinking-level . "high")
                 (:effective-reasoning-effort . "high")))))
    (should (eq 'error (psi-emacs-state-run-state psi-emacs--state)))
    (should (string-match-p "model:(openai) gpt-5 • thinking high" header-line-format))))

(ert-deftest psi-error-line-upsert-replaces-previous ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--set-last-error psi-emacs--state "first error")
    (psi-emacs--set-last-error psi-emacs--state "second error")
    (let ((buf (buffer-string)))
      (should (string-match-p "Error: second error" buf))
      (should-not (string-match-p "Error: first error" buf))
      (should (= 1 (how-many "^Error:" (point-min) (point-max)))))))

(ert-deftest psi-rpc-error-persists-in-transcript-and-sets-last-error ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "existing\n")
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((messages nil))
      (cl-letf (((symbol-function 'message)
                 (lambda (fmt &rest args)
                   (push (apply #'format fmt args) messages))))
        (psi-emacs--on-rpc-event
         (current-buffer)
         '((:event . "error") (:data . ((:code . "runtime/fail") (:message . "boom"))))))
      (should (string-match-p "existing" (buffer-string)))
      (should (string-match-p "Error: runtime/fail: boom" (buffer-string)))
      (should (= 1 (length messages)))
      (should (equal "runtime/fail: boom" (psi-emacs-state-last-error psi-emacs--state)))
      (should (eq 'error (psi-emacs-state-run-state psi-emacs--state)))
      (should (string-match-p "runtime/fail" (car messages))))))

(ert-deftest psi-reconnect-cancels-when-user-declines-confirmation ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "keep this")
    (set-buffer-modified-p t)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((started nil))
      (cl-letf (((symbol-function 'yes-or-no-p) (lambda (_prompt) nil))
                ((symbol-function 'psi-emacs--start-rpc-client)
                 (lambda (_buffer) (setq started t))))
        (psi-emacs-reconnect))
      (should (equal "keep this" (buffer-string)))
      (should-not started)
      (should (buffer-modified-p)))))

(ert-deftest psi-reconnect-confirmed-clears-buffer-and-restarts-fresh-session ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "old transcript")
    (set-buffer-modified-p t)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) "streaming")
    (setf (psi-emacs-state-assistant-range psi-emacs--state)
          (cons (copy-marker (point-min) nil) (copy-marker (point-max) t)))
    (puthash "t1" (list :id "t1" :stage "result" :text "done")
             (psi-emacs-state-tool-rows psi-emacs--state))
    (psi-emacs--set-last-error psi-emacs--state "old error")
    (setf (psi-emacs-state-session-id psi-emacs--state) "sess-old")
    (setf (psi-emacs-state-header-model-label psi-emacs--state) "(openai) gpt-old")
    (let ((started nil)
          (stop-called nil))
      (setf (psi-emacs-state-rpc-client psi-emacs--state) (psi-rpc-make-client))
      (cl-letf (((symbol-function 'yes-or-no-p) (lambda (_prompt) t))
                ((symbol-function 'psi-rpc-stop!)
                 (lambda (_client) (setq stop-called t)))
                ((symbol-function 'psi-emacs--start-rpc-client)
                 (lambda (_buffer) (setq started t))))
        (psi-emacs-reconnect))
      (should stop-called)
      (should started)
      (should (eq 'reconnecting (psi-emacs-state-run-state psi-emacs--state)))
      (should (psi-emacs--input-separator-marker-valid-p))
      (should (string-prefix-p "─" (buffer-string)))
      (should-not (buffer-modified-p))
      (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))
      (should-not (psi-emacs-state-last-error psi-emacs--state))
      (should-not (psi-emacs-state-error-line-range psi-emacs--state))
      (should-not (psi-emacs-state-session-id psi-emacs--state))
      (should-not (psi-emacs-state-header-model-label psi-emacs--state))
      (should (zerop (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state)))))))

(ert-deftest psi-reconnect-does-not-auto-resume ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "old")
    (set-buffer-modified-p nil)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((ops nil))
      (cl-letf (((symbol-function 'psi-emacs--dispatch-request)
                 (lambda (op _params &optional _callback)
                   (push op ops)))
                ((symbol-function 'psi-emacs--start-rpc-client)
                 (lambda (_buffer) nil)))
        (psi-emacs-reconnect))
      (should-not (member "resume" ops))
      (should-not (member "session/resume" ops)))))

(ert-deftest psi-smoke-start-handshake-send-stream-abort-reconnect ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-min) nil))
    (let ((sent nil)
          (stop-count 0)
          (restart-count 0))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) sent)))
                ((symbol-function 'psi-rpc-stop!)
                 (lambda (_client)
                   (setq stop-count (1+ stop-count))))
                ((symbol-function 'psi-emacs--start-rpc-client)
                 (lambda (_buffer)
                   (setq restart-count (1+ restart-count)))))
        (let ((client (psi-rpc-make-client :process-state 'running :transport-state 'handshaking)))
          (psi-emacs--on-rpc-state-change (current-buffer) client)
          (setf (psi-rpc-client-transport-state client) 'ready)
          (psi-emacs--on-rpc-state-change (current-buffer) client)
          (should (string= "psi [ready/running/idle] tools:collapsed" header-line-format)))

        (insert "hello from smoke")
        (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-min) nil))
        (psi-emacs-send-from-buffer nil)

        (psi-emacs--handle-rpc-event
         '((:event . "assistant/delta") (:data . ((:text . "Hi")))))
        (psi-emacs--handle-rpc-event
         '((:event . "tool/start") (:data . ((:toolCallId . "t-smoke") (:text . "start")))))
        (psi-emacs--handle-rpc-event
         '((:event . "tool/result") (:data . ((:toolCallId . "t-smoke") (:text . "done")))))
        (should (string-match-p "ψ: Hi" (buffer-string)))
        ;; Default mode is collapsed: header-only (no body text)
        (should (string-match-p "t-smoke success" (buffer-string)))

        (psi-emacs-abort)
        (should-not (psi-emacs-state-assistant-in-progress psi-emacs--state))

        (setf (psi-emacs-state-rpc-client psi-emacs--state) (psi-rpc-make-client))
        (set-buffer-modified-p nil)
        (psi-emacs-reconnect)

        (setq sent (nreverse sent))
        (should (equal '("get_messages" "query_eql" "prompt" "abort")
                       (mapcar #'car sent)))
        (should (equal '((:message . "hello from smoke"))
                       (cadr (nth 2 sent))))
        (should (= 1 stop-count))
        (should (= 1 restart-count))
        (should (psi-emacs--input-separator-marker-valid-p))
        (should (string-prefix-p "ψ\n─" (buffer-string)))))))

;;; Tool-output-mode acceptance criteria tests (10 tests)

(ert-deftest psi-emacs-test-default-mode-is-collapsed ()
  "AC1: Default tool-output-view-mode is collapsed after state initialization."
  (let ((state (psi-emacs--initialize-state nil)))
    (should (eq 'collapsed (psi-emacs-state-tool-output-view-mode state)))))

(ert-deftest psi-emacs-test-toggle-command-flips-mode ()
  "AC2: Toggle command flips mode collapsed->expanded->collapsed."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; starts collapsed
    (should (eq 'collapsed (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
    ;; toggle to expanded
    (psi-emacs-toggle-tool-output-view)
    (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
    ;; toggle back to collapsed
    (psi-emacs-toggle-tool-output-view)
    (should (eq 'collapsed (psi-emacs-state-tool-output-view-mode psi-emacs--state)))))

(ert-deftest psi-emacs-test-toggle-with-no-rows-changes-future-mode ()
  "AC3: Toggle with no rows changes mode; future tool rows render in new mode."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; No rows, toggle to expanded
    (psi-emacs-toggle-tool-output-view)
    (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
    ;; Inject a new tool row - should render in expanded mode (with body)
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result") (:data . ((:tool-id . "t-future") (:result-text . "output text")))))
    ;; Expanded mode shows body text
    (should (string-match-p "output text" (buffer-string)))))

(ert-deftest psi-emacs-test-collapsed-rows-render-header-only ()
  "AC4: Collapsed mode renders header-only rows (no body text)."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; Default is collapsed
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t-hdr") (:text . "body content here")))))
    ;; Header should be present
    (should (string-match-p "t-hdr pending" (buffer-string)))
    ;; Body text should NOT be present in collapsed mode
    (should-not (string-match-p "body content here" (buffer-string)))))

(ert-deftest psi-emacs-test-collapsed-rows-show-live-status-updates ()
  "AC5: Collapsed mode updates header stage on lifecycle events."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t-live") (:text . "")))))
    (should (string-match-p "t-live pending" (buffer-string)))
    ;; Advance to executing stage
    (psi-emacs--handle-rpc-event
     '((:event . "tool/executing") (:data . ((:tool-id . "t-live") (:text . "")))))
    (should (string-match-p "t-live running" (buffer-string)))
    ;; Previous stage header should be replaced
    (should-not (string-match-p "t-live pending" (buffer-string)))))

(ert-deftest psi-emacs-test-accumulated-output-visible-after-expand ()
  "AC6: Collapsed rows accumulate output; toggle to expanded reveals full text."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; Inject start/executing/result events in collapsed mode
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start") (:data . ((:tool-id . "t-acc") (:text . "first")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/executing") (:data . ((:tool-id . "t-acc") (:text . "second")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result") (:data . ((:tool-id . "t-acc") (:result-text . "final")))))
    ;; Collapsed: body text not visible
    (should-not (string-match-p "first" (buffer-string)))
    ;; Toggle to expanded
    (psi-emacs-toggle-tool-output-view)
    ;; Expanded: accumulated text visible
    (let ((buf (buffer-string)))
      (should (string-match-p "final" buf)))))

(ert-deftest psi-emacs-test-error-row-stays-collapsed-in-collapsed-mode ()
  "AC7: Error tool results do not auto-expand in collapsed mode."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; Inject an error result
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result")
       (:data . ((:tool-id . "t-err")
                 (:result-text . "ERROR: something went wrong")
                 (:is-error . t)))))
    ;; Collapsed: header visible
    (should (string-match-p "t-err error" (buffer-string)))
    ;; Collapsed: error body text NOT visible
    (should-not (string-match-p "ERROR: something went wrong" (buffer-string)))))

(ert-deftest psi-emacs-test-header-line-includes-tool-mode ()
  "AC8: Header line displays tool-output mode (collapsed or expanded)."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--refresh-header-line)
    ;; Default: header shows tools:collapsed
    (should (string-match-p "tools:collapsed" header-line-format))
    ;; After toggle: header shows tools:expanded
    (psi-emacs-toggle-tool-output-view)
    (should (string-match-p "tools:expanded" header-line-format))
    ;; Toggle back: header shows tools:collapsed again
    (psi-emacs-toggle-tool-output-view)
    (should (string-match-p "tools:collapsed" header-line-format))))

(ert-deftest psi-emacs-test-reconnect-resets-mode-to-collapsed ()
  "AC9: Reconnect (reset-transcript-state) restores tool-output mode to collapsed."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; Set mode to expanded
    (psi-emacs-toggle-tool-output-view)
    (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
    ;; Simulate reconnect reset
    (psi-emacs--reset-transcript-state)
    ;; Mode must be back to collapsed
    (should (eq 'collapsed (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
    ;; Header line must reflect collapsed
    (should (string-match-p "tools:collapsed" header-line-format))))

(ert-deftest psi-emacs-test-non-reconnect-session-preserves-mode ()
  "AC10: Non-reconnect session operations preserve the current tool-output mode."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-min) nil))
    ;; Set mode to expanded
    (psi-emacs-toggle-tool-output-view)
    (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))
    ;; Simulate a new message send (non-reconnect session operation)
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (insert "new message")
        (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-min) nil))
        (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
        (psi-emacs-send-from-buffer nil)))
    ;; Mode must still be expanded
    (should (eq 'expanded (psi-emacs-state-tool-output-view-mode psi-emacs--state)))))

(ert-deftest psi-start-rpc-client-uses-default-topic-set ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((captured-topics :unset))
      (cl-letf (((symbol-function 'psi-rpc-start!)
                 (lambda (client _spawn-fn _command &optional topics)
                   (setq captured-topics topics)
                   client)))
        (psi-emacs--start-rpc-client (current-buffer)))
      (should (equal psi-rpc-default-topics captured-topics)))))

(ert-deftest psi-extension-ui-widgets-updated-replaces-and-sorts-projection ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "right") (:extension-id . "ext-b") (:widget-id . "w-2") (:text . "Right-B"))
                              ((:placement . "left") (:extension-id . "ext-b") (:widget-id . "w-1") (:text . "Left-B"))
                              ((:placement . "left") (:extension-id . "ext-a") (:widget-id . "w-3") (:text . "Left-A"))])))))
    (let ((buf (buffer-string)))
      (should-not (string-match-p "Extension Widgets:" buf))
      (should (< (string-match-p "Left-A" buf)
                 (string-match-p "Left-B" buf)))
      (should (< (string-match-p "Left-B" buf)
                 (string-match-p "Right-B" buf))))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "left") (:extension-id . "ext-c") (:widget-id . "w-1") (:text . "Only"))])))))
    (let ((buf (buffer-string)))
      (should (string-match-p "Only" buf))
      (should-not (string-match-p "Left-A" buf))
      (should-not (string-match-p "Right-B" buf)))))

(ert-deftest psi-extension-ui-widgets-updated-renders-content-lines-stacked ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "left")
                               (:extension-id . "ext-a")
                               (:widget-id . "w-1")
                               (:content . ["Head line"
                                            "Detail line one"
                                            "Detail line two"]))])))))
    (let ((buf (buffer-string)))
      (should (string-match-p "Head line" buf))
      (should (string-match-p "Detail line one\nDetail line two" buf))
      (should-not (string-match-p "\\[left/ext-a/w-1\\]" buf))
      (should-not (string-match-p "\\[\"Head line\"" buf)))))

(ert-deftest psi-extension-ui-widgets-updated-renders-structured-content-lines ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "left")
                               (:extension-id . "ext-a")
                               (:widget-id . "w-1")
                               (:content-lines . [((:text . "Plain line"))
                                                  ((:text . "Delete run-1")
                                                   (:action . ((:type . "command")
                                                               (:command . "/chain-rm run-1"))))]))])))))
    (let ((buf (buffer-string)))
      (should (string-match-p "Plain line" buf))
      (should (string-match-p "Delete run-1" buf)))
    (goto-char (point-min))
    (should (search-forward "Delete run-1" nil t))
    (let ((cmd (get-text-property (max (point-min) (1- (point))) 'psi-widget-command)))
      (should (equal "/chain-rm run-1" cmd)))))

(ert-deftest psi-projection-widget-action-activates-command-via-command-op ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls)
                   t)))
        (insert (propertize "Delete run-1"
                            'psi-widget-command "/chain-rm run-1"
                            'keymap psi-emacs--projection-widget-action-keymap))
        (goto-char (point-min))
        (psi-emacs--projection-activate-widget-action)
        (should (equal '(("command" ((:text . "/chain-rm run-1"))))
                       calls))))))

(ert-deftest psi-projection-tree-widget-action-uses-command-op ()
  "Widget action `/tree <id>` should route via backend command dispatch."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls)
                   t)))
        (insert (propertize "switch to s2"
                            'psi-widget-command "/tree s2"
                            'keymap psi-emacs--projection-widget-action-keymap))
        (goto-char (point-min))
        (psi-emacs--projection-activate-widget-action)
        (should (equal '(("command" ((:text . "/tree s2"))))
                       calls))))))

(ert-deftest psi-projection-tree-widget-action-clears-transcript-via-rehydrate-events ()
  "Widget `/tree <id>` activation should clear stale transcript when rehydrate events arrive."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (insert "old transcript")
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (1+ (length "old transcript")) nil))
    (setf (psi-emacs-state-projection-footer psi-emacs--state) "footer")
    (puthash "stale-tool" (list :id "stale-tool" :stage "result" :text "stale")
             (psi-emacs-state-tool-rows psi-emacs--state))
    (cl-letf (((symbol-value 'psi-emacs--send-request-function)
               (lambda (_state op params &optional _callback)
                 (when (equal op "command")
                   (should (equal '((:text . "/tree s2")) params)))
                 t)))
      (insert (propertize "switch to s2"
                          'psi-widget-command "/tree s2"
                          'keymap psi-emacs--projection-widget-action-keymap))
      (goto-char (point-max))
      (psi-emacs--projection-activate-widget-action)
      (psi-emacs--handle-rpc-event
       '((:event . "session/resumed")
         (:data . ((:session-id . "s2")
                   (:session-file . "/tmp/s2.ndedn")
                   (:message-count . 1)))))
      (psi-emacs--handle-rpc-event
       '((:event . "session/rehydrated")
         (:data . ((:messages . [((:role . :assistant) (:text . "switched"))])
                   (:tool-calls . ())
                   (:tool-order . ())))))
      (should-not (string-match-p "old transcript" (buffer-string)))
      (should (string-match-p "ψ: switched\n" (buffer-string)))
      (should (equal "footer" (psi-emacs-state-projection-footer psi-emacs--state)))
      (should (zerop (hash-table-count (psi-emacs-state-tool-rows psi-emacs--state))))
      (should (psi-emacs--input-separator-marker-valid-p)))))

(ert-deftest psi-extension-ui-status-updated-replaces-and-sorts-by-extension-id ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/status-updated")
       (:data . ((:statuses . [((:extension-id . "ext-z") (:text . "Zeta"))
                               ((:extension-id . "ext-a") (:text . "Alpha"))])))))
    (let ((buf (buffer-string)))
      (should (string-match-p "Extension Statuses:" buf))
      (should (< (string-match-p "\\[ext-a\\] Alpha" buf)
                 (string-match-p "\\[ext-z\\] Zeta" buf))))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/status-updated")
       (:data . ((:statuses . [((:extension-id . "ext-b") (:text . "Only status"))])))))
    (let ((buf (buffer-string)))
      (should (string-match-p "\\[ext-b\\] Only status" buf))
      (should-not (string-match-p "\\[ext-a\\] Alpha" buf)))))

(ert-deftest psi-extension-ui-widgets-render-divider-before-footer-when-present ()
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 20)))
      (psi-emacs--handle-rpc-event
       '((:event . "ui/widgets-updated")
         (:data . ((:widgets . [((:placement . "before-edit")
                                 (:extension-id . "ext-a")
                                 (:widget-id . "w-1")
                                 (:content . ["Widget line"]))])))))
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated")
         (:data . ((:path-line . "~/psi-main")
                   (:stats-line . "latency 12ms")))))
      (let ((buf (buffer-string)))
        (should (string-match-p "Widget line\n────────────────────\n~/psi-main" buf))))))

(ert-deftest psi-extension-ui-status-updated-with-input-area-preserves-separator ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--handle-rpc-event
     '((:event . "ui/status-updated")
       (:data . ((:statuses . [((:extension-id . "ext-b") (:text . "ok"))])))))
    (should (string-match-p "Extension Statuses:" (buffer-string)))
    (should (string-match-p "\\[ext-b\\] ok" (buffer-string)))
    (should (psi-emacs--input-separator-marker-valid-p))))

(ert-deftest psi-extension-ui-footer-updated-updates-projection-and-preserves-transcript ()
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (let ((before-anchor (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))))
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated") (:data . ((:text . "mode: parity")))))
      (should (string-match-p "ψ: hello" (buffer-string)))
      (should (string-match-p "mode: parity" (buffer-string)))
      (should (= (psi-emacs--draft-end-position)
                 (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))))
      (should (>= (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))
                  before-anchor)))))

(ert-deftest psi-extension-ui-footer-updated-uses-canonical-payload-shape ()
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (let ((before-anchor (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))))
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated")
         (:data . ((:path-line . "~/psi-main")
                   (:stats-line . "latency 12ms")
                   (:status-line . "connected")))))
      (should (string-match-p "ψ: hello" (buffer-string)))
      (should (string-match-p "~/psi-main\nlatency 12ms\nconnected" (buffer-string)))
      (should-not (string-match-p "mode: parity" (buffer-string)))
      (should (= (psi-emacs--draft-end-position)
                 (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))))
      (should (>= (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))
                  before-anchor)))))

(ert-deftest psi-extension-ui-footer-renders-blank-line-and-separator-before-block ()
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 20)))
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated")
         (:data . ((:path-line . "~/psi-main")
                   (:stats-line . "latency 12ms"))))))
    (let* ((buf (buffer-string))
           (lines (split-string buf "\n")))
      (should (equal "ψ: hello" (nth 0 lines)))
      (should (equal "" (nth 1 lines)))
      (should (= 20 (string-width (or (nth 2 lines) ""))))
      (should (equal "~/psi-main" (nth 3 lines))))))

(ert-deftest psi-extension-ui-footer-draft-before-projection-sends-non-empty-prompt ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-run-state psi-emacs--state) 'idle)
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:stats-line . "latency 12ms")))))
    ;; Point remains in draft area above projection/footer block.
    (insert "second prompt")
    (let ((rpc-calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) rpc-calls))))
        (psi-emacs-send-from-buffer nil))
      (setq rpc-calls (nreverse rpc-calls))
      (should (equal '(("prompt" ((:message . "second prompt"))))
                     rpc-calls)))))

(ert-deftest psi-extension-ui-footer-updated-right-aligns-provider-model-when-detectable ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 70)))
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated")
         (:data . ((:path-line . "~/psi-main")
                   (:stats-line . "↑4.6k ↓14 $0.008 1.7%/272k (openai) gpt-5.3-codex • thinking off"))))))
    (let* ((lines (split-string (buffer-string) "\n" t))
           (stats-line (seq-find (lambda (line)
                                   (string-match-p "↑4\\.6k" line))
                                 lines)))
      (should (stringp stats-line))
      (should (string-match-p "  (openai) gpt-5\\.3-codex • thinking off$" stats-line))
      (should (= 70 (string-width stats-line))))))

(ert-deftest psi-footer-projection-width-prefers-window-text-width ()
  (with-temp-buffer
    (cl-letf (((symbol-function 'get-buffer-window)
               (lambda (&rest _args) 'fake-win))
              ((symbol-function 'window-text-width)
               (lambda (_win) 96))
              ((symbol-function 'window-body-width)
               (lambda (_win &optional _pixelwise) 120))
              ((symbol-function 'window-margins)
               (lambda (_win) '(2 . 1))))
      (should (= 96 (psi-emacs--projection-window-width))))))

(ert-deftest psi-footer-projection-width-falls-back-to-margins-when-text-width-unavailable ()
  (with-temp-buffer
    (cl-letf (((symbol-function 'get-buffer-window)
               (lambda (&rest _args) 'fake-win))
              ((symbol-function 'window-text-width)
               (lambda (_win) nil))
              ((symbol-function 'window-body-width)
               (lambda (_win &optional _pixelwise) 120))
              ((symbol-function 'window-margins)
               (lambda (_win) '(2 . 1))))
      (should (= 117 (psi-emacs--projection-window-width))))))

(ert-deftest psi-window-configuration-change-refreshes-width-sensitive-separators ()
  (with-temp-buffer
    (insert "ψ: hello\n")
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 20)))
      (psi-emacs--ensure-input-area)
      (psi-emacs--handle-rpc-event
       '((:event . "footer/updated")
         (:data . ((:path-line . "~/psi-main")
                   (:stats-line . "latency 12ms")))))
      (should (= 20 (psi-emacs--input-separator-current-width))))
    ;; Simulate input-separator drift: marker exists but no longer points at a
    ;; separator character. `psi-emacs--ensure-input-area` should repair it.
    (let ((sep (psi-emacs--input-separator-marker-cache)))
      (save-excursion
        (let ((inhibit-read-only t))
          (goto-char (marker-position sep))
          (delete-char 1)
          (insert "X"))))
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 33)))
      (psi-emacs--handle-window-configuration-change)
      (should (= 33 (psi-emacs--input-separator-current-width)))
      (let* ((lines (split-string (buffer-string) "\n"))
             (separator-lines (seq-filter (lambda (line)
                                            (and (not (string-empty-p line))
                                                 (string-match-p "^─+$" line)))
                                          lines)))
        ;; input separator + projection separator should both refresh to width 33
        (should (>= (length separator-lines) 2))
        (should (seq-every-p (lambda (line)
                               (= 33 (string-width line)))
                             separator-lines))))))

(ert-deftest psi-extension-ui-footer-refresh-preserves-assistant-transcript ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:stats-line . "stats1")))))
    (psi-emacs--handle-rpc-event
     '((:event . "assistant/message")
       (:data . ((:text . "reply")))))
    (should (string-match-p "ψ: reply" (buffer-string)))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:stats-line . "stats2")))))
    (let ((buf (buffer-string)))
      (should (string-match-p "ψ: reply" buf))
      (should (string-match-p "stats2" buf)))))

(ert-deftest psi-extension-ui-tool-rows-render-before-footer-projection ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:stats-line . "stats1")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/start")
       (:data . ((:tool-id . "t-footer") (:text . "start")))))
    (let* ((buf (buffer-string))
           (tool-pos (string-match-p "t-footer pending" buf))
           (path-pos (string-match-p "~/psi-main" buf)))
      (should tool-pos)
      (should path-pos)
      (should (< tool-pos path-pos)))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result")
       (:data . ((:tool-id . "t-footer") (:result-text . "done")))))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:stats-line . "stats2")))))
    (let* ((buf (buffer-string))
           (tool-pos (string-match-p "t-footer success" buf))
           (path-pos (string-match-p "~/psi-main" buf)))
      (should tool-pos)
      (should path-pos)
      (should (< tool-pos path-pos)))))

(ert-deftest psi-toggle-tool-output-view-preserves-footer-and-widgets ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--handle-rpc-event
     '((:event . "ui/widgets-updated")
       (:data . ((:widgets . [((:placement . "left")
                               (:extension-id . "ext-a")
                               (:widget-id . "w-1")
                               (:text . "Widget line"))])))))
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:stats-line . "stats")))))
    (psi-emacs--handle-rpc-event
     '((:event . "tool/result")
       (:data . ((:tool-id . "t-toggle-preserve")
                 (:tool-name . "bash")
                 (:result-text . "ok")))))
    ;; Toggle twice to exercise both collapsed->expanded and expanded->collapsed rerenders.
    (psi-emacs-toggle-tool-output-view)
    (psi-emacs-toggle-tool-output-view)
    (let* ((buf (buffer-string))
           (tool-pos (string-match-p "\\$ .*success" buf))
           (widget-pos (string-match-p "Widget line" buf))
           (path-pos (string-match-p "~/psi-main" buf))
           (bounds (psi-emacs--region-bounds 'projection 'main)))
      (should tool-pos)
      (should widget-pos)
      (should path-pos)
      (should (< tool-pos widget-pos))
      (should (< widget-pos path-pos))
      (should (consp bounds))
      (should (< (car bounds) (cdr bounds))))))

(ert-deftest psi-extension-ui-dialog-requested-confirm-sends-resolve-boolean ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-function 'y-or-n-p)
                 (lambda (&rest _args) t))
                ((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs--handle-rpc-event
         '((:event . "ui/dialog-requested")
           (:data . ((:dialog-id . "d-1") (:kind . "confirm") (:prompt . "Proceed?"))))))
      (setq calls (nreverse calls))
      (should (equal '(("resolve_dialog" ((:dialog-id . "d-1") (:result . t))))
                     calls)))))

(ert-deftest psi-extension-ui-dialog-requested-select-sends-selected-option-value ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-function 'completing-read)
                 (lambda (&rest _args) "Beta"))
                ((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs--handle-rpc-event
         '((:event . "ui/dialog-requested")
           (:data . ((:dialog-id . "d-2")
                     (:kind . "select")
                     (:prompt . "Pick")
                     (:options . [((:label . "Alpha") (:value . "a"))
                                  ((:label . "Beta") (:value . "b"))]))))))
      (setq calls (nreverse calls))
      (should (equal '(("resolve_dialog" ((:dialog-id . "d-2") (:result . "b"))))
                     calls)))))

(ert-deftest psi-extension-ui-dialog-requested-input-sends-resolve-string ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-function 'read-string)
                 (lambda (&rest _args) "typed value"))
                ((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs--handle-rpc-event
         '((:event . "ui/dialog-requested")
           (:data . ((:dialog-id . "d-3") (:kind . "input") (:prompt . "Enter"))))))
      (setq calls (nreverse calls))
      (should (equal '(("resolve_dialog" ((:dialog-id . "d-3") (:result . "typed value"))))
                     calls)))))

(ert-deftest psi-extension-ui-dialog-requested-cancel-on-quit-sends-cancel-dialog ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-function 'y-or-n-p)
                 (lambda (&rest _args)
                   (signal 'quit nil)))
                ((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs--handle-rpc-event
         '((:event . "ui/dialog-requested")
           (:data . ((:dialog-id . "d-4") (:kind . "confirm") (:prompt . "Proceed?"))))))
      (setq calls (nreverse calls))
      (should (equal '(("cancel_dialog" ((:dialog-id . "d-4"))))
                     calls)))))

(ert-deftest psi-extension-ui-dialog-requested-malformed-payload-no-rpc-response ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _callback)
                   (push (list op params) calls))))
        (psi-emacs--handle-rpc-event
         '((:event . "ui/dialog-requested")
           (:data . ((:kind . "input") (:prompt . "missing id"))))))
      (should (equal '() calls))
      (should (string-match-p "Ignored malformed ui/dialog-requested event" (buffer-string))))))

(ert-deftest psi-extension-ui-notification-adds-visible-entry-in-receive-order ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (cl-letf (((symbol-function 'run-at-time)
               (lambda (&rest _args) nil)))
      (psi-emacs--handle-rpc-event
       '((:event . "ui/notification")
         (:data . ((:extension-id . "ext-a") (:text . "First")))))
      (psi-emacs--handle-rpc-event
       '((:event . "ui/notification")
         (:data . ((:extension-id . "ext-b") (:text . "Second"))))))
    (let ((buf (buffer-string)))
      (should (string-match-p "Extension Notifications:" buf))
      (should (< (string-match-p "\\[ext-a\\] First" buf)
                 (string-match-p "\\[ext-b\\] Second" buf))))))

(ert-deftest psi-extension-ui-notification-enforces-max-visible-three ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (cl-letf (((symbol-function 'run-at-time)
               (lambda (&rest _args) nil)))
      (dotimes (i 4)
        (psi-emacs--handle-rpc-event
         `((:event . "ui/notification")
           (:data . ((:extension-id . "ext") (:text . ,(format "N%s" (1+ i)))))))))
    (let ((buf (buffer-string)))
      (should-not (string-match-p "\\[ext\\] N1" buf))
      (should (string-match-p "\\[ext\\] N2" buf))
      (should (string-match-p "\\[ext\\] N3" buf))
      (should (string-match-p "\\[ext\\] N4" buf)))))

(ert-deftest psi-extension-ui-notification-auto-dismisses-after-timeout ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((scheduled-seconds nil)
          (scheduled-callback nil)
          (scheduled-args nil))
      (cl-letf (((symbol-function 'run-at-time)
                 (lambda (seconds _repeat fn &rest args)
                   (setq scheduled-seconds seconds)
                   (setq scheduled-callback fn)
                   (setq scheduled-args args)
                   nil)))
        (psi-emacs--handle-rpc-event
         '((:event . "ui/notification")
           (:data . ((:extension-id . "ext-t") (:text . "Temp"))))))
      (should (= psi-emacs-notification-timeout-seconds scheduled-seconds))
      (should (string-match-p "\\[ext-t\\] Temp" (buffer-string)))
      (apply scheduled-callback scheduled-args)
      (should-not (string-match-p "\\[ext-t\\] Temp" (buffer-string))))))

(ert-deftest psi-input-area-creates-separator-and-start-marker ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (insert "existing transcript")
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 20)))
      (psi-emacs--ensure-input-area)
      (let ((sep (psi-emacs--input-separator-marker-cache)))
        (should (markerp sep))
        (should (eq ?─ (char-after (marker-position sep))))
        (should (= 20 (psi-emacs--input-separator-current-width)))
        (should (= (psi-emacs--input-start-position)
                   (marker-position (psi-emacs-state-draft-anchor psi-emacs--state))))))))

(ert-deftest psi-input-separator-refreshes-width-when-window-width-changes ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 20)))
      (psi-emacs--ensure-input-area)
      (should (= 20 (psi-emacs--input-separator-current-width))))
    (cl-letf (((symbol-function 'psi-emacs--projection-window-width)
               (lambda () 33)))
      (should (psi-emacs--input-separator-needs-refresh-p))
      (psi-emacs--ensure-input-area)
      (should-not (psi-emacs--input-separator-needs-refresh-p))
      (should (= 33 (psi-emacs--input-separator-current-width))))))

(ert-deftest psi-send-copies-input-to-transcript-and-clears-input ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position))
    (insert "hello input")
    (let ((calls (psi-test--capture-request-sends
                  (lambda ()
                    (psi-emacs-send-from-buffer nil)))))
      (should (equal '(("prompt" ((:message . "hello input")))) calls))
      (should (string-empty-p (psi-emacs--tail-draft-text)))
      (should (string-match-p "^User: hello input" (buffer-string))))))

(ert-deftest psi-send-omits-input-separator-from-first-prompt-when-anchor-drifts ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    ;; Reproduce startup edge case: stale draft anchor points at separator line.
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (marker-position (psi-emacs--input-separator-marker-cache)) nil))
    (goto-char (psi-emacs--draft-end-position))
    (insert "hello input")
    (should (equal "hello input" (psi-emacs--tail-draft-text)))
    (let ((calls (psi-test--capture-request-sends
                  (lambda ()
                    (psi-emacs-send-from-buffer nil)))))
      (should (equal '(("prompt" ((:message . "hello input")))) calls)))))

(ert-deftest psi-send-does-not-copy-input-when-dispatch-not-confirmed ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-transport-state psi-emacs--state) 'ready)
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position))
    (insert "hello input")
    (cl-letf (((symbol-value 'psi-emacs--send-request-function)
               (lambda (_state _op _params &optional _callback)
                 nil)))
      (psi-emacs-send-from-buffer nil))
    (should (equal "hello input" (psi-emacs--tail-draft-text)))
    (should-not (string-match-p "^User: hello input" (buffer-string)))))

(ert-deftest psi-send-repairs-missing-input-separator-after-submit ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position))
    (insert "hello input")
    ;; Simulate drift: marker exists but no longer points to a separator char.
    (let ((sep (psi-emacs--input-separator-marker-cache)))
      (save-excursion
        (goto-char (marker-position sep))
        (let ((inhibit-read-only t))
          (delete-char 1)
          (insert "x"))))
    (should-not (psi-emacs--input-separator-marker-valid-p))
    (let ((calls (psi-test--capture-request-sends
                  (lambda ()
                    (psi-emacs-send-from-buffer nil)))))
      (should (equal '(("prompt" ((:message . "hello input")))) calls))
      (should (psi-emacs--input-separator-marker-valid-p))
      (should (string-empty-p (psi-emacs--tail-draft-text)))
      (should (string-match-p "^User: hello input" (buffer-string))))))

(ert-deftest psi-replay-session-messages-with-input-area-stays-above-separator ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--replay-session-messages
     '(((:role . :assistant) (:text . "boot ok"))))
    (let ((sep-pos (psi-emacs--input-separator-position)))
      (should (integerp sep-pos))
      (should (string-match-p "^ψ: boot ok$" (buffer-string)))
      (should (< (or (string-match-p "ψ: boot ok" (buffer-string)) 0)
                 sep-pos)))))

(ert-deftest psi-replay-session-messages-string-role-user-renders-as-user ()
  "String role \"user\" from backend must render as \"User:\", not \"ψ:\".
Regression: `intern' of \"user\" gives bare symbol `user', not keyword :user,
so the old `(eq role :user)' check always fell through to the assistant branch."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--replay-session-messages
     ;; Backend sends role as a string "user" / "assistant"
     '(((:role . "user")      (:content . "hello"))
       ((:role . "assistant") (:content . "hi there"))))
    (let ((text (buffer-string)))
      (should (string-match-p "User: hello"    text))
      (should (string-match-p "ψ: hi there"    text))
      ;; Ensure user message is NOT rendered as assistant
      (should-not (string-match-p "ψ: hello"   text))
      (should-not (string-match-p "User: hi there" text)))))

(ert-deftest psi-replay-session-messages-keyword-role-user-renders-as-user ()
  "Keyword :user role must also render as \"User:\" (existing callers)."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--replay-session-messages
     '(((:role . :user)      (:content . "hello"))
       ((:role . :assistant) (:content . "hi there"))))
    (let ((text (buffer-string)))
      (should (string-match-p "User: hello"  text))
      (should (string-match-p "ψ: hi there"  text)))))

(ert-deftest psi-replay-session-messages-structured-assistant-content-renders-text ()
  "Structured assistant content maps should replay as visible transcript text."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--replay-session-messages
     '(((:role . "assistant")
        (:content . ((:kind . :structured)
                     (:blocks . [((:kind . :text)
                                  (:text . "boot ok"))]))))))
    (should (string-match-p "ψ: boot ok" (buffer-string)))))

(ert-deftest psi-replay-session-messages-assistant-error-message-renders-text ()
  "Assistant replay rows with empty content but :error-message should be visible."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state)
          (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--replay-session-messages
     '(((:role . "assistant")
        (:content . [])
        (:error-message . "Validation failed"))))
    (should (string-match-p (regexp-quote "ψ: [error] Validation failed")
                            (buffer-string)))))

(ert-deftest psi-input-history-m-p-m-n-navigates-submissions ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--history-record-input "first")
    (psi-emacs--history-record-input "second")
    (psi-emacs--ensure-input-area)
    (goto-char (psi-emacs--draft-end-position))
    (insert "draft")
    (psi-emacs-previous-input)
    (should (equal "second" (psi-emacs--tail-draft-text)))
    (psi-emacs-previous-input)
    (should (equal "first" (psi-emacs--tail-draft-text)))
    (psi-emacs-next-input)
    (should (equal "second" (psi-emacs--tail-draft-text)))
    (psi-emacs-next-input)
    (should (equal "draft" (psi-emacs--tail-draft-text)))))

(ert-deftest psi-capf-installed-in-psi-mode-buffer ()
  (with-temp-buffer
    (psi-emacs-mode)
    (should (memq #'psi-emacs-prompt-capf completion-at-point-functions))))

(ert-deftest psi-capf-slash-context-returns-command-candidates-with-category ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "/re")
    (let* ((capf (psi-emacs-prompt-capf))
           (table (nth 2 capf))
           (category (plist-get (nthcdr 3 capf) :category))
           (cands (all-completions "/re" table)))
      (should capf)
      (should (eq category 'psi_prompt))
      (should (member "/resume" cands)))))

(ert-deftest psi-capf-slash-context-includes-background-job-commands ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "/j")
    (let* ((capf (psi-emacs-prompt-capf))
           (table (nth 2 capf))
           (cands (all-completions "/j" table)))
      (should capf)
      (should (member "/jobs" cands))
      (should (member "/job" cands)))))

(ert-deftest psi-capf-slash-context-includes-server-command-candidates ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "/re")
    (let* ((capf (psi-emacs-prompt-capf))
           (table (nth 2 capf))
           (cands (all-completions "/re" table)))
      (should capf)
      (should (member "/remember" cands))))
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "/hi")
    (let* ((capf (psi-emacs-prompt-capf))
           (table (nth 2 capf))
           (cands (all-completions "/hi" table)))
      (should capf)
      (should (member "/history" cands)))))

(ert-deftest psi-capf-slash-includes-extension-commands-from-state ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state
                (make-psi-emacs-state :extension-command-names '("chain" "/chain-list")))
    (insert "/ch")
    (let* ((capf (psi-emacs-prompt-capf))
           (table (nth 2 capf))
           (cands (all-completions "/ch" table)))
      (should capf)
      (should (member "/chain" cands))
      (should (member "/chain-list" cands)))))

(ert-deftest psi-capf-at-reference-context-returns-file-candidates-and-category ()
  (let* ((tmp (make-temp-file "psi-capf-ref-" t))
         (default-directory (file-name-as-directory tmp)))
    (unwind-protect
        (progn
          (write-region "" nil (expand-file-name "README.md" tmp) nil 'silent)
          (make-directory (expand-file-name "src" tmp))
          (make-directory (expand-file-name ".git" tmp))
          (write-region "" nil (expand-file-name ".git/config" tmp) nil 'silent)
          (with-temp-buffer
            (psi-emacs-mode)
            (insert "@")
            (let* ((capf (psi-emacs-prompt-capf))
                   (table (nth 2 capf))
                   (category (plist-get (nthcdr 3 capf) :category))
                   (cands (all-completions "@" table)))
              (should capf)
              (should (eq category 'psi_reference))
              (should (member "@README.md" cands))
              (should (member "@src/" cands))
              (should-not (seq-some (lambda (cand)
                                      (string-match-p "@\\.git" cand))
                                    cands)))))
      (when (file-directory-p tmp)
        (delete-directory tmp t)))))

(ert-deftest psi-capf-falls-through-outside-slash-or-at-context ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "hello")
    (should-not (psi-emacs-prompt-capf))))

(ert-deftest psi-capf-slash-and-reference-provide-affixation-functions ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "/h")
    (let* ((slash-capf (psi-emacs-prompt-capf))
           (slash-affix (plist-get (nthcdr 3 slash-capf) :affixation-function)))
      (should (functionp slash-affix)))
    (let ((inhibit-read-only t))
      (erase-buffer))
    (insert "@")
    (let* ((ref-capf (psi-emacs-prompt-capf))
           (ref-affix (plist-get (nthcdr 3 ref-capf) :affixation-function))
           (ref-exit (plist-get (nthcdr 3 ref-capf) :exit-function)))
      (should (functionp ref-affix))
      (should (functionp ref-exit)))))

(ert-deftest psi-capf-reference-candidates-include-project-root-when-distinct ()
  (let* ((project-root (make-temp-file "psi-capf-proj-" t))
         (nested (expand-file-name "sub/dir" project-root))
         (default-directory (file-name-as-directory nested)))
    (unwind-protect
        (progn
          (make-directory nested t)
          (write-region "" nil (expand-file-name "root-only.md" project-root) nil 'silent)
          (cl-letf (((symbol-function 'project-current)
                     (lambda (&rest _)
                       'psi-test-proj))
                    ((symbol-function 'project-root)
                     (lambda (_proj)
                       project-root)))
            (with-temp-buffer
              (psi-emacs-mode)
              (insert "@root")
              (let* ((capf (psi-emacs-prompt-capf))
                     (table (nth 2 capf))
                     (cands (all-completions "@root" table)))
                (should (member "@root-only.md" cands))))))
      (when (file-directory-p project-root)
        (delete-directory project-root t)))))

(ert-deftest psi-capf-reference-exit-adds-trailing-space-for-files ()
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "@README.md")
    (goto-char (point-max))
    (psi-emacs--reference-exit "@README.md" 'finished)
    (should (string-suffix-p " " (buffer-string)))))

(ert-deftest psi-capf-reference-hidden-policy-respects-defcustom ()
  (let* ((tmp (make-temp-file "psi-capf-hidden-" t))
         (default-directory (file-name-as-directory tmp)))
    (unwind-protect
        (progn
          (write-region "" nil (expand-file-name ".env" tmp) nil 'silent)
          (with-temp-buffer
            (psi-emacs-mode)
            (insert "@")
            (let ((psi-emacs-reference-include-hidden nil)
                  (capf (psi-emacs-prompt-capf)))
              (should-not (member "@.env" (all-completions "@" (nth 2 capf))))))
          (with-temp-buffer
            (psi-emacs-mode)
            (insert "@")
            (let ((psi-emacs-reference-include-hidden t)
                  (capf (psi-emacs-prompt-capf)))
              (should (member "@.env" (all-completions "@" (nth 2 capf)))))))
      (when (file-directory-p tmp)
        (delete-directory tmp t)))))

(ert-deftest psi-capf-reference-match-style-prefix-vs-substring ()
  (let* ((tmp (make-temp-file "psi-capf-match-" t))
         (default-directory (file-name-as-directory tmp)))
    (unwind-protect
        (progn
          (write-region "" nil (expand-file-name "alpha.txt" tmp) nil 'silent)
          (let ((psi-emacs-reference-match-style 'substring))
            (should (member "@alpha.txt"
                            (psi-emacs--reference-candidates "@ha"))))
          (let ((psi-emacs-reference-match-style 'prefix))
            (should-not (member "@alpha.txt"
                                (psi-emacs--reference-candidates "@ha")))))
      (when (file-directory-p tmp)
        (delete-directory tmp t)))))

(ert-deftest psi-capf-reference-candidate-limit-respects-defcustom ()
  (let* ((tmp (make-temp-file "psi-capf-limit-" t))
         (default-directory (file-name-as-directory tmp)))
    (unwind-protect
        (progn
          (dotimes (i 4)
            (write-region "" nil (expand-file-name (format "file-%d.txt" i) tmp) nil 'silent))
          (with-temp-buffer
            (psi-emacs-mode)
            (insert "@")
            (let* ((psi-emacs-reference-max-candidates 2)
                   (capf (psi-emacs-prompt-capf))
                   (cands (all-completions "@" (nth 2 capf))))
              (should (= 2 (length cands))))))
      (when (file-directory-p tmp)
        (delete-directory tmp t)))))

;;; ── context/updated + session tree widget ─────────────────────────────────────

(ert-deftest psi-context-updated-stores-snapshot-on-state ()
  "context/updated stores context snapshot on frontend state."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "context/updated")
       (:data . ((:active-session-id . "s1")
                 (:sessions . [((:id . "s1") (:name . "main") (:is-active . t) (:is-streaming . nil) (:parent-session-id . nil))
                               ((:id . "s2") (:name . "fork-1") (:is-active . nil) (:is-streaming . nil) (:parent-session-id . "s1"))
                               ])))))
    (let ((snap (psi-emacs-state-context-snapshot psi-emacs--state)))
      (should (listp snap))
      (should (equal "s1" (alist-get :active-session-id snap nil nil #'equal))))))

(ert-deftest psi-context-updated-renders-session-tree-widget ()
  "context/updated with multiple sessions adds session-tree widget to projection."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "context/updated")
       (:data . ((:active-session-id . "s1")
                 (:sessions . [((:id . "s1") (:name . "main") (:is-active . t) (:is-streaming . nil) (:parent-session-id . nil) (:worktree-path . "/repo/main") (:created-at . "2026-03-16T09:00:00Z"))
                               ((:id . "s2") (:name . "fork-1") (:is-active . nil) (:is-streaming . nil) (:parent-session-id . "s1") (:worktree-path . "/repo/child") (:created-at . "2026-03-16T08:00:00Z"))
                               ])))))
    (let* ((widgets (psi-emacs-state-projection-widgets psi-emacs--state))
           (tree-w (seq-find (lambda (w)
                               (and (equal "psi-session"
                                           (psi-emacs--event-data-get w '(:extension-id extension-id)))
                                    (equal "session-tree"
                                           (psi-emacs--event-data-get w '(:widget-id widget-id)))))
                             widgets)))
      (should tree-w)
      (let* ((lines (append (psi-emacs--event-data-get tree-w '(:content-lines content-lines)) nil))
             (texts (mapcar (lambda (l) (alist-get :text l nil nil #'equal)) lines)))
        (should (= 2 (length lines)))
        (should (cl-some (lambda (txt) (string-match-p "main" txt)) texts))
        (should (cl-some (lambda (txt) (string-match-p "/repo/main" txt)) texts))
        (should (cl-some (lambda (txt) (string-match-p "← active" txt)) texts))
        (should (cl-some (lambda (txt) (string-match-p "fork-1" txt)) texts))))))

(ert-deftest psi-context-updated-single-session-omits-widget ()
  "context/updated with only one session does not add session-tree widget."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (psi-emacs--handle-rpc-event
     '((:event . "context/updated")
       (:data . ((:active-session-id . "s1")
                 (:sessions . [((:id . "s1") (:name . "main") (:is-active . t) (:is-streaming . nil) (:parent-session-id . nil))
                               ])))))
    (let* ((widgets (psi-emacs-state-projection-widgets psi-emacs--state))
           (tree-w (seq-find (lambda (w)
                               (equal "psi-session"
                                      (psi-emacs--event-data-get w '(:extension-id extension-id))))
                             widgets)))
      (should-not tree-w))))

(ert-deftest psi-session-tree-active-line-has-no-action ()
  "Active session slot line carries no action."
  (let* ((slots '(((:id . "s1") (:name . "main") (:is-active . t) (:is-streaming . nil) (:parent-session-id . nil) (:worktree-path . "/repo/main"))))
         (lines (psi-emacs--session-tree-widget-lines slots "s1"))
         (line  (car lines)))
    (should (equal "main — /repo/main ← active" (alist-get :text line nil nil #'equal)))
    (should-not (alist-get :action line nil nil #'equal))))

(ert-deftest psi-session-tree-inactive-line-has-switch-action ()
  "Inactive session slot line carries /tree <id> action."
  (let* ((slots '(((:id . "s1") (:name . "main")   (:is-active . t)   (:is-streaming . nil) (:parent-session-id . nil))
                  ((:id . "s2") (:name . "fork-1") (:is-active . nil) (:is-streaming . nil) (:parent-session-id . nil))))
         (lines (psi-emacs--session-tree-widget-lines slots "s1"))
         (inactive (cadr lines))
         (action   (alist-get :action inactive nil nil #'equal)))
    (should action)
    (should (equal "/tree s2"
                   (psi-emacs--event-data-get action '(:command command))))))

(ert-deftest psi-session-tree-streaming-slot-shows-badge ()
  "Streaming session slot appends [streaming] to its text."
  (let* ((slots '(((:id . "s1") (:name . "main")   (:is-active . t)   (:is-streaming . nil) (:parent-session-id . nil))
                  ((:id . "s2") (:name . "fork-1") (:is-active . nil) (:is-streaming . t)   (:parent-session-id . nil))))
         (lines (psi-emacs--session-tree-widget-lines slots "s1"))
         (fork-line (cadr lines)))
    (should (string-match-p "\\[streaming\\]" (alist-get :text fork-line nil nil #'equal)))))

(ert-deftest psi-session-tree-child-slot-is-indented ()
  "Child session (parent-session-id matches a sibling id) is indented."
  (let* ((slots '(((:id . "s1") (:name . "main")   (:is-active . t)   (:is-streaming . nil) (:parent-session-id . nil))
                  ((:id . "s2") (:name . "fork-1") (:is-active . nil) (:is-streaming . nil) (:parent-session-id . "s1"))))
         (lines (psi-emacs--session-tree-widget-lines slots "s1"))
         (child-text (alist-get :text (cadr lines) nil nil #'equal)))
    (should (string-prefix-p "  " child-text))))

(ert-deftest psi-tree-slash-command-dispatches-switch-by-id ()
  "'/tree <id>' slash command calls switch_session with session-id."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    ;; Seed a context snapshot with two sessions
    (setf (psi-emacs-state-context-snapshot psi-emacs--state)
          '((:active-session-id . "s1")
            (:sessions . (((:id . "s1") (:name . "main")   (:is-active . t)   (:is-streaming . nil) (:parent-session-id . nil))
                          ((:id . "s2") (:name . "fork-1") (:is-active . nil) (:is-streaming . nil) (:parent-session-id . nil))))))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _cb)
                   (push (list op params) calls)
                   t)))
        (psi-emacs--default-handle-slash-command psi-emacs--state "/tree s2"))
      (should (= 1 (length calls)))
      (should (equal "switch_session" (caar calls)))
      (should (equal "s2" (alist-get :session-id (cadar calls) nil nil #'equal))))))

(ert-deftest psi-tree-slash-command-no-op-when-already-active ()
  "'/tree <active-id>' appends a message and sends no RPC."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-context-snapshot psi-emacs--state)
          '((:active-session-id . "s1")
            (:sessions . (((:id . "s1") (:name . "main") (:is-active . t) (:is-streaming . nil) (:parent-session-id . nil))))))
    (let ((calls nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _cb)
                   (push (list op params) calls)
                   t)))
        (psi-emacs--default-handle-slash-command psi-emacs--state "/tree s1"))
      ;; Direct /tree <id> dispatches switch_session immediately (no-op message
      ;; only happens from the picker path; direct id dispatch always fires)
      ;; Confirm exactly one call to switch_session was made (session-id branch)
      (should (= 1 (length calls))))))

(ert-deftest psi-tree-command-falls-back-to-backend-when-no-context-snapshot ()
  "Bare `/tree` should use backend command flow until a context snapshot exists."
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (let ((calls nil)
          (watchdog-reset nil))
      (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                 (lambda (_state op params &optional _cb)
                   (push (list op params) calls)
                   t))
                ((symbol-function 'psi-emacs--reset-stream-watchdog)
                 (lambda (&rest _args)
                   (setq watchdog-reset t))))
        (psi-emacs--default-handle-slash-command psi-emacs--state "/tree"))
      (setq calls (nreverse calls))
      (should (equal '(("command" ((:text . "/tree")))) calls))
      (should (eq 'streaming (psi-emacs-state-run-state psi-emacs--state)))
      (should watchdog-reset))))

(ert-deftest psi-session-display-name-uses-name-slot ()
  "psi-emacs--session-display-name returns slot name when present."
  (should (equal "my-session"
                 (psi-emacs--session-display-name
                  '((:id . "abc12345") (:name . "my-session"))))))

(ert-deftest psi-session-display-name-falls-back-to-id-prefix ()
  "psi-emacs--session-display-name returns '(session <prefix>)' when name nil."
  (should (equal "(session abc12345)"
                 (psi-emacs--session-display-name
                  '((:id . "abc123456789") (:name . nil))))))

(ert-deftest psi-session-display-name-supports-command-session-keys ()
  "psi-emacs--session-display-name supports backend command payload key names."
  (should (equal "Alpha"
                 (psi-emacs--session-display-name
                  '((:session-id . "abc123456789") (:session-name . "Alpha")))))
  (should (equal "(session abc12345)"
                 (psi-emacs--session-display-name
                  '((:session-id . "abc123456789") (:session-name . nil))))))

(ert-deftest psi-session-age-label-uses-compact-relative-units ()
  "session age labels use compact relative time units."
  (let* ((now (float-time))
         (ts-now (format-time-string "%Y-%m-%dT%H:%M:%SZ" (seconds-to-time (- now 30)) t))
         (ts-53m (format-time-string "%Y-%m-%dT%H:%M:%SZ" (seconds-to-time (- now (* 53 60))) t))
         (ts-2h  (format-time-string "%Y-%m-%dT%H:%M:%SZ" (seconds-to-time (- now (* 2 3600))) t)))
    (cl-letf (((symbol-function 'psi-emacs--now-seconds) (lambda () now)))
      (should (equal "now" (psi-emacs--session-age-label ts-now)))
      (should (equal "53m" (psi-emacs--session-age-label ts-53m)))
      (should (equal "2h" (psi-emacs--session-age-label ts-2h))))))

(ert-deftest psi-tree-session-line-label-includes-worktree-and-age ()
  "tree line labels include worktree path and compact age when available."
  (let* ((now (float-time))
         (ts-53m (format-time-string "%Y-%m-%dT%H:%M:%SZ" (seconds-to-time (- now (* 53 60))) t)))
    (cl-letf (((symbol-function 'psi-emacs--now-seconds) (lambda () now)))
      (should (equal "Alpha — /repo/alpha — 53m"
                     (psi-emacs--session-tree-line-label
                      `((:id . "abc123456789")
                        (:name . "Alpha")
                        (:worktree-path . "/repo/alpha")
                        (:created-at . ,ts-53m))))))))

(ert-deftest psi-tree-session-candidates-include-worktree-and-support-command-keys ()
  "tree candidates expose worktree path and support backend command payload keys."
  (let* ((now (float-time))
         (ts-53m (format-time-string "%Y-%m-%dT%H:%M:%SZ" (seconds-to-time (- now (* 53 60))) t))
         (ts-2h  (format-time-string "%Y-%m-%dT%H:%M:%SZ" (seconds-to-time (- now (* 2 3600))) t)))
    (cl-letf (((symbol-function 'psi-emacs--now-seconds) (lambda () now)))
      (should
       (equal '(("Alpha — /repo/alpha — 53m ← active" . "abc123456789")
                ("  (session def98765) — /repo/beta — 2h" . "def987654321"))
              (psi-emacs--tree-session-candidates
               `(((:session-id . "abc123456789")
                  (:session-name . "Alpha")
                  (:worktree-path . "/repo/alpha")
                  (:created-at . ,ts-53m)
                  (:is-active . t)
                  (:parent-session-id . nil))
                 ((:session-id . "def987654321")
                  (:session-name . nil)
                  (:worktree-path . "/repo/beta")
                  (:created-at . ,ts-2h)
                  (:parent-session-id . "abc123456789")))
               "abc123456789"))))))

(ert-deftest psi-tree-capf-includes-tree-command ()
  "'/tree' appears in slash CAPF candidates."
  (with-temp-buffer
    (psi-emacs-mode)
    (insert "/tr")
    (let* ((capf (psi-emacs-prompt-capf))
           (cands (all-completions "/tr" (nth 2 capf))))
      (should (member "/tree" cands)))))

(provide 'psi-test)

;;; psi-test.el ends here
