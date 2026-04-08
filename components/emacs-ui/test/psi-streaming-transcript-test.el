;;; psi-streaming-transcript-test.el --- Streaming transcript tests for psi Emacs frontend  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-test-support)
(require 'psi)
(require 'psi-rpc)

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
                 (:effective-reasoning-effort . "high")
                 (:header-model-label . "(openai) gpt-5.3-codex • thinking high")
                 (:status-session-line . "session: sess-1 phase:idle streaming:yes compacting:no pending:2 retry:1")))))
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
                 (:effective-reasoning-effort . "medium")
                 (:header-model-label . "(anthropic) claude-sonnet • thinking medium")
                 (:status-session-line . "session: sess-2 phase:idle streaming:no compacting:no pending:0 retry:0")))))
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
                 (:effective-reasoning-effort . "high")
                 (:header-model-label . "(openai) gpt-5 • thinking high")
                 (:status-session-line . "session: sess-3 phase:streaming streaming:yes compacting:no pending:1 retry:0")))))
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

(ert-deftest psi-input-history-navigation-works-with-projection-present ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--history-record-input "first")
    (psi-emacs--history-record-input "second")
    (psi-emacs--ensure-input-area)
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:stats-line . "latency 12ms")))))
    (psi-emacs--replace-input-text "draft")
    (goto-char (point-max))
    (psi-emacs-previous-input)
    (should (equal "second" (psi-emacs--tail-draft-text)))
    (psi-emacs-previous-input)
    (should (equal "first" (psi-emacs--tail-draft-text)))
    (psi-emacs-next-input)
    (should (equal "second" (psi-emacs--tail-draft-text)))
    (psi-emacs-next-input)
    (should (equal "draft" (psi-emacs--tail-draft-text)))))

(ert-deftest psi-input-history-navigation-survives-guarded-before-change-hooks ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--history-record-input "first")
    (psi-emacs--history-record-input "second")
    (psi-emacs--ensure-input-area)
    (psi-emacs--replace-input-text "draft")
    (add-hook 'before-change-functions
              (lambda (_beg _end)
                (unless psi-emacs--allow-protected-input-edit
                  (signal 'text-read-only nil)))
              nil
              t)
    (psi-emacs-previous-input)
    (should (equal "second" (psi-emacs--tail-draft-text)))))

(ert-deftest psi-input-area-scrubs-inherited-read-only-properties ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (let* ((range (psi-emacs--input-range))
           (start (car range)))
      (let ((inhibit-read-only t))
        (insert (propertize "draft" 'read-only t)))
      (should (get-text-property start 'read-only))
      (psi-emacs--clear-read-only-props-in-input-range)
      (should-not (get-text-property start 'read-only)))))

(ert-deftest psi-projection-boundary-does-not-make-input-read-only ()
  (with-temp-buffer
    (psi-emacs-mode)
    (setq-local psi-emacs--state (psi-emacs--initialize-state nil))
    (setf (psi-emacs-state-draft-anchor psi-emacs--state) (copy-marker (point-max) nil))
    (psi-emacs--ensure-input-area)
    (psi-emacs--handle-rpc-event
     '((:event . "footer/updated")
       (:data . ((:path-line . "~/psi-main")
                 (:stats-line . "latency 12ms")))))
    (let* ((range (psi-emacs--input-range))
           (start (car range)))
      (should-not (get-text-property start 'read-only))
      (should-not (get-text-property start 'front-sticky))
      (let ((inhibit-read-only t))
        (goto-char start)
        (insert "draft"))
      (should (equal "draft" (psi-emacs--tail-draft-text))))))


(provide 'psi-streaming-transcript-test)

;;; psi-streaming-transcript-test.el ends here
