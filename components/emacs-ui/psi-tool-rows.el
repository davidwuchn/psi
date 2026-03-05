;;; psi-tool-rows.el --- Tool row rendering helpers for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted tool row summary/render/update helpers used by psi.el.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'ansi-color)
(require 'json)
(require 'psi-globals)

(defun psi-emacs--string-has-face-p (text)
  "Return non-nil if TEXT has any `face' property."
  (let ((i 0)
        (len (length text))
        (hit nil))
    (while (and (< i len) (not hit))
      (setq hit (get-text-property i 'face text))
      (setq i (1+ i)))
    hit))

(defun psi-emacs--ansi-to-face (text)
  "Return TEXT with ANSI escapes converted to Emacs face properties."
  (let* ((raw (or text ""))
         (had-ansi (string-match-p "\x1b\\[[0-9;]*m" raw))
         (converted (ansi-color-apply raw)))
    (if (and had-ansi
             (not (psi-emacs--string-has-face-p converted))
             (not (string-empty-p converted)))
        (propertize converted 'face 'ansi-color-red)
      converted)))

(defconst psi-emacs--tool-header-max-chars 120
  "Maximum characters shown in a tool row header summary.")

(defun psi-emacs--alist-map-p (value)
  "Return non-nil when VALUE has alist-map shape."
  (and (listp value)
       (cl-every #'consp value)))

(defun psi-emacs--hash-table->alist (table)
  "Convert hash TABLE to an alist."
  (let (out)
    (maphash (lambda (k v)
               (push (cons k v) out))
             table)
    (nreverse out)))

(defun psi-emacs--json-parse-string-safe (json-string)
  "Parse JSON-STRING into an alist map, or nil when unreadable."
  (condition-case _
      (if (fboundp 'json-parse-string)
          (json-parse-string json-string
                             :object-type 'alist
                             :array-type 'list
                             :null-object nil
                             :false-object nil)
        (let ((json-object-type 'alist)
              (json-array-type 'list)
              (json-key-type 'string))
          (json-read-from-string json-string)))
    (error nil)))

(defun psi-emacs--tool-args-map (parsed-args arguments)
  "Return plist describing normalized tool args from PARSED-ARGS/ARGUMENTS.

Result shape:
  :args               alist map or nil
  :invalid-args-type  non-nil when args parse to a non-map value."
  (cond
   ((and (not (null parsed-args))
         (psi-emacs--alist-map-p parsed-args))
    (list :args parsed-args :invalid-args-type nil))

   ((hash-table-p parsed-args)
    (list :args (psi-emacs--hash-table->alist parsed-args)
          :invalid-args-type nil))

   ((null parsed-args)
    (if (and (stringp arguments)
             (not (string-empty-p (string-trim arguments))))
        (let ((parsed (psi-emacs--json-parse-string-safe arguments)))
          (cond
           ((psi-emacs--alist-map-p parsed)
            (list :args parsed :invalid-args-type nil))
           ((hash-table-p parsed)
            (list :args (psi-emacs--hash-table->alist parsed)
                  :invalid-args-type nil))
           ((null parsed)
            ;; Parse failures can occur while arguments are still streaming.
            (list :args nil :invalid-args-type nil))
           (t
            (list :args nil :invalid-args-type t))))
      (list :args nil :invalid-args-type nil)))

   (t
    (list :args nil :invalid-args-type t))))

(defun psi-emacs--tool-arg-get (args keys)
  "Return first non-nil arg value from ARGS for KEYS."
  (when (psi-emacs--alist-map-p args)
    (psi-emacs--event-data-get args keys)))

(defun psi-emacs--tool-display-id (tool-id)
  "Return display-friendly TOOL-ID.

For composite ids of shape `call|item`, keep the call id prefix."
  (if (and (stringp tool-id)
           (string-match-p "|" tool-id))
      (car (split-string tool-id "|" t))
    tool-id))

(defun psi-emacs--single-line (text)
  "Normalize TEXT to one trimmed display line."
  (let ((line (or text "")))
    (setq line (replace-regexp-in-string "[\r\n\t]+" " " line))
    (setq line (replace-regexp-in-string " +" " " line))
    (string-trim line)))

(defun psi-emacs--truncate-single-line (text max-chars)
  "Clamp TEXT to MAX-CHARS with ellipsis when needed."
  (let* ((line (psi-emacs--single-line text))
         (limit (max 1 (or max-chars 1))))
    (if (> (length line) limit)
        (concat (substring line 0 (max 0 (1- limit))) "…")
      line)))

(defun psi-emacs--project-root-directory ()
  "Return canonical project root directory for current buffer, or nil."
  (let* ((base (and (stringp default-directory)
                    (not (string-empty-p default-directory))
                    (expand-file-name default-directory)))
         (project-root
          (when (and base (fboundp 'project-current))
            (let ((proj (ignore-errors (project-current nil base))))
              (cond
               ((and proj (fboundp 'project-root))
                (ignore-errors (project-root proj)))
               ((and proj (fboundp 'project-roots))
                (car (ignore-errors (project-roots proj))))
               (t nil)))))
         (root (or project-root base)))
    (when (and (stringp root)
               (not (string-empty-p root)))
      (file-name-as-directory (expand-file-name root)))))

(defun psi-emacs--display-path-relative-to-project (path-text)
  "Return PATH-TEXT relative to current project root when possible."
  (let ((path (string-trim (or path-text ""))))
    (if (string-empty-p path)
        path
      (if (file-name-absolute-p path)
          (let* ((absolute (expand-file-name path))
                 (project-root (psi-emacs--project-root-directory)))
            (if (and project-root
                     (file-in-directory-p absolute project-root))
                (file-relative-name absolute project-root)
              (abbreviate-file-name absolute)))
        path))))

(defun psi-emacs--tool-display-name (name)
  "Return display label for tool NAME."
  (if (equal name "bash") "$" name))

(defun psi-emacs--tool-primary-text (name primary-value)
  "Return display text for tool NAME primary argument PRIMARY-VALUE."
  (when (and primary-value
             (not (string-empty-p (format "%s" primary-value))))
    (let ((text (psi-emacs--single-line (format "%s" primary-value))))
      (if (member name '("read" "edit" "write"))
          (psi-emacs--display-path-relative-to-project text)
        text))))

(defun psi-emacs--tool-summary (tool-name parsed-args arguments tool-id)
  "Return display summary for a tool row.

Prefers TOOL-NAME + key call argument (path/command) over internal TOOL-ID.
TOOL-ID remains fallback-only when tool name is absent."
  (let* ((name-raw (or tool-name
                       (psi-emacs--tool-display-id tool-id)
                       "tool"))
         (name (if (symbolp name-raw)
                   (symbol-name name-raw)
                 (format "%s" name-raw)))
         (display-name (psi-emacs--tool-display-name name))
         (args-info (psi-emacs--tool-args-map parsed-args arguments))
         (args (plist-get args-info :args))
         (invalid-args? (plist-get args-info :invalid-args-type))
         (known-tool? (member name '("read" "edit" "write" "bash")))
         (primary-value (pcase name
                          ((or "read" "edit" "write")
                           (psi-emacs--tool-arg-get args '("path" :path path)))
                          ("bash"
                           (psi-emacs--tool-arg-get args '("command" :command command)))
                          (_ nil)))
         (primary-text (psi-emacs--tool-primary-text name primary-value))
         (summary (cond
                   (invalid-args?
                    (format "%s [invalid arg]" display-name))
                   ((and known-tool? primary-text)
                    (format "%s %s" display-name primary-text))
                   (known-tool?
                    (format "%s …" display-name))
                   (t
                    display-name))))
    (psi-emacs--truncate-single-line summary psi-emacs--tool-header-max-chars)))

(defun psi-emacs--tool-status-label (stage is-error)
  "Return normalized display status from lifecycle STAGE and IS-ERROR."
  (cond
   ((equal stage "start") "pending")
   ((member stage '("delta" "executing" "update")) "running")
   ((equal stage "result") (if is-error "error" "success"))
   ((and (stringp stage) (not (string-empty-p stage))) stage)
   (t "running")))

(defun psi-emacs--tool-next-accumulated-text (row stage text arguments)
  "Return next accumulated body text from ROW and event payload.

`tool/delta` arguments updates mutate header metadata, not body output,
when ARGUMENTS is present. `tool/update` and `tool/result` replace body text
with latest snapshot semantics."
  (let* ((previous (or (plist-get row :accumulated-text) ""))
         (next (or text ""))
         (has-next (not (string-empty-p next)))
         (arguments-event? (stringp arguments)))
    (cond
     ((member stage '("update" "result"))
      (if has-next next previous))
     ((and (equal stage "delta") arguments-event?)
      previous)
     (has-next
      (concat previous next))
     (t previous))))

(defun psi-emacs--projection-start-position ()
  "Return projection block start position, or nil when absent."
  (let* ((range (and psi-emacs--state
                     (psi-emacs-state-projection-range psi-emacs--state)))
         (start (and (consp range) (car range))))
    (when (and (markerp start)
               (marker-buffer start))
      (marker-position start))))

(defun psi-emacs--tool-row-safe-end-position (end-marker)
  "Return safe end position for END-MARKER that never crosses projection start."
  (let ((end-pos (and (markerp end-marker)
                      (marker-buffer end-marker)
                      (marker-position end-marker)))
        (projection-start (psi-emacs--projection-start-position)))
    (cond
     ((null end-pos) nil)
     ((and (numberp projection-start)
           (> end-pos projection-start))
      projection-start)
     (t end-pos))))

(defun psi-emacs--tool-row-header-string (tool-summary status)
  "Build collapsed header string from TOOL-SUMMARY and STATUS."
  (format "%s %s\n" tool-summary status))

(defun psi-emacs--tool-row-string (tool-summary status text)
  "Build expanded tool row string from TOOL-SUMMARY STATUS and TEXT.

ANSI sequences in TEXT are converted to Emacs faces."
  (let ((prefix (format "%s %s: " tool-summary status))
        (body (psi-emacs--ansi-to-face text)))
    (concat prefix body "\n")))

(defun psi-emacs--render-tool-row (tool-summary status accumulated-text mode)
  "Render tool row using TOOL-SUMMARY STATUS ACCUMULATED-TEXT and MODE."
  (if (eq mode 'collapsed)
      (psi-emacs--tool-row-header-string tool-summary status)
    (psi-emacs--tool-row-string tool-summary status (or accumulated-text ""))))

(defun psi-emacs--upsert-tool-row (tool-id stage text &optional tool-name arguments parsed-args is-error)
  "Create or update TOOL-ID row for lifecycle STAGE.

TEXT represents tool execution output snapshots.
TOOL-NAME/ARGUMENTS/PARSED-ARGS update display metadata for call summaries.
Rows are rendered according to global tool-output-view-mode."
  (when (and psi-emacs--state tool-id)
    (let* ((follow-anchor (psi-emacs--draft-anchor-at-end-p))
           (rows (psi-emacs-state-tool-rows psi-emacs--state))
           (view-mode (psi-emacs-state-tool-output-view-mode psi-emacs--state))
           (row (gethash tool-id rows))
           (start (plist-get row :start))
           (end (plist-get row :end))
           (tool-name* (or tool-name (plist-get row :tool-name)))
           (arguments* (if (stringp arguments)
                           arguments
                         (plist-get row :arguments)))
           (parsed-args* (if (null parsed-args)
                             (plist-get row :parsed-args)
                           parsed-args))
           (is-error* (if (null is-error)
                          (plist-get row :is-error)
                        is-error))
           (status (psi-emacs--tool-status-label stage is-error*))
           (accumulated (psi-emacs--tool-next-accumulated-text row stage text arguments))
           (tool-summary (psi-emacs--tool-summary tool-name* parsed-args* arguments* tool-id))
           (rendered (psi-emacs--render-tool-row tool-summary status accumulated view-mode)))
      (if (and (markerp start)
               (markerp end)
               (marker-buffer start)
               (marker-buffer end))
          (save-excursion
            (let* ((start-pos (marker-position start))
                   (end-pos (psi-emacs--tool-row-safe-end-position end)))
              (when (and (numberp start-pos)
                         (numberp end-pos)
                         (<= start-pos end-pos))
                (goto-char start-pos)
                (delete-region start-pos end-pos)
                (insert rendered)
                (set-marker end (point))
                ;; Keep row boundary stable when projection is inserted at row end.
                (set-marker-insertion-type end nil)))
            (puthash tool-id (list :id tool-id
                                   :tool-name tool-name*
                                   :arguments arguments*
                                   :parsed-args parsed-args*
                                   :is-error is-error*
                                   :stage stage
                                   :status status
                                   :text text
                                   :tool-summary tool-summary
                                   :accumulated-text accumulated
                                   :start start
                                   :end end)
                     rows))
        (save-excursion
          (psi-emacs--ensure-newline-before-append)
          (let ((new-start (copy-marker (point) nil))
                ;; Keep insertion-type nil so projection/footer inserted at the
                ;; row boundary stays outside this tool row range.
                (new-end (copy-marker (point) nil)))
            (insert rendered)
            (set-marker new-end (point))
            (set-marker-insertion-type new-end nil)
            (puthash tool-id (list :id tool-id
                                   :tool-name tool-name*
                                   :arguments arguments*
                                   :parsed-args parsed-args*
                                   :is-error is-error*
                                   :stage stage
                                   :status status
                                   :text text
                                   :tool-summary tool-summary
                                   :accumulated-text accumulated
                                   :start new-start
                                   :end new-end)
                     rows))))
      (when follow-anchor
        (psi-emacs--set-draft-anchor-to-end)))))

(defun psi-emacs-toggle-tool-output-view ()
  "Toggle global tool-output view mode between collapsed and expanded.

In collapsed mode only the header line of each tool row is shown.
In expanded mode the full body output of each tool row is shown.
The toggle applies to all existing tool rows and future tool rows.
This command is valid even when no tool rows exist."
  (interactive)
  (when psi-emacs--state
    (let* ((current (psi-emacs-state-tool-output-view-mode psi-emacs--state))
           (new-mode (if (eq current 'collapsed) 'expanded 'collapsed))
           (rows (psi-emacs-state-tool-rows psi-emacs--state)))
      (setf (psi-emacs-state-tool-output-view-mode psi-emacs--state) new-mode)
      ;; Re-render all existing tool rows with the new mode
      (maphash (lambda (tool-id row)
                 (let* ((accumulated (plist-get row :accumulated-text))
                        (start (plist-get row :start))
                        (end (plist-get row :end))
                        (tool-summary (or (plist-get row :tool-summary)
                                          (psi-emacs--tool-summary
                                           (plist-get row :tool-name)
                                           (plist-get row :parsed-args)
                                           (plist-get row :arguments)
                                           tool-id)))
                        (status (or (plist-get row :status)
                                    (psi-emacs--tool-status-label
                                     (plist-get row :stage)
                                     (plist-get row :is-error)))))
                   (when (and (markerp start)
                              (markerp end)
                              (marker-buffer start)
                              (marker-buffer end))
                     (let ((rendered (psi-emacs--render-tool-row
                                      tool-summary status accumulated new-mode)))
                       (save-excursion
                         (let* ((start-pos (marker-position start))
                                (end-pos (psi-emacs--tool-row-safe-end-position end)))
                           (when (and (numberp start-pos)
                                      (numberp end-pos)
                                      (<= start-pos end-pos))
                             (goto-char start-pos)
                             (delete-region start-pos end-pos)
                             (insert rendered)
                             (set-marker end (point))
                             ;; Keep row boundary stable with projection/footer.
                             (set-marker-insertion-type end nil))))))))
               rows)
      (psi-emacs--refresh-header-line))))

(provide 'psi-tool-rows)

;;; psi-tool-rows.el ends here
