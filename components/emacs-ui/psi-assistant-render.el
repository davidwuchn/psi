;;; psi-assistant-render.el --- Assistant streaming render helpers for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted assistant stream/finalize helpers used by psi.el.

;;; Code:

(require 'subr-x)
(require 'psi-globals)

(defconst psi-emacs--assistant-line-prefix "ψ: "
  "Prefix rendered for assistant transcript lines.")

(defconst psi-emacs--thinking-line-prefix "ψ⋯ "
  "Prefix rendered for assistant thinking transcript lines.")

(defconst psi-emacs--assistant-stream-verbatim-property 'psi-emacs-stream-verbatim
  "Text property used to mark in-progress assistant content as verbatim.")

(defun psi-emacs--render-assistant-line (text)
  "Render assistant TEXT in canonical line format."
  (concat psi-emacs--assistant-line-prefix (or text "") "\n"))

(defun psi-emacs--render-thinking-line (text)
  "Render assistant thinking TEXT in canonical line format."
  (concat psi-emacs--thinking-line-prefix (or text "") "\n"))

(defun psi-emacs--apply-prefix-overlay (line-start prefix face)
  "Apply high-priority FACE overlay for PREFIX at LINE-START."
  (let ((line-end (line-end-position))
        (prefix-end (+ line-start (length prefix))))
    (when (<= prefix-end line-end)
      (remove-overlays line-start prefix-end 'category 'psi-emacs-prefix)
      (let ((ov (make-overlay line-start prefix-end nil nil t)))
        (overlay-put ov 'category 'psi-emacs-prefix)
        (overlay-put ov 'face face)
        (overlay-put ov 'priority 1000)
        (overlay-put ov 'evaporate t)))))

(defun psi-emacs--assistant-range-live-p (range)
  "Return non-nil when assistant RANGE markers are live in a buffer."
  (and (consp range)
       (markerp (car range))
       (markerp (cdr range))
       (marker-buffer (car range))
       (marker-buffer (cdr range))))

(defun psi-emacs--assistant-content-range (range)
  "Return assistant content (not prefix/newline) region for RANGE."
  (when (psi-emacs--assistant-range-live-p range)
    (let* ((start (marker-position (car range)))
           (end (marker-position (cdr range)))
           (content-start (+ start (length psi-emacs--assistant-line-prefix)))
           (content-end (if (and (> end content-start)
                                 (eq (char-before end) ?\n))
                            (1- end)
                          end)))
      (cons content-start (max content-start content-end)))))

(defun psi-emacs--set-assistant-stream-verbatim (range)
  "Apply verbatim display properties to in-progress assistant RANGE."
  (when-let ((content-range (psi-emacs--assistant-content-range range)))
    (let ((start (car content-range))
          (end (cdr content-range)))
      (when (< start end)
        (add-text-properties
         start
         end
         (list 'face 'default
               'font-lock-face nil
               psi-emacs--assistant-stream-verbatim-property t))))))

(defun psi-emacs--clear-assistant-stream-verbatim (range)
  "Remove verbatim display properties from assistant RANGE."
  (when-let ((content-range (psi-emacs--assistant-content-range range)))
    (let ((start (car content-range))
          (end (cdr content-range)))
      (when (< start end)
        (remove-list-of-text-properties
         start
         end
         (list 'face
               'font-lock-face
               psi-emacs--assistant-stream-verbatim-property))))))

(defun psi-emacs--apply-finalized-assistant-markdown (start end)
  "Apply markdown/font-lock processing to finalized assistant text START..END."
  (when (< start end)
    (condition-case _
        (cond
         ((fboundp 'markdown-fontify-region)
          (markdown-fontify-region start end))
         ((fboundp 'font-lock-flush)
          (font-lock-flush start end)
          (when (fboundp 'font-lock-ensure)
            (font-lock-ensure start end))))
      (error nil))))

(defun psi-emacs--process-finalized-assistant-range (range)
  "Finalize assistant RANGE rendering.

Clears stream-time verbatim properties, then applies markdown processing."
  (when (psi-emacs--assistant-range-live-p range)
    (psi-emacs--clear-assistant-stream-verbatim range)
    (when-let ((content-range (psi-emacs--assistant-content-range range)))
      (psi-emacs--apply-finalized-assistant-markdown (car content-range)
                                                     (cdr content-range)))))

(defun psi-emacs--set-assistant-line (text &optional stream-verbatim)
  "Create or update the single assistant line with TEXT.

When STREAM-VERBATIM is non-nil, keep the assistant content visually verbatim
while streaming; markdown processing is deferred until finalization."
  (when psi-emacs--state
    (let ((follow-anchor (psi-emacs--draft-anchor-at-end-p))
          (range (psi-emacs-state-assistant-range psi-emacs--state)))
      (if (psi-emacs--assistant-range-live-p range)
          (save-excursion
            (let ((start (car range))
                  (end (cdr range)))
              (goto-char start)
              (delete-region start end)
              (insert (psi-emacs--render-assistant-line text))
              (set-marker end (point))))
        (save-excursion
          (psi-emacs--ensure-newline-before-append)
          (let ((start (copy-marker (point) nil))
                (end (copy-marker (point) t)))
            (insert (psi-emacs--render-assistant-line text))
            (set-marker end (point))
            (setf (psi-emacs-state-assistant-range psi-emacs--state)
                  (cons start end)))))
      (let ((updated-range (psi-emacs-state-assistant-range psi-emacs--state)))
        (when (psi-emacs--assistant-range-live-p updated-range)
          (save-excursion
            (goto-char (marker-position (car updated-range)))
            (psi-emacs--apply-prefix-overlay
             (line-beginning-position)
             psi-emacs--assistant-line-prefix
             'psi-emacs-assistant-reply-face)))
        (if stream-verbatim
            (psi-emacs--set-assistant-stream-verbatim updated-range)
          (psi-emacs--clear-assistant-stream-verbatim updated-range)))
      (when follow-anchor
        (psi-emacs--set-draft-anchor-to-end)))))

(defun psi-emacs--set-thinking-line (text)
  "Create or update the single assistant thinking line with TEXT."
  (when psi-emacs--state
    (let ((follow-anchor (psi-emacs--draft-anchor-at-end-p))
          (range (psi-emacs-state-thinking-range psi-emacs--state)))
      (if (psi-emacs--assistant-range-live-p range)
          (save-excursion
            (let ((start (car range))
                  (end (cdr range)))
              (goto-char start)
              (delete-region start end)
              (insert (psi-emacs--render-thinking-line text))
              (set-marker end (point))))
        (save-excursion
          (psi-emacs--ensure-newline-before-append)
          (let ((start (copy-marker (point) nil))
                (end (copy-marker (point) nil)))
            (insert (psi-emacs--render-thinking-line text))
            (set-marker end (point))
            (setf (psi-emacs-state-thinking-range psi-emacs--state)
                  (cons start end)))))
      (let ((updated-range (psi-emacs-state-thinking-range psi-emacs--state)))
        (when (psi-emacs--assistant-range-live-p updated-range)
          (save-excursion
            (goto-char (marker-position (car updated-range)))
            (psi-emacs--apply-prefix-overlay
             (line-beginning-position)
             psi-emacs--thinking-line-prefix
             'psi-emacs-assistant-thinking-face))))
      (when follow-anchor
        (psi-emacs--set-draft-anchor-to-end)))))

(defun psi-emacs--archive-thinking-line ()
  "Freeze current thinking line as transcript history and clear in-progress state."
  (when psi-emacs--state
    (let ((range (psi-emacs-state-thinking-range psi-emacs--state)))
      (when (psi-emacs--assistant-range-live-p range)
        (let ((archived (cons (copy-marker (marker-position (car range)) nil)
                              (copy-marker (marker-position (cdr range)) nil))))
          (setf (psi-emacs-state-thinking-archived-ranges psi-emacs--state)
                (append (psi-emacs-state-thinking-archived-ranges psi-emacs--state)
                        (list archived)))))
      (when (and (consp range) (markerp (car range)))
        (set-marker (car range) nil))
      (when (and (consp range) (markerp (cdr range)))
        (set-marker (cdr range) nil))
      (setf (psi-emacs-state-thinking-range psi-emacs--state) nil)
      (setf (psi-emacs-state-thinking-in-progress psi-emacs--state) nil))))

(defun psi-emacs--clear-thinking-line ()
  "Remove any in-progress assistant thinking line and clear thinking state."
  (when psi-emacs--state
    (let ((range (psi-emacs-state-thinking-range psi-emacs--state)))
      (when (psi-emacs--assistant-range-live-p range)
        (save-excursion
          (delete-region (car range) (cdr range))))
      (when (and (consp range) (markerp (car range)))
        (set-marker (car range) nil))
      (when (and (consp range) (markerp (cdr range)))
        (set-marker (cdr range) nil))
      (setf (psi-emacs-state-thinking-range psi-emacs--state) nil)
      (setf (psi-emacs-state-thinking-in-progress psi-emacs--state) nil))))

(defun psi-emacs--common-prefix-length (a b)
  "Return length of common prefix shared by strings A and B."
  (let* ((a* (or a ""))
         (b* (or b ""))
         (limit (min (length a*) (length b*)))
         (idx 0))
    (while (and (< idx limit)
                (eq (aref a* idx) (aref b* idx)))
      (setq idx (1+ idx)))
    idx))

(defun psi-emacs--merge-assistant-stream-text (current incoming)
  "Merge CURRENT in-progress text with INCOMING stream payload.

Supports both payload styles:
- cumulative snapshots (incoming replaces current when it extends current)
- incremental deltas (incoming appends)

Also tolerates snapshot updates that differ near the previous tail
(e.g. trailing newline churn while content grows)."
  (let ((current* (or current ""))
        (incoming* (or incoming "")))
    (cond
     ((string-empty-p incoming*) current*)
     ((string-empty-p current*) incoming*)
     ((string-prefix-p current* incoming*)
      incoming*)
     (t
      (let* ((cur-len (length current*))
             (in-len (length incoming*))
             (cp (psi-emacs--common-prefix-length current* incoming*))
             (tail-close? (and (> in-len cur-len)
                               (>= cp (max 1 (1- cur-len))))))
        (if tail-close?
            incoming*
          (concat current* incoming*)))))))

(defun psi-emacs--assistant-delta (text)
  "Apply assistant delta TEXT to the in-progress assistant block."
  (when psi-emacs--state
    (psi-emacs--set-run-state psi-emacs--state 'streaming)
    (psi-emacs--reset-stream-watchdog psi-emacs--state)
    (let ((next (psi-emacs--merge-assistant-stream-text
                 (psi-emacs-state-assistant-in-progress psi-emacs--state)
                 text)))
      (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) next)
      (psi-emacs--set-assistant-line next t))))

(defun psi-emacs--assistant-thinking-delta (text)
  "Apply assistant thinking delta TEXT to the in-progress thinking block.

Thinking deltas are always incremental (never cumulative snapshots),
so we append directly without the snapshot-merge heuristic."
  (when psi-emacs--state
    (psi-emacs--set-run-state psi-emacs--state 'streaming)
    (psi-emacs--reset-stream-watchdog psi-emacs--state)
    (let ((next (concat (or (psi-emacs-state-thinking-in-progress psi-emacs--state) "")
                        (or text ""))))
      (setf (psi-emacs-state-thinking-in-progress psi-emacs--state) next)
      (psi-emacs--set-thinking-line next))))

(defun psi-emacs--assistant-before-tool-event ()
  "Prepare transcript before rendering a tool lifecycle event.

When a thinking line is in progress, archive it so subsequent thinking
deltas render as a fresh block after the tool output."
  (when (and psi-emacs--state
             (psi-emacs--assistant-range-live-p
              (psi-emacs-state-thinking-range psi-emacs--state)))
    (psi-emacs--archive-thinking-line)))

(defun psi-emacs--assistant-finalize (text)
  "Finalize assistant block with TEXT and clear in-progress state."
  (when psi-emacs--state
    (let* ((text* (if (and (stringp text) (string-empty-p text)) nil text))
           (final (or text* (psi-emacs-state-assistant-in-progress psi-emacs--state) ""))
           (follow-input (psi-emacs--draft-anchor-at-end-p)))
      (psi-emacs--set-assistant-line final nil)
      (psi-emacs--process-finalized-assistant-range
       (psi-emacs-state-assistant-range psi-emacs--state))
      (setf (psi-emacs-state-assistant-in-progress psi-emacs--state) nil)
      (setf (psi-emacs-state-assistant-range psi-emacs--state) nil)
      (psi-emacs--clear-thinking-line)
      (psi-emacs--disarm-stream-watchdog psi-emacs--state)
      (psi-emacs--set-run-state psi-emacs--state 'idle)
      (if follow-input
          (goto-char (psi-emacs--draft-end-position))
        (goto-char (psi-emacs--transcript-append-position))))))

(defun psi-emacs--assistant-content->text (content)
  "Extract assistant display text from CONTENT blocks."
  (let ((blocks (cond
                 ((vectorp content) (append content nil))
                 ((listp content) content)
                 (t nil))))
    (string-join
     (delq nil
           (mapcar (lambda (block)
                     (when (listp block)
                       (let ((type (psi-emacs--event-data-get block '(:type type))))
                         (cond
                          ((or (eq type :text) (equal type "text"))
                           (psi-emacs--event-data-get block '(:text text :message message)))
                          ((or (eq type :error) (equal type "error"))
                           (when-let ((err (psi-emacs--event-data-get block
                                                                      '(:text text :message message :error-message error-message))))
                             (format "[error] %s" err)))))))
                   blocks))
     "")))

(provide 'psi-assistant-render)

;;; psi-assistant-render.el ends here
