;;; psi-widget-projection-events-test.el --- Event tests for psi-widget-projection  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi)
(require 'psi-widget-renderer)
(require 'psi-widget-projection)

(defmacro pwpt--with-state (&rest body)
  `(let ((psi-emacs--state (psi-emacs--initialize-state nil)))
     ,@body))

(defun pwpt--make-spec-with-subs (id subscriptions &optional root-node)
  "Build a spec with :subscriptions."
  `((:id . ,id)
    (:extension-id . "ext")
    (:subscriptions . ,subscriptions)
    (:spec . ,(or root-node `((:type . text) (:content . ,id))))))

(defun pwpt--make-sub (event-name &optional local-state-map)
  "Build an EventSubscription alist."
  (if local-state-map
      `((:event-name . ,event-name) (:local-state-map . ,local-state-map))
    `((:event-name . ,event-name))))

(ert-deftest pwpt-specs-subscribed-to-finds-matching ()
  (pwpt--with-state
   (let* ((sub  (pwpt--make-sub "tool/result"))
          (spec (pwpt--make-spec-with-subs "w1" (list sub))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (let ((found (psi-widget-projection--specs-subscribed-to "tool/result")))
       (should (= 1 (length found)))
       (should (equal "w1" (alist-get :id (car found) nil nil #'equal)))))))

(ert-deftest pwpt-specs-subscribed-to-ignores-non-matching ()
  (pwpt--with-state
   (let* ((sub  (pwpt--make-sub "tool/result"))
          (spec (pwpt--make-spec-with-subs "w1" (list sub))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (should (null (psi-widget-projection--specs-subscribed-to "session/updated"))))))

(ert-deftest pwpt-specs-subscribed-to-returns-nil-when-no-specs ()
  (pwpt--with-state
   (should (null (psi-widget-projection--specs-subscribed-to "tool/result")))))

(ert-deftest pwpt-specs-subscribed-to-handles-vector-subscriptions ()
  "Subscriptions may arrive as a vector from EDN decoding."
  (pwpt--with-state
   (let* ((sub  (pwpt--make-sub "tool/result"))
          (spec `((:id . "w1") (:extension-id . "ext")
                  (:subscriptions . ,(vector sub))
                  (:spec . ((:type . text) (:content . "x"))))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (should (= 1 (length (psi-widget-projection--specs-subscribed-to "tool/result")))))))

(ert-deftest pwpt-specs-subscribed-to-multiple-specs-only-matching ()
  (pwpt--with-state
   (let* ((sub1  (pwpt--make-sub "tool/result"))
          (sub2  (pwpt--make-sub "session/updated"))
          (spec1 (pwpt--make-spec-with-subs "w1" (list sub1)))
          (spec2 (pwpt--make-spec-with-subs "w2" (list sub2))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec1 spec2))
     (let ((found (psi-widget-projection--specs-subscribed-to "tool/result")))
       (should (= 1 (length found)))
       (should (equal "w1" (alist-get :id (car found) nil nil #'equal)))))))

(ert-deftest pwpt-merge-event-payload-extracts-path ()
  (let* ((payload '((:tool-id . "t1") (:tool-name . "bash")))
         (lsm     '((:last-tool-id . (:tool-id))))
         (result  (psi-widget-projection--merge-event-payload nil lsm payload)))
    (should (equal "t1" (alist-get :last-tool-id result nil nil #'equal)))))

(ert-deftest pwpt-merge-event-payload-preserves-existing-keys ()
  (let* ((existing '((:kept . "yes") (:last-tool-id . "old")))
         (payload  '((:tool-id . "new")))
         (lsm      '((:last-tool-id . (:tool-id))))
         (result   (psi-widget-projection--merge-event-payload existing lsm payload)))
    (should (equal "new" (alist-get :last-tool-id result nil nil #'equal)))
    (should (equal "yes" (alist-get :kept result nil nil #'equal)))))

(ert-deftest pwpt-merge-event-payload-nil-lsm-returns-existing ()
  (let* ((existing '((:kept . "yes")))
         (result   (psi-widget-projection--merge-event-payload existing nil nil)))
    (should (equal existing result))))

(ert-deftest pwpt-merge-event-payload-missing-path-stores-nil ()
  (let* ((payload '((:other . "x")))
         (lsm     '((:my-key . (:missing-path))))
         (result  (psi-widget-projection--merge-event-payload nil lsm payload)))
    (should (null (alist-get :my-key result nil nil #'equal)))))

(ert-deftest pwpt-merge-event-payload-handles-vector-lsm ()
  "local-state-map may arrive as a vector from EDN decoding."
  (let* ((payload '((:tool-id . "t1")))
         (lsm     (vector '(:last-tool-id . (:tool-id))))
         (result  (psi-widget-projection--merge-event-payload nil lsm payload)))
    (should (equal "t1" (alist-get :last-tool-id result nil nil #'equal)))))

(ert-deftest pwpt-apply-subscribed-event-clears-in-flight ()
  (pwpt--with-state
   (let* ((sub  (pwpt--make-sub "tool/result"))
          (spec (pwpt--make-spec-with-subs "w1" (list sub)))
          (lstate (psi-widget-renderer-make-lstate))
          (lstate (psi-widget-renderer-lstate-set-in-flight lstate "b1" t)))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (psi-widget-projection--set-lstate "ext" "w1" lstate)
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (psi-widget-projection--apply-subscribed-event spec "tool/result" '()))
     (should-not (psi-widget-renderer--in-flight-p
                  (psi-widget-projection--get-lstate "ext" "w1") "b1")))))

(ert-deftest pwpt-apply-subscribed-event-merges-local-state-map ()
  (pwpt--with-state
   (let* ((lsm  '((:last-id . (:tool-id))))
          (sub  (pwpt--make-sub "tool/result" lsm))
          (spec (pwpt--make-spec-with-subs "w1" (list sub))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (psi-widget-projection--apply-subscribed-event
        spec "tool/result" '((:tool-id . "t99"))))
     (let* ((lstate (psi-widget-projection--get-lstate "ext" "w1"))
            (es     (plist-get lstate :event-state)))
       (should (equal "t99" (alist-get :last-id es nil nil #'equal)))))))

(ert-deftest pwpt-apply-subscribed-event-no-lsm-leaves-event-state ()
  (pwpt--with-state
   (let* ((sub    (pwpt--make-sub "tool/result"))
          (spec   (pwpt--make-spec-with-subs "w1" (list sub)))
          (lstate (psi-widget-renderer-make-lstate))
          (lstate (plist-put (copy-sequence lstate) :event-state '((:prior . "val")))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (psi-widget-projection--set-lstate "ext" "w1" lstate)
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (psi-widget-projection--apply-subscribed-event
        spec "tool/result" '((:tool-id . "t1"))))
     (let* ((ls2 (psi-widget-projection--get-lstate "ext" "w1"))
            (es  (plist-get ls2 :event-state)))
       (should (equal "val" (alist-get :prior es nil nil #'equal)))))))

(ert-deftest pwpt-apply-subscribed-event-fires-query ()
  (pwpt--with-state
   (let* ((sub  (pwpt--make-sub "tool/result"))
          (spec (pwpt--make-spec-with-subs "w1" (list sub))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((render-called nil))
       (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block)
                  (lambda () (setq render-called t))))
         (psi-widget-projection--apply-subscribed-event spec "tool/result" '()))
       (should render-called)))))

(ert-deftest pwpt-handle-event-dispatches-to-subscribed-spec ()
  (pwpt--with-state
   (let* ((lsm  '((:last-id . (:tool-id))))
          (sub  (pwpt--make-sub "tool/result" lsm))
          (spec (pwpt--make-spec-with-subs "w1" (list sub))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (psi-widget-projection-handle-event "tool/result" '((:tool-id . "t42"))))
     (let* ((lstate (psi-widget-projection--get-lstate "ext" "w1"))
            (es     (plist-get lstate :event-state)))
       (should (equal "t42" (alist-get :last-id es nil nil #'equal)))))))

(ert-deftest pwpt-handle-event-ignores-unsubscribed-event ()
  (pwpt--with-state
   (let* ((sub  (pwpt--make-sub "tool/result"))
          (spec (pwpt--make-spec-with-subs "w1" (list sub))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((render-called nil))
       (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block)
                  (lambda () (setq render-called t))))
         (psi-widget-projection-handle-event "session/updated" '()))
       (should-not render-called)))))

(ert-deftest pwpt-handle-event-noop-when-no-state ()
  (let ((psi-emacs--state nil))
    (should-not (condition-case err
                    (progn (psi-widget-projection-handle-event "tool/result" '()) nil)
                  (error err)))))

(provide 'psi-widget-projection-events-test)
;;; psi-widget-projection-events-test.el ends here
