;;; psi-widget-renderer.el --- Recursive renderer for psi.ui.widget-spec node trees  -*- lexical-binding: t; -*-

;;; Commentary:
;;
;; Walks a WidgetSpec node tree (produced by psi.ui.widget-spec on the server)
;; and returns a propertized string for insertion into the projection block.
;;
;; Design:
;;   - Pure function: (node data lstate) → string
;;   - No buffer mutations — caller inserts the result
;;   - Local state (collapse, in-flight) is a plain alist, managed by caller
;;   - Interactions (button clicks, collapsible toggles) call back via
;;     registered handler functions
;;
;; Integration:
;;   - psi-projection.el calls psi-widget-renderer-render-spec to get the
;;     rendered string for each widget spec, then inserts into projection block
;;   - psi-events.el handles ui/widgets-updated → triggers re-render
;;
;; See spec/emacs-widget-spec.allium for the full behavioural specification.

;;; Code:

(require 'cl-lib)

;;; Faces — semantic widget faces mapped from node types

(defface psi-widget-heading-face
  '((t :inherit font-lock-doc-face :weight bold))
  "Face for widget heading nodes."
  :group 'psi-emacs)

(defface psi-widget-strong-face
  '((t :inherit bold))
  "Face for widget strong nodes."
  :group 'psi-emacs)

(defface psi-widget-muted-face
  '((t :inherit shadow))
  "Face for widget muted nodes."
  :group 'psi-emacs)

(defface psi-widget-code-face
  '((t :inherit fixed-pitch))
  "Face for widget code nodes."
  :group 'psi-emacs)

(defface psi-widget-success-face
  '((t :inherit success))
  "Face for widget success nodes."
  :group 'psi-emacs)

(defface psi-widget-warning-face
  '((t :inherit warning))
  "Face for widget warning nodes."
  :group 'psi-emacs)

(defface psi-widget-error-face
  '((t :inherit error))
  "Face for widget error nodes."
  :group 'psi-emacs)

(defface psi-widget-button-face
  '((t :inherit link))
  "Face for widget button nodes."
  :group 'psi-emacs)

(defface psi-widget-button-in-flight-face
  '((t :inherit shadow))
  "Face for widget button nodes with a pending in-flight mutation."
  :group 'psi-emacs)

(defface psi-widget-collapsible-face
  '((t :inherit font-lock-keyword-face))
  "Face for widget collapsible title/indicator."
  :group 'psi-emacs)

(defface psi-widget-inline-error-face
  '((t :inherit error :slant italic))
  "Face for inline malformed-node errors."
  :group 'psi-emacs)

;;; Local state accessors
;; lstate is a plist:
;;   :collapsed-keys  — hash-table of node-key → t
;;   :in-flight-keys  — hash-table of node-key → t
;;   :event-state     — alist of key → value

(defun psi-widget-renderer--edn-truthy-p (val)
  "Return non-nil when VAL is truthy in EDN terms.
Treats nil and :false as false; all other values as true."
  (and val (not (eq val :false))))

(defun psi-widget-renderer--collapsed-p (lstate key)
  "Return non-nil when node KEY is collapsed in LSTATE."
  (and key
       (let ((ht (plist-get lstate :collapsed-keys)))
         (and ht (gethash key ht)))))

(defun psi-widget-renderer--in-flight-p (lstate key)
  "Return non-nil when node KEY has an in-flight mutation in LSTATE."
  (and key
       (let ((ht (plist-get lstate :in-flight-keys)))
         (and ht (gethash key ht)))))

;;; Interaction handlers
;; Registered globally; called when user activates a button or toggles collapsible.

(defvar psi-widget-renderer-on-button-activate nil
  "Function called when a button is activated.
Called as (fn widget-id node-key).
widget-id is \"ext-id/widget-id\", node-key is the button's :key.")

(defvar psi-widget-renderer-on-collapsible-toggle nil
  "Function called when a collapsible is toggled.
Called as (fn widget-id node-key).")

;;; Content resolution

(defun psi-widget-renderer--resolve-content (node data)
  "Return the display string for NODE, resolving :content-path against DATA."
  (let ((path (alist-get :content-path node nil nil #'equal)))
    (if path
        (let ((val (psi-widget-renderer--get-path data path)))
          (if (null val) "" (format "%s" val)))
      (or (alist-get :content node nil nil #'equal) ""))))

(defun psi-widget-renderer--get-path (data path)
  "Walk PATH (a list of keys) into DATA alist/plist, returning the value or nil."
  (if (null path)
      data
    (let* ((key  (car path))
           (rest (cdr path))
           (val  (cond
                  ((listp data)  (alist-get key data nil nil #'equal))
                  (t             nil))))
      (psi-widget-renderer--get-path val rest))))

;;; Node validation

(defconst psi-widget-renderer--valid-types
  '(text newline hstack vstack heading strong muted code
    success warning error button collapsible list)
  "Valid :type values for widget nodes.")

(defun psi-widget-renderer--valid-node-p (node)
  "Return non-nil when NODE has a recognised type and required fields."
  (and (listp node)
       (let ((type (alist-get :type node nil nil #'equal)))
         (and (memq type psi-widget-renderer--valid-types)
              (pcase type
                ('button      (and (stringp (alist-get :label node nil nil #'equal))
                                   (listp   (alist-get :mutation node nil nil #'equal))))
                ('collapsible (stringp (alist-get :title node nil nil #'equal)))
                ('list        (not (null (alist-get :items-path node nil nil #'equal))))
                (_            t))))))

;;; Indentation helpers

(defun psi-widget-renderer--indent-string (n)
  "Return a string of N spaces."
  (make-string (max 0 n) ?\s))

(defun psi-widget-renderer--indent-lines (text n)
  "Indent every line of TEXT by N spaces."
  (if (<= n 0)
      text
    (let ((pad (psi-widget-renderer--indent-string n)))
      (mapconcat (lambda (line) (concat pad line))
                 (split-string text "\n")
                 "\n"))))

;;; Core renderer

(defun psi-widget-renderer--render-node (node data lstate widget-id indent)
  "Render NODE into a propertized string.
DATA is the query result alist.  LSTATE is the local state plist.
WIDGET-ID is \"ext-id/widget-id\" for interaction callbacks.
INDENT is the current left-indent level in spaces."
  (if (not (psi-widget-renderer--valid-node-p node))
      (propertize
       (format "[widget error: %s]"
               (if (listp node)
                   (format "unknown type %s" (alist-get :type node nil nil #'equal))
                 "node must be a map"))
       'face 'psi-widget-inline-error-face)
    (let ((type (alist-get :type node nil nil #'equal)))
      (pcase type
        ('text     (psi-widget-renderer--render-text     node data))
        ('newline  "\n")
        ('hstack   (psi-widget-renderer--render-hstack   node data lstate widget-id indent))
        ('vstack   (psi-widget-renderer--render-vstack   node data lstate widget-id indent))
        ('heading  (psi-widget-renderer--render-heading  node data))
        ('strong   (psi-widget-renderer--render-semantic node data 'psi-widget-strong-face))
        ('muted    (psi-widget-renderer--render-semantic node data 'psi-widget-muted-face))
        ('code     (psi-widget-renderer--render-semantic node data 'psi-widget-code-face))
        ('success  (psi-widget-renderer--render-semantic node data 'psi-widget-success-face))
        ('warning  (psi-widget-renderer--render-semantic node data 'psi-widget-warning-face))
        ('error    (psi-widget-renderer--render-semantic node data 'psi-widget-error-face))
        ('button      (psi-widget-renderer--render-button      node lstate widget-id))
        ('collapsible (psi-widget-renderer--render-collapsible node data lstate widget-id indent))
        ('list        (psi-widget-renderer--render-list        node data lstate widget-id indent))
        (_         (propertize (format "[unknown type: %s]" type)
                               'face 'psi-widget-inline-error-face))))))

(defun psi-widget-renderer--render-text (node data)
  "Render a :text NODE."
  (let* ((content (psi-widget-renderer--resolve-content node data))
         (face    (alist-get :face node nil nil #'equal)))
    (if face
        (propertize content 'face face)
      content)))

(defun psi-widget-renderer--render-semantic (node data face)
  "Render a semantic text NODE (strong, muted, code, etc.) with FACE."
  (propertize (psi-widget-renderer--resolve-content node data)
              'face face))

(defun psi-widget-renderer--render-heading (node data)
  "Render a :heading NODE."
  (let* ((content (psi-widget-renderer--resolve-content node data))
         (level   (or (alist-get :level node nil nil #'equal) 1))
         (face    (intern (format "outline-%d" (min level 8)))))
    (propertize content 'face face)))

(defun psi-widget-renderer--render-hstack (node data lstate widget-id indent)
  "Render an :hstack NODE — children joined horizontally."
  (let* ((spacing  (or (alist-get :spacing node nil nil #'equal) 1))
         (sep      (make-string spacing ?\s))
         (children (alist-get :children node nil nil #'equal)))
    (mapconcat
     (lambda (child)
       (psi-widget-renderer--render-node child data lstate widget-id indent))
     (cl-remove-if #'null children)
     sep)))

(defun psi-widget-renderer--render-vstack (node data lstate widget-id indent)
  "Render a :vstack NODE — children joined vertically."
  (let* ((node-indent (or (alist-get :indent node nil nil #'equal) 0))
         (spacing     (or (alist-get :spacing node nil nil #'equal) 0))
         (children    (alist-get :children node nil nil #'equal))
         (total-indent (+ indent node-indent))
         (blank-sep   (make-string spacing ?\n))
         (parts       (mapcar
                       (lambda (child)
                         (psi-widget-renderer--render-node
                          child data lstate widget-id total-indent))
                       (cl-remove-if #'null children))))
    (let ((joined (mapconcat #'identity parts (concat "\n" blank-sep))))
      (if (> total-indent 0)
          (psi-widget-renderer--indent-lines joined total-indent)
        joined))))

(defun psi-widget-renderer--render-button (node lstate widget-id)
  "Render a :button NODE."
  (let* ((label     (alist-get :label    node nil nil #'equal))
         (key       (alist-get :key      node nil nil #'equal))
         (disabled  (psi-widget-renderer--edn-truthy-p
                     (alist-get :disabled node nil nil #'equal)))
         (in-flight (psi-widget-renderer--in-flight-p lstate key))
         (face      (cond (in-flight 'psi-widget-button-in-flight-face)
                          (disabled  'psi-widget-muted-face)
                          (t         'psi-widget-button-face)))
         (display   (format "[%s]" (if in-flight (concat label "…") label)))
         (keymap    (when (and (not disabled) (not in-flight) key widget-id)
                      (psi-widget-renderer--button-keymap widget-id key))))
    (apply #'propertize display
           'face face
           'help-echo (when (not disabled) label)
           (when keymap
             (list 'keymap keymap
                   'mouse-face 'highlight
                   'follow-link t
                   'psi-widget-button-widget-id widget-id
                   'psi-widget-button-key key)))))

(defun psi-widget-renderer--button-keymap (widget-id node-key)
  "Return a keymap that calls `psi-widget-renderer-on-button-activate'."
  (let ((map (make-sparse-keymap)))
    (define-key map (kbd "RET")
      (lambda ()
        (interactive)
        (when (functionp psi-widget-renderer-on-button-activate)
          (funcall psi-widget-renderer-on-button-activate widget-id node-key))))
    (define-key map [mouse-1]
      (lambda (event)
        (interactive "e")
        (ignore event)
        (when (functionp psi-widget-renderer-on-button-activate)
          (funcall psi-widget-renderer-on-button-activate widget-id node-key))))
    map))

(defun psi-widget-renderer--render-collapsible (node data lstate widget-id indent)
  "Render a :collapsible NODE."
  (let* ((key        (alist-get :key   node nil nil #'equal))
         (title-path (alist-get :title-path node nil nil #'equal))
         (title      (if title-path
                         (or (psi-widget-renderer--get-path data title-path)
                             (alist-get :title node nil nil #'equal))
                       (alist-get :title node nil nil #'equal)))
         (collapsed  (psi-widget-renderer--collapsed-p lstate key))
         (indicator  (if collapsed "▶" "▼"))
         (header     (propertize (format "%s %s" indicator title)
                                 'face 'psi-widget-collapsible-face
                                 'mouse-face 'highlight
                                 'follow-link t
                                 'help-echo "Toggle section"
                                 'keymap (when (and key widget-id)
                                           (psi-widget-renderer--collapsible-keymap
                                            widget-id key))))
         (children   (alist-get :children node nil nil #'equal)))
    (if (or collapsed (null children))
        header
      (let* ((child-parts
              (mapcar (lambda (child)
                        (psi-widget-renderer--render-node
                         child data lstate widget-id (+ indent 2)))
                      (cl-remove-if #'null children)))
             (body (mapconcat #'identity child-parts "\n")))
        (concat header "\n" body)))))

(defun psi-widget-renderer--collapsible-keymap (widget-id node-key)
  "Return a keymap that calls `psi-widget-renderer-on-collapsible-toggle'."
  (let ((map (make-sparse-keymap)))
    (define-key map (kbd "RET")
      (lambda ()
        (interactive)
        (when (functionp psi-widget-renderer-on-collapsible-toggle)
          (funcall psi-widget-renderer-on-collapsible-toggle widget-id node-key))))
    (define-key map [mouse-1]
      (lambda (event)
        (interactive "e")
        (ignore event)
        (when (functionp psi-widget-renderer-on-collapsible-toggle)
          (funcall psi-widget-renderer-on-collapsible-toggle widget-id node-key))))
    map))

(defun psi-widget-renderer--render-list (node data lstate widget-id indent)
  "Render a :list NODE — item-spec rendered once per element at items-path."
  (let* ((items-path (alist-get :items-path node nil nil #'equal))
         (item-spec  (alist-get :item-spec  node nil nil #'equal))
         (vertical   (let ((v (alist-get :vertical node 'not-found nil #'equal)))
                       (if (eq v 'not-found) t (and v t))))
         (node-indent (or (alist-get :indent node nil nil #'equal) 0))
         (items      (when items-path
                       (let ((v (psi-widget-renderer--get-path data items-path)))
                         (cond ((vectorp v) (append v nil))
                               ((listp v)  v)
                               (t          nil)))))
         (sep        (if vertical "\n" " ")))
    (if (null items)
        ""
      (mapconcat
       (lambda (item)
         (let* ((item-data (append `((:item . ,item)) data))
                (rendered  (psi-widget-renderer--render-node
                            item-spec item-data lstate widget-id
                            (+ indent node-indent))))
           rendered))
       items
       sep))))

;;; Public API

(defun psi-widget-renderer-render-spec (widget-spec data lstate)
  "Render WIDGET-SPEC into a propertized string.

WIDGET-SPEC is an alist with :id, :extension-id, :spec (root node).
DATA is the EQL query result alist.
LSTATE is the local state plist with :collapsed-keys, :in-flight-keys.

Returns a propertized string ready for insertion into the projection block."
  (let* ((widget-id  (format "%s/%s"
                             (alist-get :extension-id widget-spec nil nil #'equal)
                             (alist-get :id           widget-spec nil nil #'equal)))
         (root-node  (alist-get :spec widget-spec nil nil #'equal)))
    (if (null root-node)
        (propertize "[widget error: missing :spec]" 'face 'psi-widget-inline-error-face)
      (psi-widget-renderer--render-node root-node data lstate widget-id 0))))

(defun psi-widget-renderer-make-lstate (&optional collapsed-keys in-flight-keys event-state)
  "Create a fresh local state plist.
COLLAPSED-KEYS and IN-FLIGHT-KEYS are hash-tables of key → t.
EVENT-STATE is an alist."
  (list :collapsed-keys (or collapsed-keys (make-hash-table :test #'equal))
        :in-flight-keys (or in-flight-keys (make-hash-table :test #'equal))
        :event-state    (or event-state nil)))

(defun psi-widget-renderer-lstate-set-collapsed (lstate key collapsed-p)
  "Return a new LSTATE with KEY collapsed or expanded per COLLAPSED-P."
  (let ((ht (copy-hash-table (or (plist-get lstate :collapsed-keys)
                                 (make-hash-table :test #'equal)))))
    (if collapsed-p
        (puthash key t ht)
      (remhash key ht))
    (plist-put (copy-sequence lstate) :collapsed-keys ht)))

(defun psi-widget-renderer-lstate-set-in-flight (lstate key in-flight-p)
  "Return a new LSTATE with KEY in-flight status set per IN-FLIGHT-P."
  (let ((ht (copy-hash-table (or (plist-get lstate :in-flight-keys)
                                 (make-hash-table :test #'equal)))))
    (if in-flight-p
        (puthash key t ht)
      (remhash key ht))
    (plist-put (copy-sequence lstate) :in-flight-keys ht)))

(defun psi-widget-renderer-lstate-clear-in-flight (lstate)
  "Return a new LSTATE with all in-flight keys cleared."
  (plist-put (copy-sequence lstate)
             :in-flight-keys (make-hash-table :test #'equal)))

(defun psi-widget-renderer-initial-lstate (widget-spec)
  "Build initial local state from WIDGET-SPEC, honouring :initially-expanded."
  (let* ((root     (alist-get :spec widget-spec nil nil #'equal))
         (ht       (make-hash-table :test #'equal)))
    (when root
      (psi-widget-renderer--collect-initial-collapsed root ht))
    (psi-widget-renderer-make-lstate ht)))

(defun psi-widget-renderer--collect-initial-collapsed (node ht)
  "Walk NODE tree, adding initially-collapsed collapsible keys to HT."
  (when (listp node)
    (let ((type (alist-get :type node nil nil #'equal))
          (key  (alist-get :key  node nil nil #'equal)))
      (when (and (eq type 'collapsible) key
                 (not (psi-widget-renderer--edn-truthy-p
                       (alist-get :initially-expanded node nil nil #'equal))))
        (puthash key t ht))
      (dolist (child (alist-get :children node nil nil #'equal))
        (psi-widget-renderer--collect-initial-collapsed child ht))
      (when-let ((item-spec (alist-get :item-spec node nil nil #'equal)))
        (psi-widget-renderer--collect-initial-collapsed item-spec ht)))))

(provide 'psi-widget-renderer)
;;; psi-widget-renderer.el ends here
