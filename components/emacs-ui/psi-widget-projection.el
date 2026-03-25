;;; psi-widget-projection.el --- Widget-spec query, lstate, and projection wiring  -*- lexical-binding: t; -*-

;;; Commentary:
;;
;; Wires psi-widget-renderer into the live frontend:
;;
;;   ui/widgets-updated event
;;     → re-query [:psi.ui/widget-specs] via query_eql
;;     → store specs in psi-emacs-state
;;     → merge/initialise per-widget local state (lstate)
;;     → trigger projection block re-render
;;
;;   psi-widget-projection-render-specs
;;     → called from psi-projection.el render block
;;     → renders each spec via psi-widget-renderer-render-spec
;;     → returns propertized string
;;
;;   Button / collapsible interaction handlers
;;     → update lstate
;;     → fire mutation over RPC (button) or re-render (collapsible)

;;; Code:

(require 'cl-lib)
(require 'psi-rpc)
(require 'psi-widget-renderer)

;;; Forward declarations

(declare-function psi-emacs-state-rpc-client           "psi")
(declare-function psi-emacs-state-projection-widget-specs  "psi")
(declare-function psi-emacs-state-projection-widget-lstates "psi")
(declare-function psi-emacs--upsert-projection-block   "psi-projection")

(defvar psi-emacs--state)
(defvar psi-emacs--send-request-function)

;;; Constants

(defconst psi-widget-projection--query
  "[:psi.ui/widget-specs]"
  "EQL query string to fetch all registered widget specs.")

;;; Query + spec storage

(defun psi-widget-projection-request-specs ()
  "Fire query_eql for :psi.ui/widget-specs and store results in state."
  (when (and psi-emacs--state
             (functionp psi-emacs--send-request-function))
    (funcall psi-emacs--send-request-function
             psi-emacs--state
             "query_eql"
             `((:query . ,psi-widget-projection--query))
             (lambda (frame)
               (psi-widget-projection--handle-specs-result frame)))))

(defun psi-widget-projection--handle-specs-result (frame)
  "Handle query_eql response FRAME for widget specs."
  (when psi-emacs--state
    (let* ((data   (alist-get :data frame nil nil #'equal))
           (result (and (listp data)
                        (alist-get :result data nil nil #'equal)))
           (specs  (and (listp result)
                        (alist-get :psi.ui/widget-specs result nil nil #'equal)))
           (specs-list (cond ((vectorp specs) (append specs nil))
                             ((listp specs)   specs)
                             (t               nil))))
      (setf (psi-emacs-state-projection-widget-specs psi-emacs--state)
            specs-list)
      (psi-widget-projection--sync-lstates specs-list)
      (psi-emacs--upsert-projection-block))))

;;; Local state management

(defun psi-widget-projection--sync-lstates (specs)
  "Sync per-widget lstates for SPECS — init new, preserve existing, drop removed."
  (let* ((current-lstates (or (psi-emacs-state-projection-widget-lstates psi-emacs--state)
                              (make-hash-table :test #'equal)))
         (new-lstates     (make-hash-table :test #'equal)))
    (dolist (spec specs)
      (let* ((ext-id    (alist-get :extension-id spec nil nil #'equal))
             (widget-id (alist-get :id           spec nil nil #'equal))
             (key       (format "%s/%s" ext-id widget-id))
             (existing  (gethash key current-lstates)))
        (puthash key
                 (or existing
                     (psi-widget-renderer-initial-lstate spec))
                 new-lstates)))
    (setf (psi-emacs-state-projection-widget-lstates psi-emacs--state)
          new-lstates)))

(defun psi-widget-projection--get-lstate (ext-id widget-id)
  "Return lstate for EXT-ID/WIDGET-ID, or a fresh one."
  (let ((lstates (psi-emacs-state-projection-widget-lstates psi-emacs--state))
        (key     (format "%s/%s" ext-id widget-id)))
    (if (hash-table-p lstates)
        (or (gethash key lstates)
            (psi-widget-renderer-make-lstate))
      (psi-widget-renderer-make-lstate))))

(defun psi-widget-projection--set-lstate (ext-id widget-id lstate)
  "Store LSTATE for EXT-ID/WIDGET-ID."
  (when psi-emacs--state
    (let ((lstates (or (psi-emacs-state-projection-widget-lstates psi-emacs--state)
                       (make-hash-table :test #'equal)))
          (key     (format "%s/%s" ext-id widget-id)))
      (unless (hash-table-p (psi-emacs-state-projection-widget-lstates psi-emacs--state))
        (setf (psi-emacs-state-projection-widget-lstates psi-emacs--state) lstates))
      (puthash key lstate lstates))))

;;; Rendering

(defun psi-widget-projection-render-specs (state)
  "Render all widget specs from STATE into a propertized string.
Returns empty string when no specs are registered."
  (let ((specs (psi-emacs-state-projection-widget-specs state)))
    (if (null specs)
        ""
      (mapconcat
       (lambda (spec)
         (let* ((ext-id    (alist-get :extension-id spec nil nil #'equal))
                (widget-id (alist-get :id           spec nil nil #'equal))
                (lstate    (psi-widget-projection--get-lstate ext-id widget-id)))
           (psi-widget-renderer-render-spec spec nil lstate)))
       specs
       "\n"))))

;;; Interaction handlers

(defun psi-widget-projection--parse-widget-id (widget-id)
  "Split \"ext-id/widget-id\" into (ext-id . widget-id) cons, or nil."
  (when (stringp widget-id)
    (let ((pos (string-match "/" widget-id)))
      (when pos
        (cons (substring widget-id 0 pos)
              (substring widget-id (1+ pos)))))))

(defun psi-widget-projection--on-button-activate (widget-id node-key)
  "Handle button activation for WIDGET-ID / NODE-KEY.
Updates lstate to in-flight, fires mutation over RPC, re-renders."
  (when-let ((parsed (psi-widget-projection--parse-widget-id widget-id)))
    (let* ((ext-id    (car parsed))
           (wid       (cdr parsed))
           (specs     (psi-emacs-state-projection-widget-specs psi-emacs--state))
           (spec      (cl-find-if
                       (lambda (s)
                         (and (equal (alist-get :extension-id s nil nil #'equal) ext-id)
                              (equal (alist-get :id s nil nil #'equal) wid)))
                       specs))
           (lstate    (psi-widget-projection--get-lstate ext-id wid))
           (root-node (alist-get :spec spec nil nil #'equal))
           (button    (psi-widget-projection--find-node-by-key root-node node-key))
           (mutation  (and button (alist-get :mutation button nil nil #'equal))))
      (when (and spec mutation
                 (not (psi-widget-renderer--in-flight-p lstate node-key))
                 (not (alist-get :disabled button nil nil #'equal)))
        ;; Mark in-flight
        (psi-widget-projection--set-lstate
         ext-id wid
         (psi-widget-renderer-lstate-set-in-flight lstate node-key t))
        ;; Fire mutation via query_eql
        (psi-widget-projection--dispatch-mutation mutation ext-id wid node-key)
        ;; Re-render immediately to show spinner
        (psi-emacs--upsert-projection-block)))))

(defun psi-widget-projection--dispatch-mutation (mutation ext-id widget-id node-key)
  "Send MUTATION as an EQL mutation over RPC.
On response, clear in-flight state and re-render."
  (when (functionp psi-emacs--send-request-function)
    (let* ((mut-name   (alist-get :name   mutation nil nil #'equal))
           (mut-params (or (alist-get :params mutation nil nil #'equal) '()))
           (query-str  (format "[(%s %s)]"
                               mut-name
                               (psi-widget-projection--edn-encode mut-params))))
      (funcall psi-emacs--send-request-function
               psi-emacs--state
               "query_eql"
               `((:query . ,query-str))
               (lambda (_frame)
                 ;; Clear in-flight on any response (success or error)
                 (when psi-emacs--state
                   (let ((lstate (psi-widget-projection--get-lstate ext-id widget-id)))
                     (psi-widget-projection--set-lstate
                      ext-id widget-id
                      (psi-widget-renderer-lstate-set-in-flight lstate node-key nil)))
                   (psi-emacs--upsert-projection-block)))))))

(defun psi-widget-projection--on-collapsible-toggle (widget-id node-key)
  "Handle collapsible toggle for WIDGET-ID / NODE-KEY.
Updates local collapsed state and re-renders — no RPC round-trip."
  (when-let ((parsed (psi-widget-projection--parse-widget-id widget-id)))
    (let* ((ext-id (car parsed))
           (wid    (cdr parsed))
           (lstate (psi-widget-projection--get-lstate ext-id wid))
           (collapsed-p (psi-widget-renderer--collapsed-p lstate node-key))
           (new-lstate  (psi-widget-renderer-lstate-set-collapsed
                         lstate node-key (not collapsed-p))))
      (psi-widget-projection--set-lstate ext-id wid new-lstate)
      (psi-emacs--upsert-projection-block))))

;;; Node lookup

(defun psi-widget-projection--find-node-by-key (node key)
  "Find the first node in NODE tree with :key equal to KEY."
  (when (listp node)
    (if (equal (alist-get :key node nil nil #'equal) key)
        node
      (let ((children (alist-get :children node nil nil #'equal))
            (item-spec (alist-get :item-spec node nil nil #'equal))
            (found nil))
        (dolist (child (append (cond ((vectorp children) (append children nil))
                                     ((listp children) children))
                               (when item-spec (list item-spec))))
          (unless found
            (setq found (psi-widget-projection--find-node-by-key child key))))
        found))))

;;; EDN encoding (minimal — for mutation params)

(defun psi-widget-projection--edn-encode (x)
  "Encode X as a minimal EDN string for mutation params."
  (cond
   ((null x)     "nil")
   ((eq x t)     "true")
   ((eq x :false) "false")
   ((numberp x)  (number-to-string x))
   ((stringp x)  (format "%S" x))
   ((keywordp x) (symbol-name x))
   ((symbolp x)  (symbol-name x))
   ((vectorp x)
    (format "[%s]" (mapconcat #'psi-widget-projection--edn-encode
                              (append x nil) " ")))
   ((listp x)
    (if (and (consp (car x)) (not (listp (caar x))))
        ;; alist → EDN map
        (format "{%s}"
                (mapconcat
                 (lambda (pair)
                   (format "%s %s"
                           (psi-widget-projection--edn-encode (car pair))
                           (psi-widget-projection--edn-encode (cdr pair))))
                 x " "))
      (format "(%s)" (mapconcat #'psi-widget-projection--edn-encode x " "))))
   (t (format "%S" x))))

;;; Setup — register interaction handlers

(defun psi-widget-projection-setup ()
  "Register widget interaction handlers with psi-widget-renderer.
Call once during frontend initialisation."
  (setq psi-widget-renderer-on-button-activate
        #'psi-widget-projection--on-button-activate)
  (setq psi-widget-renderer-on-collapsible-toggle
        #'psi-widget-projection--on-collapsible-toggle))

(provide 'psi-widget-projection)
;;; psi-widget-projection.el ends here
