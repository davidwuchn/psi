;;; psi-widget-projection-test.el --- Tests for psi-widget-projection  -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi)
(require 'psi-widget-renderer)
(require 'psi-widget-projection)

;;; Helpers

(defun pwpt--make-spec (id &optional root-node)
  "Build a minimal widget spec alist."
  `((:id . ,id)
    (:extension-id . "ext")
    (:spec . ,(or root-node
                  `((:type . text) (:content . ,id))))))

(defun pwpt--make-button-spec (id key)
  "Build a spec with a single button node."
  `((:id . ,id)
    (:extension-id . "ext")
    (:spec . ((:type . button)
              (:label . "Go")
              (:key . ,key)
              (:disabled . nil)
              (:mutation . ((:name . ext/do-thing)
                            (:params . ((:widget . ,id)))))))))

(defun pwpt--make-collapsible-spec (id key &optional initially-expanded)
  "Build a spec with a collapsible root node."
  `((:id . ,id)
    (:extension-id . "ext")
    (:spec . ((:type . collapsible)
              (:title . "Section")
              (:key . ,key)
              (:initially-expanded . ,(or initially-expanded :false))
              (:children . (((:type . text) (:content . "inner"))))))))

(defmacro pwpt--with-state (&rest body)
  "Run BODY with a fresh psi-emacs-state bound as psi-emacs--state."
  `(let ((psi-emacs--state (psi-emacs--initialize-state nil)))
     ,@body))

(defun pwpt--capture-query-sends (thunk)
  "Run THUNK, capture (op params) pairs sent via send-request-function."
  (let ((calls nil))
    (cl-letf (((symbol-value 'psi-emacs--send-request-function)
               (lambda (_state op params &optional _cb)
                 (push (list op params) calls))))
      (funcall thunk))
    (nreverse calls)))

(defun pwpt--capture-query-sends-with-callback (thunk)
  "Run THUNK, capture (op params callback) triples."
  (let ((calls nil))
    (cl-letf (((symbol-value 'psi-emacs--send-request-function)
               (lambda (_state op params &optional cb)
                 (push (list op params cb) calls))))
      (funcall thunk))
    (nreverse calls)))

;;; ─── psi-widget-projection-request-specs ────────────────────────────────────

(ert-deftest pwpt-request-specs-sends-query-eql ()
  (pwpt--with-state
   (let ((calls (pwpt--capture-query-sends
                 #'psi-widget-projection-request-specs)))
     (should (= 1 (length calls)))
     (should (equal "query_eql" (car (car calls))))
     (let ((params (cadr (car calls))))
       (should (string-match-p "psi.ui/widget-specs" (cdr (assq :query params))))))))

(ert-deftest pwpt-request-specs-noop-when-no-state ()
  (let ((psi-emacs--state nil))
    (let ((calls (pwpt--capture-query-sends
                  #'psi-widget-projection-request-specs)))
      (should (= 0 (length calls))))))

(ert-deftest pwpt-request-specs-noop-when-no-send-function ()
  (pwpt--with-state
   (let ((psi-emacs--send-request-function nil))
     ;; Should not error
     (should-not (condition-case err
                     (progn (psi-widget-projection-request-specs) nil)
                   (error err))))))

;;; ─── lstate management ───────────────────────────────────────────────────────

(ert-deftest pwpt-sync-lstates-inits-new-specs ()
  (pwpt--with-state
   (let ((spec (pwpt--make-spec "w1")))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((lstates (psi-emacs-state-projection-widget-lstates psi-emacs--state)))
       (should (hash-table-p lstates))
       (should (not (null (gethash "ext/w1" lstates))))))))

(ert-deftest pwpt-sync-lstates-preserves-existing-lstate ()
  (pwpt--with-state
   (let* ((spec   (pwpt--make-collapsible-spec "w1" "s1" nil))
          (_      (psi-widget-projection--sync-lstates (list spec)))
          (lstates (psi-emacs-state-projection-widget-lstates psi-emacs--state))
          ;; Manually modify lstate
          (lstate (gethash "ext/w1" lstates))
          (lstate (psi-widget-renderer-lstate-set-collapsed lstate "s1" nil)))
     (puthash "ext/w1" lstate lstates)
     ;; Re-sync — existing lstate should be preserved
     (psi-widget-projection--sync-lstates (list spec))
     (let* ((lstates2 (psi-emacs-state-projection-widget-lstates psi-emacs--state))
            (lstate2  (gethash "ext/w1" lstates2)))
       (should-not (psi-widget-renderer--collapsed-p lstate2 "s1"))))))

(ert-deftest pwpt-sync-lstates-removes-stale-specs ()
  (pwpt--with-state
   (let ((spec1 (pwpt--make-spec "w1"))
         (spec2 (pwpt--make-spec "w2")))
     (psi-widget-projection--sync-lstates (list spec1 spec2))
     ;; Remove spec2
     (psi-widget-projection--sync-lstates (list spec1))
     (let ((lstates (psi-emacs-state-projection-widget-lstates psi-emacs--state)))
       (should     (gethash "ext/w1" lstates))
       (should-not (gethash "ext/w2" lstates))))))

(ert-deftest pwpt-get-lstate-returns-fresh-when-missing ()
  (pwpt--with-state
   (let ((lstate (psi-widget-projection--get-lstate "ext" "unknown")))
     (should (listp lstate))
     (should (hash-table-p (plist-get lstate :collapsed-keys))))))

(ert-deftest pwpt-set-lstate-stores-and-retrieves ()
  (pwpt--with-state
   (let* ((lstate (psi-widget-renderer-make-lstate))
          (lstate (psi-widget-renderer-lstate-set-collapsed lstate "k1" t)))
     (psi-widget-projection--set-lstate "ext" "w1" lstate)
     (let ((retrieved (psi-widget-projection--get-lstate "ext" "w1")))
       (should (psi-widget-renderer--collapsed-p retrieved "k1"))))))

;;; ─── psi-widget-projection-render-specs ─────────────────────────────────────

(ert-deftest pwpt-render-specs-returns-empty-when-no-specs ()
  (pwpt--with-state
   (should (equal "" (psi-widget-projection-render-specs psi-emacs--state)))))

(ert-deftest pwpt-render-specs-renders-registered-spec ()
  (pwpt--with-state
   (let ((spec (pwpt--make-spec "w1" '((:type . text) (:content . "hello from spec")))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((result (psi-widget-projection-render-specs psi-emacs--state)))
       (should (string-match-p "hello from spec" result))))))

(ert-deftest pwpt-render-specs-renders-multiple-specs-separated ()
  (pwpt--with-state
   (let ((spec1 (pwpt--make-spec "w1" '((:type . text) (:content . "first"))))
         (spec2 (pwpt--make-spec "w2" '((:type . text) (:content . "second")))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state)
           (list spec1 spec2))
     (psi-widget-projection--sync-lstates (list spec1 spec2))
     (let ((result (psi-widget-projection-render-specs psi-emacs--state)))
       (should (string-match-p "first" result))
       (should (string-match-p "second" result))))))

;;; ─── Collapsible toggle handler ──────────────────────────────────────────────

(ert-deftest pwpt-collapsible-toggle-flips-collapsed-state ()
  (pwpt--with-state
   (let* ((spec (pwpt--make-collapsible-spec "w1" "s1" nil)))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     ;; Initially collapsed
     (should (psi-widget-renderer--collapsed-p
              (psi-widget-projection--get-lstate "ext" "w1") "s1"))
     ;; Toggle — should expand (no upsert needed, just lstate check)
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (psi-widget-projection--on-collapsible-toggle "ext/w1" "s1"))
     (should-not (psi-widget-renderer--collapsed-p
                  (psi-widget-projection--get-lstate "ext" "w1") "s1"))
     ;; Toggle again — should collapse
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (psi-widget-projection--on-collapsible-toggle "ext/w1" "s1"))
     (should (psi-widget-renderer--collapsed-p
              (psi-widget-projection--get-lstate "ext" "w1") "s1")))))

(ert-deftest pwpt-collapsible-toggle-does-not-send-rpc ()
  (pwpt--with-state
   (let* ((spec (pwpt--make-collapsible-spec "w1" "s1" nil)))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((calls (pwpt--capture-query-sends
                   (lambda ()
                     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
                       (psi-widget-projection--on-collapsible-toggle "ext/w1" "s1"))))))
       (should (= 0 (length calls)))))))

;;; ─── Button activation handler ───────────────────────────────────────────────

(ert-deftest pwpt-button-activate-marks-in-flight ()
  (pwpt--with-state
   (let* ((spec (pwpt--make-button-spec "w1" "b1")))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (pwpt--capture-query-sends
      (lambda ()
        (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
          (psi-widget-projection--on-button-activate "ext/w1" "b1"))))
     (should (psi-widget-renderer--in-flight-p
              (psi-widget-projection--get-lstate "ext" "w1") "b1")))))

(ert-deftest pwpt-button-activate-sends-query-eql-mutation ()
  (pwpt--with-state
   (let* ((spec (pwpt--make-button-spec "w1" "b1")))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((calls (pwpt--capture-query-sends
                   (lambda ()
                     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
                       (psi-widget-projection--on-button-activate "ext/w1" "b1"))))))
       (should (= 1 (length calls)))
       (should (equal "query_eql" (car (car calls))))
       (let ((query (cdr (assq :query (cadr (car calls))))))
         (should (string-match-p "ext/do-thing" query)))))))

(ert-deftest pwpt-button-activate-noop-when-already-in-flight ()
  (pwpt--with-state
   (let* ((spec   (pwpt--make-button-spec "w1" "b1"))
          (lstate (psi-widget-renderer-make-lstate))
          (lstate (psi-widget-renderer-lstate-set-in-flight lstate "b1" t)))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (psi-widget-projection--set-lstate "ext" "w1" lstate)
     (let ((calls (pwpt--capture-query-sends
                   (lambda ()
                     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
                       (psi-widget-projection--on-button-activate "ext/w1" "b1"))))))
       (should (= 0 (length calls)))))))

(ert-deftest pwpt-button-activate-response-clears-in-flight ()
  (pwpt--with-state
   (let* ((spec (pwpt--make-button-spec "w1" "b1")))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((captured-cb nil))
       (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                  (lambda (_state _op _params &optional cb)
                    (setq captured-cb cb)))
                 ((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
         (psi-widget-projection--on-button-activate "ext/w1" "b1"))
       ;; In-flight now
       (should (psi-widget-renderer--in-flight-p
                (psi-widget-projection--get-lstate "ext" "w1") "b1"))
       ;; Simulate response
       (when captured-cb
         (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
           (funcall captured-cb '((:data . ((:ok . t)))))))
       ;; In-flight cleared
       (should-not (psi-widget-renderer--in-flight-p
                    (psi-widget-projection--get-lstate "ext" "w1") "b1"))))))

(ert-deftest pwpt-button-activate-noop-when-disabled ()
  (pwpt--with-state
   (let* ((spec `((:id . "w1") (:extension-id . "ext")
                  (:spec . ((:type . button) (:label . "Go") (:key . "b1")
                            (:disabled . t)
                            (:mutation . ((:name . ext/do-thing))))))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((calls (pwpt--capture-query-sends
                   (lambda ()
                     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
                       (psi-widget-projection--on-button-activate "ext/w1" "b1"))))))
       (should (= 0 (length calls)))))))

