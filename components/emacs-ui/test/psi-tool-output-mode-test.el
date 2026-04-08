;;; psi-tool-output-mode-test.el --- Tool output mode tests for psi Emacs frontend  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-test-support)
(require 'psi)

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


(provide 'psi-tool-output-mode-test)

;;; psi-tool-output-mode-test.el ends here
