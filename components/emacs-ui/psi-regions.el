;;; psi-regions.el --- Region identity helpers for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Stable region identity via text properties + marker index.

;;; Code:

(require 'subr-x)
(require 'psi-globals)

(defconst psi-emacs--region-kind-property 'psi-region-kind
  "Text property key storing region kind identity.")

(defconst psi-emacs--region-id-property 'psi-region-id
  "Text property key storing stable region id identity.")

(defun psi-emacs--region-key (kind id)
  "Return canonical hash key for KIND/ID region identity." 
  (list kind id))

(defun psi-emacs--region-table ()
  "Return (and lazily initialize) region index table for current state."
  (when psi-emacs--state
    (or (psi-emacs-state-regions psi-emacs--state)
        (let ((table (make-hash-table :test #'equal)))
          (setf (psi-emacs-state-regions psi-emacs--state) table)
          table))))

(defun psi-emacs--region-marker-position (value)
  "Return numeric position from marker/int VALUE, else nil."
  (cond
   ((and (markerp value) (marker-buffer value)) (marker-position value))
   ((integerp value) value)
   (t nil)))

(defun psi-emacs--region-entry-live-p (entry)
  "Return non-nil when region ENTRY markers are live in this buffer."
  (and (listp entry)
       (markerp (plist-get entry :start))
       (markerp (plist-get entry :end))
       (marker-buffer (plist-get entry :start))
       (marker-buffer (plist-get entry :end))))

(defun psi-emacs--region-annotate (start end kind id &optional props)
  "Annotate START..END with KIND/ID text properties plus PROPS." 
  (when (and (integerp start)
             (integerp end)
             (< start end))
    (let ((inhibit-read-only t))
      (add-text-properties
       start
       end
       (append (list psi-emacs--region-kind-property kind
                     psi-emacs--region-id-property id)
               props)))))

(defun psi-emacs--region-find-by-property (kind id &optional start end)
  "Find contiguous region bounds with KIND/ID properties between START and END.

Returns (START . END) or nil when not found."
  (let* ((scan-start (max (or start (point-min)) (point-min)))
         (scan-end (min (or end (point-max)) (point-max)))
         (cursor scan-start)
         found)
    (while (and (< cursor scan-end)
                (not found))
      (let ((id-pos (text-property-any cursor scan-end psi-emacs--region-id-property id)))
        (if (null id-pos)
            (setq cursor scan-end)
          (if (equal (get-text-property id-pos psi-emacs--region-kind-property) kind)
              (let* ((id-end (or (next-single-property-change id-pos psi-emacs--region-id-property nil scan-end)
                                 scan-end))
                     (kind-end (or (next-single-property-change id-pos psi-emacs--region-kind-property nil scan-end)
                                   scan-end))
                     (region-end (min id-end kind-end)))
                (setq found (cons id-pos region-end)))
            (setq cursor (1+ id-pos))))))
    found))

(defun psi-emacs--region-unregister (kind id)
  "Remove KIND/ID region entry from index and clear its markers." 
  (when-let* ((table (psi-emacs--region-table))
              (key (psi-emacs--region-key kind id))
              (entry (gethash key table)))
    (let ((start (plist-get entry :start))
          (end (plist-get entry :end)))
      (when (markerp start) (set-marker start nil))
      (when (markerp end) (set-marker end nil)))
    (remhash key table)))

(defun psi-emacs--region-register (kind id start end &optional props)
  "Register KIND/ID region START..END and annotate text with identity props." 
  (let ((start-pos (psi-emacs--region-marker-position start))
        (end-pos (psi-emacs--region-marker-position end)))
    (when (and id
               (integerp start-pos)
               (integerp end-pos)
               (<= start-pos end-pos))
      (let* ((table (psi-emacs--region-table))
             (key (psi-emacs--region-key kind id))
             (entry (and table (gethash key table))))
        (when (psi-emacs--region-entry-live-p entry)
          (let ((old-start (plist-get entry :start))
                (old-end (plist-get entry :end)))
            (when (markerp old-start) (set-marker old-start nil))
            (when (markerp old-end) (set-marker old-end nil))))
        (let ((start-marker (copy-marker start-pos nil))
              (end-marker (copy-marker end-pos nil)))
          (set-marker-insertion-type start-marker t)
          (set-marker-insertion-type end-marker nil)
          (puthash key (list :kind kind
                             :id id
                             :start start-marker
                             :end end-marker)
                   table))
        (psi-emacs--region-annotate start-pos end-pos kind id props)
        id))))

(defun psi-emacs--region-bounds (kind id)
  "Return bounds (START . END) for KIND/ID, repairing from properties when needed." 
  (when id
    (let* ((table (psi-emacs--region-table))
           (key (psi-emacs--region-key kind id))
           (entry (and table (gethash key table))))
      (cond
       ((psi-emacs--region-entry-live-p entry)
        (cons (marker-position (plist-get entry :start))
              (marker-position (plist-get entry :end))))
       (t
        (when-let ((bounds (psi-emacs--region-find-by-property kind id)))
          (psi-emacs--region-register kind id (car bounds) (cdr bounds))
          bounds))))))

(defun psi-emacs--regions-clear ()
  "Clear all tracked region markers in current state and empty region index." 
  (when psi-emacs--state
    (let ((table (psi-emacs-state-regions psi-emacs--state)))
      (when (hash-table-p table)
        (maphash (lambda (_key entry)
                   (let ((start (plist-get entry :start))
                         (end (plist-get entry :end)))
                     (when (markerp start) (set-marker start nil))
                     (when (markerp end) (set-marker end nil))))
                 table)
        (clrhash table)))
    (setf (psi-emacs-state-active-assistant-id psi-emacs--state) nil)
    (setf (psi-emacs-state-active-thinking-id psi-emacs--state) nil)))

(defun psi-emacs--next-region-id (kind)
  "Allocate and return next stable id token for KIND." 
  (when psi-emacs--state
    (let* ((next (1+ (or (psi-emacs-state-region-seq psi-emacs--state) 0)))
           (kind* (if (symbolp kind) (symbol-name kind) (format "%s" kind)))
           (id (format "%s-%d" kind* next)))
      (setf (psi-emacs-state-region-seq psi-emacs--state) next)
      id)))

(provide 'psi-regions)

;;; psi-regions.el ends here
