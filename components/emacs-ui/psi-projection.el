;;; psi-projection.el --- Footer/widget/status/notification projection helpers  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted projection and notification lifecycle helpers used by psi.el.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'psi-globals)

(defvar psi-emacs-notification-timeout-seconds)

(defvar psi-emacs--send-request-function)

(defvar psi-emacs--projection-widget-action-keymap
  (let ((map (make-sparse-keymap)))
    (define-key map (kbd "RET") #'psi-emacs--projection-activate-widget-action)
    (define-key map [mouse-1] #'psi-emacs--projection-activate-widget-action)
    map))

(defun psi-emacs--projection-action->command (action)
  "Return slash command string from ACTION map, or nil."
  (let* ((type* (and (listp action)
                     (psi-emacs--event-data-get action '(:type type))))
         (type (cond
                ((keywordp type*) (substring (symbol-name type*) 1))
                ((symbolp type*) (symbol-name type*))
                ((stringp type*) (downcase type*))
                (t nil)))
         (command (and (listp action)
                       (psi-emacs--event-data-get action '(:command command)))))
    (when (and (equal type "command")
               (stringp command)
               (not (string-empty-p (string-trim command))))
      (string-trim command))))

(defun psi-emacs--projection-activate-widget-action (&optional event)
  "Activate widget action at point or mouse EVENT."
  (interactive)
  (let* ((pos (cond
               ((and event (consp event))
                (posn-point (event-end event)))
               (t (point))))
         (command (and (integerp pos)
                       (get-text-property pos 'psi-widget-command))))
    (when (and (stringp command)
               (not (string-empty-p command))
               psi-emacs--state
               (functionp psi-emacs--send-request-function))
      (funcall psi-emacs--send-request-function
               psi-emacs--state
               "prompt"
               `((:message . ,command))))))

(defun psi-emacs--projection-seq (value)
  "Normalize VALUE into a proper list sequence."
  (cond
   ((vectorp value) (append value nil))
   ((listp value) value)
   (t nil)))

(defun psi-emacs--projection-item-key (item keys)
  "Return deterministic sort/display key from ITEM over KEYS."
  (let ((value (and (listp item)
                    (psi-emacs--event-data-get item keys))))
    (if (stringp value)
        value
      (format "%s" (or value "")))))

(defun psi-emacs--projection-item-text (item)
  "Return display text for projection ITEM."
  (let ((value (and (listp item)
                    (psi-emacs--event-data-get item
                                               '(:text text :message message :value value :content content)))))
    (cond
     ((stringp value) value)
     ((null value) "")
     (t (format "%s" value)))))

(defun psi-emacs--projection-widget-content-lines (widget)
  "Return display content lines for projected WIDGET."
  (let* ((content-lines (and (listp widget)
                             (psi-emacs--event-data-get widget '(:content-lines content-lines :contentLines contentLines))))
         (content (and (listp widget)
                       (psi-emacs--event-data-get widget '(:content content))))
         (content-seq (psi-emacs--projection-seq (or content-lines content)))
         (lines (and content-seq
                     (delq nil
                           (mapcar (lambda (line)
                                     (cond
                                      ((stringp line)
                                       (unless (string-empty-p line) line))
                                      ((listp line)
                                       (let ((txt (psi-emacs--event-data-get line '(:text text))))
                                         (when (and (stringp txt)
                                                    (not (string-empty-p txt)))
                                           txt)))
                                      ((null line) nil)
                                      (t (format "%s" line))))
                                   content-seq)))))
    (if content-seq
        (or lines (list ""))
      (list (psi-emacs--projection-item-text widget)))))

(defun psi-emacs--projection-widget-lines (widget)
  "Render projected WIDGET as one or more deterministic content lines.

Widget metadata (placement/extension-id/widget-id) is intentionally omitted
from transcript projection rendering."
  (let* ((content-lines (and (listp widget)
                             (psi-emacs--event-data-get widget '(:content-lines content-lines :contentLines contentLines))))
         (line-seq (psi-emacs--projection-seq content-lines))
         (rendered (and line-seq
                        (delq nil
                              (mapcar (lambda (line)
                                        (cond
                                         ((stringp line)
                                          (unless (string-empty-p line) line))
                                         ((listp line)
                                          (let* ((txt (psi-emacs--event-data-get line '(:text text)))
                                                 (action (psi-emacs--event-data-get line '(:action action)))
                                                 (command (psi-emacs--projection-action->command action)))
                                            (when (and (stringp txt)
                                                       (not (string-empty-p txt)))
                                              (if command
                                                  (propertize txt
                                                              'face 'psi-emacs-projection-widget-face
                                                              'font-lock-face 'psi-emacs-projection-widget-face
                                                              'mouse-face 'highlight
                                                              'help-echo command
                                                              'follow-link t
                                                              'keymap psi-emacs--projection-widget-action-keymap
                                                              'psi-widget-command command)
                                                txt))))
                                         ((null line) nil)
                                         (t (format "%s" line))))
                                      line-seq)))))
    (if line-seq
        (or rendered (list ""))
      (or (psi-emacs--projection-widget-content-lines widget)
          (list "")))))

(defun psi-emacs--projection-sort-widgets (widgets)
  "Return WIDGETS sorted by [placement, extension-id, widget-id]."
  (sort (copy-sequence widgets)
        (lambda (a b)
          (let ((ap (psi-emacs--projection-item-key a '(:placement placement)))
                (bp (psi-emacs--projection-item-key b '(:placement placement)))
                (ae (psi-emacs--projection-item-key a '(:extension-id extension-id :extensionId extensionId)))
                (be (psi-emacs--projection-item-key b '(:extension-id extension-id :extensionId extensionId)))
                (aw (psi-emacs--projection-item-key a '(:widget-id widget-id :widgetId widgetId)))
                (bw (psi-emacs--projection-item-key b '(:widget-id widget-id :widgetId widgetId))))
            (cond
             ((string< ap bp) t)
             ((string< bp ap) nil)
             ((string< ae be) t)
             ((string< be ae) nil)
             (t (string< aw bw)))))))

(defun psi-emacs--projection-sort-statuses (statuses)
  "Return STATUSES sorted by extension-id."
  (sort (copy-sequence statuses)
        (lambda (a b)
          (string< (psi-emacs--projection-item-key a '(:extension-id extension-id :extensionId extensionId))
                   (psi-emacs--projection-item-key b '(:extension-id extension-id :extensionId extensionId))))))

(defun psi-emacs--projection-window-width ()
  "Return render width for footer projection in current buffer.

Prefers `window-text-width' when available (tracks visible text area in
columns). Falls back to body-width minus left/right margins for compatibility.
Returns 80 for non-visible buffers (e.g. tests/background)."
  (if-let ((win (get-buffer-window (current-buffer) t)))
      (let* ((text-width* (and (fboundp 'window-text-width)
                               (window-text-width win)))
             (text-width (if (and (integerp text-width*) (> text-width* 0))
                             text-width*
                           (let* ((body (window-body-width win))
                                  (margins (window-margins win))
                                  (left-margin (or (car margins) 0))
                                  (right-margin (or (cdr margins) 0)))
                             (- body left-margin right-margin)))))
        (max 1 text-width))
    80))

(defun psi-emacs--split-footer-stats-line (line)
  "Split canonical footer stats LINE into [left right] when possible.

RIGHT is recognized as the provider/model segment that starts with
`(provider) ...`. Returns (list left right-or-nil)."
  (let ((s (or line "")))
    (if (string-match "\\`\\(.*\\) \\(([^)]*) .+\\)\\'" s)
        (list (string-trim-right (match-string 1 s))
              (match-string 2 s))
      (list s nil))))

(defun psi-emacs--align-footer-stats-line (line)
  "Right-align provider/model segment in canonical footer stats LINE.

When LINE contains a recognizable `(provider) ...` right segment, align it to
buffer width with at least two spaces between left and right. Otherwise, return
LINE unchanged."
  (pcase-let* ((`(,left ,right) (psi-emacs--split-footer-stats-line line)))
    (if (and (stringp right) (not (string-empty-p right)))
        (let* ((width (psi-emacs--projection-window-width))
               (left-w (string-width left))
               (right-w (string-width right))
               (min-pad 2)
               (total (+ left-w min-pad right-w)))
          (if (<= total width)
              (concat left (make-string (- width left-w right-w) ?\s) right)
            line))
      line)))

(defun psi-emacs--projection-footer-text (data)
  "Extract deterministic footer projection text from event DATA.

Canonical payload lines are rendered as multi-line text in this order:
path-line, stats-line, status-line (blank lines omitted)."
  (let* ((path-line* (psi-emacs--event-data-get data '(:path-line path-line :pathLine pathLine)))
         (stats-line* (psi-emacs--event-data-get data '(:stats-line stats-line :statsLine statsLine)))
         (status-line* (psi-emacs--event-data-get data '(:status-line status-line :statusLine statusLine)))
         (path-line (and (stringp path-line*)
                         (not (string-empty-p path-line*))
                         (propertize path-line*
                                     'face 'psi-emacs-projection-footer-path-face
                                     'font-lock-face 'psi-emacs-projection-footer-path-face)))
         (stats-line (and (stringp stats-line*)
                          (not (string-empty-p stats-line*))
                          (propertize (psi-emacs--align-footer-stats-line stats-line*)
                                      'face 'psi-emacs-projection-footer-stats-face
                                      'font-lock-face 'psi-emacs-projection-footer-stats-face)))
         (status-line (and (stringp status-line*)
                           (not (string-empty-p status-line*))
                           (propertize status-line*
                                       'face 'psi-emacs-projection-footer-status-face
                                       'font-lock-face 'psi-emacs-projection-footer-status-face)))
         (canonical-lines (delq nil (list path-line stats-line status-line))))
    (if canonical-lines
        (string-join canonical-lines "\n")
      (let ((value (psi-emacs--event-data-get data
                                              '(:text text :message message :footer footer :content content))))
        (cond
         ((stringp value) (propertize value
                                      'face 'psi-emacs-projection-footer-face
                                      'font-lock-face 'psi-emacs-projection-footer-face))
         ((null value) nil)
         (t (propertize (format "%s" value)
                        'face 'psi-emacs-projection-footer-face
                        'font-lock-face 'psi-emacs-projection-footer-face)))))))

(defun psi-emacs--cancel-notification-timer (state notification-id)
  "Cancel notification timer for NOTIFICATION-ID in STATE, if present."
  (when (and state notification-id)
    (let* ((timers (psi-emacs-state-projection-notification-timers state))
           (timer (and (hash-table-p timers)
                       (gethash notification-id timers))))
      (when (timerp timer)
        (cancel-timer timer))
      (when (hash-table-p timers)
        (remhash notification-id timers)))))

(defun psi-emacs--clear-notification-lifecycle (state)
  "Clear projected notification state and cancel all lifecycle timers in STATE."
  (when state
    (let ((timers (psi-emacs-state-projection-notification-timers state)))
      (when (hash-table-p timers)
        (maphash (lambda (_id timer)
                   (when (timerp timer)
                     (cancel-timer timer)))
                 timers)
        (clrhash timers)))
    (setf (psi-emacs-state-projection-notifications state) nil)
    (setf (psi-emacs-state-projection-notification-seq state) 0)))

(defun psi-emacs--projection-notification-line (notification)
  "Render deterministic line for projected NOTIFICATION item."
  (format "- [%s] %s"
          (or (plist-get notification :extension-id) "")
          (or (plist-get notification :text) "")))

(defun psi-emacs--notification-remove-by-id (state notification-id)
  "Remove projected notification by NOTIFICATION-ID from STATE and re-render."
  (when (and state notification-id)
    (let* ((notifications (or (psi-emacs-state-projection-notifications state) '()))
           (next (cl-remove-if (lambda (item)
                                 (equal (plist-get item :id) notification-id))
                               notifications)))
      (psi-emacs--cancel-notification-timer state notification-id)
      (setf (psi-emacs-state-projection-notifications state) next)
      (when (eq state psi-emacs--state)
        (psi-emacs--upsert-projection-block)))))

(defun psi-emacs--schedule-notification-dismiss (state notification-id)
  "Schedule auto-dismiss timer for NOTIFICATION-ID in STATE."
  (when (and state notification-id)
    (psi-emacs--cancel-notification-timer state notification-id)
    (let ((timer (run-at-time psi-emacs-notification-timeout-seconds nil
                              (lambda (buffer st id)
                                (when (and (buffer-live-p buffer) st)
                                  (with-current-buffer buffer
                                    (psi-emacs--notification-remove-by-id st id))))
                              (current-buffer)
                              state
                              notification-id)))
      (puthash notification-id timer
               (psi-emacs-state-projection-notification-timers state)))))

(defun psi-emacs--handle-notification-event (data)
  "Apply `ui/notification` DATA to local projection lifecycle state."
  (when psi-emacs--state
    (let* ((seq (1+ (or (psi-emacs-state-projection-notification-seq psi-emacs--state) 0)))
           (extension-id (psi-emacs--projection-item-key data '(:extension-id extension-id :extensionId extensionId)))
           (text (psi-emacs--projection-item-text data))
           (notification-id (format "n-%s" seq))
           (entry (list :id notification-id :seq seq :extension-id extension-id :text text))
           (existing (or (psi-emacs-state-projection-notifications psi-emacs--state) '()))
           (next (append existing (list entry))))
      (setf (psi-emacs-state-projection-notification-seq psi-emacs--state) seq)
      ;; enforce max visible 3, keeping oldest->newest order
      (while (> (length next) 3)
        (let ((drop (car next)))
          (setq next (cdr next))
          (psi-emacs--cancel-notification-timer psi-emacs--state (plist-get drop :id))))
      (setf (psi-emacs-state-projection-notifications psi-emacs--state) next)
      (psi-emacs--schedule-notification-dismiss psi-emacs--state notification-id)
      (psi-emacs--upsert-projection-block))))

(defun psi-emacs--projection-render-block (state)
  "Render deterministic projection block from STATE."
  (let* ((widgets (or (psi-emacs-state-projection-widgets state) '()))
         (statuses (or (psi-emacs-state-projection-statuses state) '()))
         (notifications (or (psi-emacs-state-projection-notifications state) '()))
         (footer (psi-emacs-state-projection-footer state))
         (separator-raw (make-string (psi-emacs--projection-window-width) ?─))
         (separator (propertize separator-raw
                                'face 'psi-emacs-projection-separator-face
                                'font-lock-face 'psi-emacs-projection-separator-face))
         (lines nil))
    (when widgets
      (dolist (widget widgets)
        (dolist (widget-line (psi-emacs--projection-widget-lines widget))
          (push (propertize widget-line
                            'face 'psi-emacs-projection-widget-face
                            'font-lock-face 'psi-emacs-projection-widget-face)
                lines)))
      ;; Keep widget content visually isolated from statuses/footer.
      ;; Only add this divider when there are projected widgets.
      (push separator lines))
    (when statuses
      (push (propertize "Extension Statuses:"
                        'face 'psi-emacs-projection-heading-face
                        'font-lock-face 'psi-emacs-projection-heading-face)
            lines)
      (dolist (status statuses)
        (let ((extension-id (psi-emacs--projection-item-key status '(:extension-id extension-id :extensionId extensionId)))
              (text (psi-emacs--projection-item-text status)))
          (push (concat "- ["
                        (propertize extension-id
                                    'face 'psi-emacs-projection-extension-id-face
                                    'font-lock-face 'psi-emacs-projection-extension-id-face)
                        "] "
                        text)
                lines))))
    (when notifications
      (push (propertize "Extension Notifications:"
                        'face 'psi-emacs-projection-heading-face
                        'font-lock-face 'psi-emacs-projection-heading-face)
            lines)
      (dolist (notification notifications)
        (let ((extension-id (or (plist-get notification :extension-id) ""))
              (text (or (plist-get notification :text) "")))
          (push (concat "- ["
                        (propertize extension-id 'face 'psi-emacs-projection-extension-id-face)
                        "] "
                        text)
                lines))))
    (when (and (stringp footer)
               (not (string-empty-p footer)))
      (push (propertize footer
                        'face 'psi-emacs-projection-footer-face
                        'font-lock-face 'psi-emacs-projection-footer-face)
            lines))
    (if lines
        ;; Blank line before separator keeps visual breathing room between
        ;; transcript input area and projection/footer content.
        (concat "\n"
                separator "\n"
                (string-join (nreverse lines) "\n")
                "\n")
      "")))

(defun psi-emacs--ensure-newline-before-projection-append ()
  "Ensure projection append boundary starts on a fresh line at buffer tail."
  (goto-char (point-max))
  (unless (or (bobp)
              (eq (char-before) ?\n))
    (insert "\n")))

(defun psi-emacs--upsert-projection-block ()
  "Upsert deterministic projection block from current state.

Projection is always reinserted at end-of-buffer so footer/projection content
tracks transcript growth like a terminal footer."
  (when psi-emacs--state
    ;; Keep dedicated input separator width aligned with current visible window
    ;; whenever projection is re-rendered (notably after startup hydration).
    (when (and (fboundp 'psi-emacs--input-separator-marker-valid-p)
               (fboundp 'psi-emacs--input-separator-needs-refresh-p)
               (fboundp 'psi-emacs--refresh-input-separator-line)
               (psi-emacs--input-separator-marker-valid-p)
               (psi-emacs--input-separator-needs-refresh-p))
      (psi-emacs--refresh-input-separator-line))
    (let* ((follow-anchor (psi-emacs--draft-anchor-at-end-p))
           (range (psi-emacs-state-projection-range psi-emacs--state))
           (start (and (consp range) (car range)))
           (end (and (consp range) (cdr range)))
           (rendered (psi-emacs--projection-render-block psi-emacs--state)))
      (let ((inhibit-read-only t))
        ;; Remove previous projection block first.
        (when (and (markerp start)
                   (markerp end)
                   (marker-buffer start)
                   (marker-buffer end))
          (save-excursion
            (delete-region start end))
          (set-marker start nil)
          (set-marker end nil)
          (setf (psi-emacs-state-projection-range psi-emacs--state) nil))

        ;; Reinsert at current end-of-buffer.
        (unless (string-empty-p rendered)
          (save-excursion
            (psi-emacs--ensure-newline-before-projection-append)
            (let ((new-start (copy-marker (point) nil))
                  ;; Keep insertion-type nil on end marker so appends at the
                  ;; projection tail stay outside the projection range.
                  (new-end (copy-marker (point) nil)))
              (insert rendered)
              ;; After insertion, make start marker advance when user inserts at
              ;; draft/projection boundary so draft text stays outside range.
              (set-marker-insertion-type new-start t)
              (set-marker new-end (point))
              (setf (psi-emacs-state-projection-range psi-emacs--state)
                    (cons new-start new-end))))))

      (when follow-anchor
        (psi-emacs--set-draft-anchor-to-end)))))

(provide 'psi-projection)

;;; psi-projection.el ends here
