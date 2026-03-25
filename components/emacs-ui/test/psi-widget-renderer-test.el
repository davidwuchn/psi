;;; psi-widget-renderer-test.el --- Tests for psi-widget-renderer  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi-widget-renderer)

;;; Helpers

(defun pwrt--spec (id root-node)
  "Build a minimal widget spec alist for testing."
  `((:id . ,id) (:extension-id . "ext") (:spec . ,root-node)))

(defun pwrt--render (node &optional data lstate)
  "Render NODE with optional DATA and LSTATE, return plain string."
  (let* ((spec   (pwrt--spec "w" node))
         (lstate (or lstate (psi-widget-renderer-initial-lstate spec))))
    (substring-no-properties
     (psi-widget-renderer-render-spec spec data lstate))))

(defun pwrt--node (&rest pairs)
  "Build a node alist from PAIRS."
  (cl-loop for (k v) on pairs by #'cddr collect (cons k v)))

;;; ─── Node type: text ─────────────────────────────────────────────────────────

(ert-deftest psi-widget-renderer-text-renders-content ()
  (should (equal "hello"
                 (pwrt--render (pwrt--node :type 'text :content "hello")))))

(ert-deftest psi-widget-renderer-text-resolves-content-path ()
  (let ((data '((:name . "world"))))
    (should (equal "world"
                   (pwrt--render (pwrt--node :type 'text :content-path '(:name))
                                 data)))))

(ert-deftest psi-widget-renderer-text-content-path-missing-key-returns-empty ()
  (let ((data '((:other . "x"))))
    (should (equal ""
                   (pwrt--render (pwrt--node :type 'text :content-path '(:name))
                                 data)))))

(ert-deftest psi-widget-renderer-text-prefers-content-path-over-content ()
  (let ((data '((:name . "from-path"))))
    (should (equal "from-path"
                   (pwrt--render (pwrt--node :type 'text
                                             :content "literal"
                                             :content-path '(:name))
                                 data)))))

;;; ─── Node type: newline ──────────────────────────────────────────────────────

(ert-deftest psi-widget-renderer-newline-renders-newline ()
  (should (equal "\n"
                 (pwrt--render (pwrt--node :type 'newline)))))

;;; ─── Semantic text nodes ─────────────────────────────────────────────────────

(ert-deftest psi-widget-renderer-heading-renders-content ()
  (should (equal "Title"
                 (pwrt--render (pwrt--node :type 'heading :content "Title" :level 1)))))

(ert-deftest psi-widget-renderer-strong-renders-content ()
  (should (equal "bold"
                 (pwrt--render (pwrt--node :type 'strong :content "bold")))))

(ert-deftest psi-widget-renderer-muted-renders-content ()
  (should (equal "quiet"
                 (pwrt--render (pwrt--node :type 'muted :content "quiet")))))

(ert-deftest psi-widget-renderer-code-renders-content ()
  (should (equal "ls -la"
                 (pwrt--render (pwrt--node :type 'code :content "ls -la")))))

(ert-deftest psi-widget-renderer-success-renders-content ()
  (should (equal "done"
                 (pwrt--render (pwrt--node :type 'success :content "done")))))

(ert-deftest psi-widget-renderer-warning-renders-content ()
  (should (equal "slow"
                 (pwrt--render (pwrt--node :type 'warning :content "slow")))))

(ert-deftest psi-widget-renderer-error-renders-content ()
  (should (equal "failed"
                 (pwrt--render (pwrt--node :type 'error :content "failed")))))

;;; ─── Node type: hstack ───────────────────────────────────────────────────────

(ert-deftest psi-widget-renderer-hstack-joins-children-with-spacing ()
  (let ((node (pwrt--node :type 'hstack :spacing 1
                          :children (list (pwrt--node :type 'text :content "a")
                                          (pwrt--node :type 'text :content "b")
                                          (pwrt--node :type 'text :content "c")))))
    (should (equal "a b c" (pwrt--render node)))))

(ert-deftest psi-widget-renderer-hstack-respects-spacing ()
  (let ((node (pwrt--node :type 'hstack :spacing 3
                          :children (list (pwrt--node :type 'text :content "x")
                                          (pwrt--node :type 'text :content "y")))))
    (should (equal "x   y" (pwrt--render node)))))

(ert-deftest psi-widget-renderer-hstack-ignores-nil-children ()
  (let ((node (pwrt--node :type 'hstack :spacing 1
                          :children (list (pwrt--node :type 'text :content "a")
                                          nil
                                          (pwrt--node :type 'text :content "b")))))
    (should (equal "a b" (pwrt--render node)))))

;;; ─── Node type: vstack ───────────────────────────────────────────────────────

(ert-deftest psi-widget-renderer-vstack-joins-children-with-newlines ()
  (let ((node (pwrt--node :type 'vstack :indent 0 :spacing 0
                          :children (list (pwrt--node :type 'text :content "line1")
                                          (pwrt--node :type 'text :content "line2")))))
    (should (equal "line1\nline2" (pwrt--render node)))))

(ert-deftest psi-widget-renderer-vstack-applies-indent ()
  (let ((node (pwrt--node :type 'vstack :indent 2 :spacing 0
                          :children (list (pwrt--node :type 'text :content "a")
                                          (pwrt--node :type 'text :content "b")))))
    (should (equal "  a\n  b" (pwrt--render node)))))

(ert-deftest psi-widget-renderer-vstack-applies-spacing ()
  (let ((node (pwrt--node :type 'vstack :indent 0 :spacing 1
                          :children (list (pwrt--node :type 'text :content "a")
                                          (pwrt--node :type 'text :content "b")))))
    (should (equal "a\n\nb" (pwrt--render node)))))

;;; ─── Node type: button ───────────────────────────────────────────────────────

(ert-deftest psi-widget-renderer-button-renders-label-in-brackets ()
  (let ((node (pwrt--node :type 'button :label "Go" :key "b1" :disabled nil
                          :mutation '((:name . foo/bar)))))
    (should (equal "[Go]" (pwrt--render node)))))

(ert-deftest psi-widget-renderer-button-in-flight-shows-ellipsis ()
  (let* ((spec   (pwrt--spec "w" (pwrt--node :type 'button :label "Go" :key "b1"
                                             :disabled nil
                                             :mutation '((:name . foo/bar)))))
         (lstate (psi-widget-renderer-initial-lstate spec))
         (lstate (psi-widget-renderer-lstate-set-in-flight lstate "b1" t)))
    (should (equal "[Go…]"
                   (substring-no-properties
                    (psi-widget-renderer-render-spec spec nil lstate))))))

(ert-deftest psi-widget-renderer-button-keymap-present-when-not-disabled ()
  (let* ((node   (pwrt--node :type 'button :label "Go" :key "b1" :disabled nil
                             :mutation '((:name . foo/bar))))
         (spec   (pwrt--spec "w" node))
         (lstate (psi-widget-renderer-initial-lstate spec))
         (result (psi-widget-renderer-render-spec spec nil lstate)))
    (should (keymapp (get-text-property 0 'keymap result)))))

(ert-deftest psi-widget-renderer-button-no-keymap-when-disabled ()
  (let* ((node   (pwrt--node :type 'button :label "Go" :key "b1" :disabled t
                             :mutation '((:name . foo/bar))))
         (spec   (pwrt--spec "w" node))
         (lstate (psi-widget-renderer-initial-lstate spec))
         (result (psi-widget-renderer-render-spec spec nil lstate)))
    (should (null (get-text-property 0 'keymap result)))))

;;; ─── Node type: collapsible ──────────────────────────────────────────────────

(ert-deftest psi-widget-renderer-collapsible-shows-children-when-expanded ()
  (let* ((node (pwrt--node :type 'collapsible :title "Section" :key "s1"
                           :initially-expanded t
                           :children (list (pwrt--node :type 'text :content "child"))))
         (spec   (pwrt--spec "w" node))
         (lstate (psi-widget-renderer-initial-lstate spec))
         (result (pwrt--render node nil lstate)))
    (should (string-match-p "child" result))
    (should (string-match-p "▼" result))))

(ert-deftest psi-widget-renderer-collapsible-hides-children-when-collapsed ()
  (let* ((node (pwrt--node :type 'collapsible :title "Section" :key "s1"
                           :initially-expanded nil
                           :children (list (pwrt--node :type 'text :content "child"))))
         (spec   (pwrt--spec "w" node))
         (lstate (psi-widget-renderer-initial-lstate spec))
         (result (pwrt--render node nil lstate)))
    (should-not (string-match-p "child" result))
    (should (string-match-p "▶" result))))

(ert-deftest psi-widget-renderer-collapsible-toggle-expands ()
  (let* ((node (pwrt--node :type 'collapsible :title "S" :key "s1"
                           :initially-expanded nil
                           :children (list (pwrt--node :type 'text :content "inner"))))
         (spec   (pwrt--spec "w" node))
         (lstate (psi-widget-renderer-initial-lstate spec))
         (lstate (psi-widget-renderer-lstate-set-collapsed lstate "s1" nil))
         (result (pwrt--render node nil lstate)))
    (should (string-match-p "inner" result))))

(ert-deftest psi-widget-renderer-collapsible-title-path-resolved ()
  (let* ((data '((:title . "Dynamic Title")))
         (node (pwrt--node :type 'collapsible :title "fallback" :key "s1"
                           :title-path '(:title)
                           :initially-expanded t
                           :children nil))
         (spec   (pwrt--spec "w" node))
         (lstate (psi-widget-renderer-initial-lstate spec))
         (result (pwrt--render node data lstate)))
    (should (string-match-p "Dynamic Title" result))))

;;; ─── Node type: list ─────────────────────────────────────────────────────────

(ert-deftest psi-widget-renderer-list-renders-each-item ()
  (let* ((data '((:items . (((:name . "foo")) ((:name . "bar"))))))
         (node (pwrt--node :type 'list
                           :items-path '(:items)
                           :item-spec (pwrt--node :type 'text :content-path '(:item :name))
                           :vertical t :indent 0))
         (result (pwrt--render node data)))
    (should (string-match-p "foo" result))
    (should (string-match-p "bar" result))))

(ert-deftest psi-widget-renderer-list-empty-items-path-returns-empty ()
  (let* ((data '((:items . nil)))
         (node (pwrt--node :type 'list
                           :items-path '(:items)
                           :item-spec (pwrt--node :type 'text :content "x")
                           :vertical t :indent 0)))
    (should (equal "" (pwrt--render node data)))))

(ert-deftest psi-widget-renderer-list-vertical-joins-with-newlines ()
  (let* ((data '((:items . (((:name . "a")) ((:name . "b"))))))
         (node (pwrt--node :type 'list
                           :items-path '(:items)
                           :item-spec (pwrt--node :type 'text :content-path '(:item :name))
                           :vertical t :indent 0))
         (result (pwrt--render node data)))
    (should (string-match-p "a\nb" result))))

(ert-deftest psi-widget-renderer-list-horizontal-joins-with-space ()
  (let* ((data '((:items . (((:name . "a")) ((:name . "b"))))))
         (node (pwrt--node :type 'list
                           :items-path '(:items)
                           :item-spec (pwrt--node :type 'text :content-path '(:item :name))
                           :vertical nil :indent 0))
         (result (pwrt--render node data)))
    (should (string-match-p "a b" result))))

;;; ─── Malformed nodes ─────────────────────────────────────────────────────────

(ert-deftest psi-widget-renderer-unknown-type-renders-inline-error ()
  (let ((result (pwrt--render (pwrt--node :type 'bogus :content "x"))))
    (should (string-match-p "\\[.*error\\|unknown.*bogus\\]" result))))

(ert-deftest psi-widget-renderer-button-missing-label-renders-inline-error ()
  (let ((result (pwrt--render (pwrt--node :type 'button :key "b1"
                                          :mutation '((:name . foo/bar))))))
    (should (string-match-p "\\[widget error" result))))

(ert-deftest psi-widget-renderer-collapsible-missing-title-renders-inline-error ()
  (let ((result (pwrt--render (pwrt--node :type 'collapsible :key "s1"
                                          :children nil))))
    (should (string-match-p "\\[widget error" result))))

;;; ─── Local state ─────────────────────────────────────────────────────────────

(ert-deftest psi-widget-renderer-initial-lstate-collapses-initially-collapsed ()
  (let* ((node (pwrt--node :type 'collapsible :key "s1" :title "S"
                           :initially-expanded nil :children nil))
         (spec   (pwrt--spec "w" node))
         (lstate (psi-widget-renderer-initial-lstate spec)))
    (should (psi-widget-renderer--collapsed-p lstate "s1"))))

(ert-deftest psi-widget-renderer-initial-lstate-expands-initially-expanded ()
  (let* ((node (pwrt--node :type 'collapsible :key "s1" :title "S"
                           :initially-expanded t :children nil))
         (spec   (pwrt--spec "w" node))
         (lstate (psi-widget-renderer-initial-lstate spec)))
    (should-not (psi-widget-renderer--collapsed-p lstate "s1"))))

(ert-deftest psi-widget-renderer-lstate-set-collapsed-toggles-key ()
  (let* ((lstate (psi-widget-renderer-make-lstate))
         (lstate (psi-widget-renderer-lstate-set-collapsed lstate "k1" t)))
    (should (psi-widget-renderer--collapsed-p lstate "k1"))
    (let ((lstate (psi-widget-renderer-lstate-set-collapsed lstate "k1" nil)))
      (should-not (psi-widget-renderer--collapsed-p lstate "k1")))))

(ert-deftest psi-widget-renderer-lstate-set-in-flight-toggles-key ()
  (let* ((lstate (psi-widget-renderer-make-lstate))
         (lstate (psi-widget-renderer-lstate-set-in-flight lstate "b1" t)))
    (should (psi-widget-renderer--in-flight-p lstate "b1"))
    (let ((lstate (psi-widget-renderer-lstate-set-in-flight lstate "b1" nil)))
      (should-not (psi-widget-renderer--in-flight-p lstate "b1")))))

(ert-deftest psi-widget-renderer-lstate-clear-in-flight-clears-all ()
  (let* ((lstate (psi-widget-renderer-make-lstate))
         (lstate (psi-widget-renderer-lstate-set-in-flight lstate "b1" t))
         (lstate (psi-widget-renderer-lstate-set-in-flight lstate "b2" t))
         (lstate (psi-widget-renderer-lstate-clear-in-flight lstate)))
    (should-not (psi-widget-renderer--in-flight-p lstate "b1"))
    (should-not (psi-widget-renderer--in-flight-p lstate "b2"))))

;;; ─── Composed tree ───────────────────────────────────────────────────────────

(ert-deftest psi-widget-renderer-composed-tree-renders-correctly ()
  (let* ((data '((:phase . "idle") (:model . "claude")))
         (node (pwrt--node :type 'vstack :indent 0 :spacing 0
                           :children
                           (list
                            (pwrt--node :type 'heading :content "Status" :level 1)
                            (pwrt--node :type 'hstack :spacing 1
                                        :children
                                        (list (pwrt--node :type 'muted :content "phase:")
                                              (pwrt--node :type 'text :content-path '(:phase))))
                            (pwrt--node :type 'hstack :spacing 1
                                        :children
                                        (list (pwrt--node :type 'muted :content "model:")
                                              (pwrt--node :type 'text :content-path '(:model)))))))
         (result (pwrt--render node data)))
    (should (string-match-p "Status" result))
    (should (string-match-p "phase:.*idle" result))
    (should (string-match-p "model:.*claude" result))))

(provide 'psi-widget-renderer-test)
;;; psi-widget-renderer-test.el ends here
