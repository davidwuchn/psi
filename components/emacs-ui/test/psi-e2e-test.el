;;; psi-e2e-test.el --- End-to-end harness for psi Emacs frontend -*- lexical-binding: t; -*-

(require 'cl-lib)
(require 'subr-x)

(add-to-list 'load-path
             (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name))))
(require 'psi)

(defconst psi-e2e-timeout-seconds 60
  "Maximum seconds to wait for each asynchronous e2e step.")

(defun psi-e2e--wait-for (pred timeout-seconds)
  "Poll PRED until non-nil or TIMEOUT-SECONDS elapse."
  (let ((deadline (+ (float-time) timeout-seconds))
        (done nil))
    (while (and (not done)
                (< (float-time) deadline))
      (setq done (condition-case _
                     (funcall pred)
                   (error nil)))
      (unless done
        (accept-process-output nil 0.05)
        (sleep-for 0.05)))
    done))

(defun psi-e2e--buffer-snapshot (buffer)
  "Return plain-text snapshot for BUFFER."
  (if (buffer-live-p buffer)
      (with-current-buffer buffer
        (buffer-substring-no-properties (point-min) (point-max)))
    "<buffer-not-live>"))

(defun psi-e2e--input-focused-p (buffer)
  "Return non-nil when BUFFER point is inside focused compose input area."
  (and (buffer-live-p buffer)
       (with-current-buffer buffer
         (and psi-emacs--state
              (let* ((range (psi-emacs--input-range))
                     (start (car range))
                     (end (cdr range))
                     (pt (point)))
                (and (<= start pt)
                     (<= pt end)
                     (= pt (psi-emacs--draft-end-position))))))))

(defun psi-e2e--footer-displayed-p (buffer)
  "Return non-nil when BUFFER currently renders a non-empty projection footer."
  (and (buffer-live-p buffer)
       (with-current-buffer buffer
         (let ((footer (and psi-emacs--state
                            (psi-emacs-state-projection-footer psi-emacs--state))))
           (and (stringp footer)
                (not (string-empty-p footer))
                (string-match-p (regexp-quote (substring-no-properties footer))
                                (psi-e2e--buffer-snapshot buffer)))))))

(defun psi-e2e--separator-position ()
  "Return input separator position when marker is valid, else nil."
  (when (and psi-emacs--state
             (psi-emacs--input-separator-marker-valid-p))
    (marker-position (psi-emacs--input-separator-marker-cache))))

(defun psi-e2e--projection-read-only-position ()
  "Return a stable interior position for projection/footer read-only checks.

Returns nil when no projection region is present."
  (when-let ((bounds (and psi-emacs--state
                          (psi-emacs--region-bounds 'projection 'main))))
    (let ((start-pos (car bounds))
          (end-pos (cdr bounds)))
      (when (< start-pos end-pos)
        (if (> (- end-pos start-pos) 1)
            (1- end-pos)
          start-pos)))))

(defun psi-e2e--assert-read-only-at (pos label)
  "Assert current buffer rejects edits at POS with text-read-only for LABEL."
  (let ((inserted nil))
    (save-excursion
      (goto-char pos)
      (unless (condition-case _
                  (progn
                    (insert "x")
                    (setq inserted t)
                    nil)
                (text-read-only t))
        (when inserted
          (delete-char -1))
        (error "%s is editable at position %s" label pos)))))

(defun psi-e2e--assert-non-input-read-only (buffer)
  "Assert transcript/footer non-input areas are read-only.

Separator is editable at the exact boundary position by design, so this check
verifies the position just before separator start (transcript side)."
  (with-current-buffer buffer
    (let* ((separator-pos (psi-e2e--separator-position))
           (separator-transcript-pos (and (integerp separator-pos)
                                          (> separator-pos (point-min))
                                          (1- separator-pos)))
           (projection-pos (psi-e2e--projection-read-only-position))
           (checks (delq nil
                         (list (cons (point-min) "non-input transcript area")
                               (and separator-transcript-pos
                                    (cons separator-transcript-pos "rules/transcript side area"))
                               (and projection-pos
                                    (cons projection-pos "footer/projection area"))))))
      (dolist (check checks)
        (psi-e2e--assert-read-only-at (car check) (cdr check))))))

(defun psi-e2e--candidate-labels (collection)
  "Return display labels extracted from completing-read COLLECTION."
  (mapcar (lambda (item)
            (if (consp item)
                (car item)
              item))
          (cond
           ((vectorp collection) (append collection nil))
           ((listp collection) collection)
           (t nil))))

(defun psi-e2e--auto-select-frontend-choice (prompt collection)
  "Return deterministic completing-read choice for frontend-action PROMPT.

Prefers a stable thinking-level selection when available; otherwise returns the
first candidate label." 
  (let ((labels (delq nil (psi-e2e--candidate-labels collection))))
    (cond
     ((and (stringp prompt)
           (string-match-p "thinking level" (downcase prompt))
           (member "high" labels))
      "high")
     ((consp labels)
      (car labels)))))

(defun psi-e2e-run ()
  "Run baseline Emacs UI end-to-end scenario.

Scenario:
1. launch frontend + wait for rpc transport ready
2. assert focused input, visible footer, and read-only non-input areas
3. send `/history` and assert backend history output appears
4. send `/thinking`, auto-complete backend-requested frontend action, and assert result
5. send `/quit` and assert buffer exits"
  (let ((psi-emacs-command '("clojure" "-M:psi" "--rpc-edn"))
        (psi-emacs-working-directory default-directory)
        (buffer nil)
        (frontend-prompts nil)
        (ok t)
        (failure nil))
    (condition-case err
        (cl-letf (((symbol-function 'completing-read)
                   (lambda (prompt collection &rest _args)
                     (push prompt frontend-prompts)
                     (or (psi-e2e--auto-select-frontend-choice prompt collection)
                         (error "no frontend-action choice available for prompt: %s" prompt)))))
          (progn
            (setq buffer (psi-emacs-open-buffer "*psi-e2e*"))
            (unless (psi-e2e--wait-for
                     (lambda ()
                       (and (buffer-live-p buffer)
                            (with-current-buffer buffer
                              (and psi-emacs--state
                                   (eq (psi-emacs-state-transport-state psi-emacs--state) 'ready)))))
                     psi-e2e-timeout-seconds)
              (error "transport not ready within timeout"))

            (unless (psi-e2e--wait-for (lambda () (psi-e2e--footer-displayed-p buffer))
                                       psi-e2e-timeout-seconds)
              (error "footer not displayed within timeout; snapshot:\n%s"
                     (psi-e2e--buffer-snapshot buffer)))

            (unless (psi-e2e--input-focused-p buffer)
              (error "input area is not focused after startup; snapshot:\n%s"
                     (psi-e2e--buffer-snapshot buffer)))

            (psi-e2e--assert-non-input-read-only buffer)

            (with-current-buffer buffer
              (psi-emacs--replace-input-text "/history")
              (psi-emacs-send-from-buffer nil))

            (unless (psi-e2e--wait-for
                     (lambda ()
                       (and (buffer-live-p buffer)
                            (with-current-buffer buffer
                              (string-match-p "Message history" (buffer-string)))))
                     psi-e2e-timeout-seconds)
              (error "did not observe history output; snapshot:\n%s"
                     (psi-e2e--buffer-snapshot buffer)))

            (unless (psi-e2e--input-focused-p buffer)
              (error "input area lost focus after /history; snapshot:\n%s"
                     (psi-e2e--buffer-snapshot buffer)))

            (psi-e2e--assert-non-input-read-only buffer)

            (with-current-buffer buffer
              (psi-emacs--replace-input-text "/thinking")
              (psi-emacs-send-from-buffer nil))

            (unless (psi-e2e--wait-for
                     (lambda ()
                       (and (buffer-live-p buffer)
                            (with-current-buffer buffer
                              (and (string-match-p "Thinking level set to high" (buffer-string))
                                   (null (psi-emacs-state-pending-frontend-action psi-emacs--state))))))
                     psi-e2e-timeout-seconds)
              (error "did not observe /thinking frontend-action completion; snapshot:\n%s"
                     (psi-e2e--buffer-snapshot buffer)))

            (unless (cl-some (lambda (prompt)
                               (and (stringp prompt)
                                    (string-match-p "Select a thinking level" prompt)))
                             frontend-prompts)
              (error "did not observe thinking-picker frontend prompt; prompts=%S"
                     (nreverse frontend-prompts)))

            (unless (psi-e2e--input-focused-p buffer)
              (error "input area lost focus after /thinking; snapshot:\n%s"
                     (psi-e2e--buffer-snapshot buffer)))

            (psi-e2e--assert-non-input-read-only buffer)

            (when (buffer-live-p buffer)
              (with-current-buffer buffer
                (psi-emacs--replace-input-text "/quit")
                (psi-emacs-send-from-buffer nil)))

            (unless (psi-e2e--wait-for (lambda () (not (buffer-live-p buffer))) 5)
              (error "buffer did not close after /quit"))))
      (error
       (setq ok nil)
       (setq failure (error-message-string err))))

    (when (buffer-live-p buffer)
      (kill-buffer buffer))

    (if ok
        (progn
          (princ "psi-emacs-e2e:ok\n")
          (kill-emacs 0))
      (progn
        (princ (format "psi-emacs-e2e:fail:%s\n" (or failure "unknown failure")))
        (kill-emacs 1)))))

(provide 'psi-e2e-test)

;;; psi-e2e-test.el ends here