;;; ─── Widget ID parsing ───────────────────────────────────────────────────────

(ert-deftest pwpt-parse-widget-id-splits-correctly ()
  (should (equal '("ext" . "w1")
                 (psi-widget-projection--parse-widget-id "ext/w1")))
  (should (equal '("my-ext" . "agent-chain")
                 (psi-widget-projection--parse-widget-id "my-ext/agent-chain"))))

(ert-deftest pwpt-parse-widget-id-returns-nil-without-slash ()
  (should (null (psi-widget-projection--parse-widget-id "nowidgetid")))
  (should (null (psi-widget-projection--parse-widget-id nil))))

;;; ─── EDN encoding ────────────────────────────────────────────────────────────

(ert-deftest pwpt-edn-encode-primitives ()
  (should (equal "nil"    (psi-widget-projection--edn-encode nil)))
  (should (equal "true"   (psi-widget-projection--edn-encode t)))
  (should (equal "false"  (psi-widget-projection--edn-encode :false)))
  (should (equal "42"     (psi-widget-projection--edn-encode 42)))
  (should (equal ":foo"   (psi-widget-projection--edn-encode :foo)))
  (should (equal "foo"    (psi-widget-projection--edn-encode 'foo))))

(ert-deftest pwpt-edn-encode-string ()
  (should (equal "\"hello\"" (psi-widget-projection--edn-encode "hello"))))

(ert-deftest pwpt-edn-encode-alist-as-map ()
  (let ((result (psi-widget-projection--edn-encode '((:a . 1) (:b . 2)))))
    (should (string-match-p ":a 1" result))
    (should (string-match-p ":b 2" result))
    (should (string-prefix-p "{" result))
    (should (string-suffix-p "}" result))))

(ert-deftest pwpt-edn-encode-vector ()
  (should (equal "[1 2 3]"
                 (psi-widget-projection--edn-encode [1 2 3]))))

;;; ─── Node lookup ─────────────────────────────────────────────────────────────

(ert-deftest pwpt-find-node-by-key-finds-root ()
  (let ((node '((:type . button) (:key . "b1") (:label . "Go"))))
    (should (equal node
                   (psi-widget-projection--find-node-by-key node "b1")))))

(ert-deftest pwpt-find-node-by-key-finds-nested ()
  (let* ((child '((:type . button) (:key . "b1") (:label . "Go")))
         (root  `((:type . vstack) (:children . (,child)))))
    (should (equal child
                   (psi-widget-projection--find-node-by-key root "b1")))))

(ert-deftest pwpt-find-node-by-key-returns-nil-when-missing ()
  (let ((node '((:type . text) (:key . "t1") (:content . "x"))))
    (should (null (psi-widget-projection--find-node-by-key node "missing")))))

;;; ─── Per-spec query data ─────────────────────────────────────────────────────

(defun pwpt--make-spec-with-query (id query-vec &optional root-node)
  "Build a spec alist with :query set to QUERY-VEC."
  `((:id . ,id)
    (:extension-id . "ext")
    (:query . ,query-vec)
    (:spec . ,(or root-node `((:type . text) (:content . ,id))))))

(ert-deftest pwpt-spec-key-formats-correctly ()
  (let ((spec (pwpt--make-spec "w1")))
    (should (equal "ext/w1"
                   (psi-widget-projection--spec-key spec)))))

(ert-deftest pwpt-spec-query-string-nil-when-no-query ()
  (let ((spec (pwpt--make-spec "w1")))
    (should (null (psi-widget-projection--spec-query-string spec)))))

(ert-deftest pwpt-spec-query-string-nil-when-empty-query ()
  (let ((spec (pwpt--make-spec-with-query "w1" [])))
    (should (null (psi-widget-projection--spec-query-string spec)))))

(ert-deftest pwpt-spec-query-string-formats-keywords ()
  (let* ((spec   (pwpt--make-spec-with-query "w1" [:psi.agent-chain/chains]))
         (result (psi-widget-projection--spec-query-string spec)))
    (should (stringp result))
    (should (string-prefix-p "[" result))
    (should (string-suffix-p "]" result))
    (should (string-match-p "psi.agent-chain/chains" result))))

(ert-deftest pwpt-spec-query-string-formats-multiple-keywords ()
  (let* ((spec   (pwpt--make-spec-with-query "w1" [:psi.agent-chain/chains :psi.agent-chain/count]))
         (result (psi-widget-projection--spec-query-string spec)))
    (should (string-match-p "psi.agent-chain/chains" result))
    (should (string-match-p "psi.agent-chain/count" result))))

(ert-deftest pwpt-get-set-spec-data-roundtrip ()
  (pwpt--with-state
   (let ((data '((:psi.agent-chain/count . 3))))
     (psi-widget-projection--set-spec-data "ext/w1" data)
     (should (equal data (psi-widget-projection--get-spec-data "ext/w1"))))))

(ert-deftest pwpt-get-spec-data-nil-when-absent ()
  (pwpt--with-state
   (should (null (psi-widget-projection--get-spec-data "ext/missing")))))

(ert-deftest pwpt-fetch-spec-data-noop-when-no-query ()
  "Spec with no :query fires no query_eql, renders immediately."
  (pwpt--with-state
   (let* ((spec  (pwpt--make-spec "w1"))
          (render-called nil))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block)
                (lambda () (setq render-called t))))
       (let ((calls (pwpt--capture-query-sends
                     (lambda () (psi-widget-projection--fetch-spec-data (list spec))))))
         (should (= 0 (length calls)))
         (should render-called))))))

(ert-deftest pwpt-fetch-spec-data-sends-query-for-spec-with-query ()
  (pwpt--with-state
   (let* ((spec (pwpt--make-spec-with-query "w1" [:psi.agent-chain/chains])))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (let ((calls (pwpt--capture-query-sends
                     (lambda () (psi-widget-projection--fetch-spec-data (list spec))))))
         (should (= 1 (length calls)))
         (should (equal "query_eql" (car (car calls))))
         (let ((query (cdr (assq :query (cadr (car calls))))))
           (should (string-match-p "psi.agent-chain/chains" query))))))))

(ert-deftest pwpt-fetch-spec-data-stores-result-and-rerenders ()
  (pwpt--with-state
   (let* ((spec         (pwpt--make-spec-with-query "w1" [:psi.agent-chain/count]))
          (render-count 0)
          (captured-cb  nil))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (cl-letf (((symbol-value 'psi-emacs--send-request-function)
                (lambda (_state _op _params &optional cb) (setq captured-cb cb)))
               ((symbol-function 'psi-emacs--upsert-projection-block)
                (lambda () (cl-incf render-count))))
       (psi-widget-projection--fetch-spec-data (list spec)))
     ;; Simulate response
     (when captured-cb
       (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block)
                  (lambda () (cl-incf render-count))))
         (funcall captured-cb
                  '((:data . ((:result . ((:psi.agent-chain/count . 5)))))))))
     (should (equal '((:psi.agent-chain/count . 5))
                    (psi-widget-projection--get-spec-data "ext/w1")))
     (should (> render-count 0)))))

(ert-deftest pwpt-fetch-spec-data-multiple-specs-fires-per-spec ()
  "Two specs with queries → two query_eql sends."
  (pwpt--with-state
   (let* ((spec1 (pwpt--make-spec-with-query "w1" [:psi.agent-chain/chains]))
          (spec2 (pwpt--make-spec-with-query "w2" [:psi.agent-chain/count])))
     (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block) #'ignore))
       (let ((calls (pwpt--capture-query-sends
                     (lambda ()
                       (psi-widget-projection--fetch-spec-data (list spec1 spec2))))))
         (should (= 2 (length calls))))))))

(ert-deftest pwpt-render-specs-passes-data-to-renderer ()
  "render-specs passes stored data for content-path resolution."
  (pwpt--with-state
   (let* ((spec (pwpt--make-spec-with-query
                 "w1" [:psi.agent-chain/count]
                 '((:type . text) (:content-path . (:psi.agent-chain/count))))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (psi-widget-projection--set-spec-data "ext/w1" '((:psi.agent-chain/count . 42)))
     (let ((result (substring-no-properties
                    (psi-widget-projection-render-specs psi-emacs--state))))
       (should (string-match-p "42" result))))))

(ert-deftest pwpt-render-specs-nil-data-when-no-query ()
  "render-specs passes nil data when no query result stored."
  (pwpt--with-state
   (let* ((spec (pwpt--make-spec "w1" '((:type . text) (:content . "static")))))
     (setf (psi-emacs-state-projection-widget-specs psi-emacs--state) (list spec))
     (psi-widget-projection--sync-lstates (list spec))
     (let ((result (substring-no-properties
                    (psi-widget-projection-render-specs psi-emacs--state))))
       (should (string-match-p "static" result))))))

;;; ─── Event subscriptions ─────────────────────────────────────────────────────

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

;;; specs-subscribed-to

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

;;; merge-event-payload

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

;;; apply-subscribed-event

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
  "When sub has no local-state-map, event-state is unchanged."
  (pwpt--with-state
   (let* ((sub    (pwpt--make-sub "tool/result"))   ; no lsm
          (spec   (pwpt--make-spec-with-subs "w1" (list sub)))
          (lstate (psi-widget-renderer-make-lstate))
          ;; Pre-set event-state
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
     ;; No :query → fetch-spec-data triggers immediate render, no query_eql
     (let ((render-called nil))
       (cl-letf (((symbol-function 'psi-emacs--upsert-projection-block)
                  (lambda () (setq render-called t))))
         (psi-widget-projection--apply-subscribed-event spec "tool/result" '()))
       (should render-called)))))

;;; handle-event

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

(provide 'psi-widget-projection-test)
;;; psi-widget-projection-test.el ends here
