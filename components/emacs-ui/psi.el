;;; psi.el --- Emacs process-buffer frontend  -*- lexical-binding: t; -*-

;; Copyright (C)

;;; Commentary:
;; Dedicated psi chat buffer with one owned subprocess.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'ansi-color)
(require 'json)
(require 'psi-rpc)

(defgroup psi-emacs nil
  "psi Emacs frontend."
  :group 'applications)

(defcustom psi-emacs-command '("clojure" "-M:psi" "--rpc-edn")
  "Command used to start the owned psi rpc-edn subprocess."
  :type '(repeat string)
  :safe (lambda (value)
          (and (listp value)
               (cl-every #'stringp value)))
  :group 'psi-emacs)

(defcustom psi-emacs-buffer-name "*psi*"
  "Default dedicated buffer name for psi Emacs frontend."
  :type 'string
  :group 'psi-emacs)

(defcustom psi-emacs-working-directory nil
  "Working directory used to launch the psi subprocess.

When nil, psi uses the `default-directory' where `psi-emacs-start' (or
`psi-emacs-open-buffer') is invoked."
  :type '(choice (const :tag "Use invocation directory" nil)
                 directory)
  :group 'psi-emacs)

(defcustom psi-emacs-stream-timeout-seconds 120
  "Seconds without streaming progress before watchdog timeout triggers.

Used to detect stalled streaming runs and transition to deterministic recovery."
  :type 'number
  :group 'psi-emacs)

(defcustom psi-emacs-notification-timeout-seconds 5
  "Seconds before a projected extension notification auto-dismisses."
  :type 'number
  :group 'psi-emacs)

(cl-defstruct psi-emacs-state
  process
  process-state
  transport-state
  run-state
  last-stream-progress-at
  stream-watchdog-timer
  last-error
  error-line-range
  assistant-in-progress
  assistant-range
  thinking-in-progress
  thinking-range
  thinking-archived-ranges
  tool-rows
  projection-widgets
  projection-widget-specs
  projection-widget-lstates
  projection-widget-data
  projection-statuses
  projection-footer
  projection-notifications
  projection-notification-seq
  projection-notification-timers
  projection-range
  regions
  region-seq
  active-assistant-id
  active-thinking-id
  input-separator-marker
  draft-anchor
  input-history
  input-history-index
  input-history-stash
  rpc-client
  tool-output-view-mode
  session-id
  session-phase
  session-is-streaming
  session-is-compacting
  session-pending-message-count
  session-retry-attempt
  session-interrupt-pending
  session-model-provider
  session-model-id
  session-model-reasoning
  session-thinking-level
  session-effective-reasoning-effort
  header-model-label
  extension-command-names
  transcript-hydrated?
  context-snapshot
  pending-frontend-action)

(defvar psi-emacs--spawn-process-function #'psi-emacs--default-spawn-process
  "Function used to spawn a psi subprocess.

The function receives one argument COMMAND (list of strings) and must return
an Emacs process object.")

(defvar psi-emacs--send-request-function #'psi-emacs--default-send-request
  "Function used by frontend commands to send RPC requests.

Called as (STATE OP PARAMS CALLBACK).")

(defvar psi-emacs--slash-command-handler-function #'psi-emacs--default-handle-slash-command
  "Function used to handle slash command input.

Called as (STATE MESSAGE). Return non-nil when MESSAGE was handled and should
not fall through to normal prompt dispatch.")

(defvar psi-emacs--state-by-buffer (make-hash-table :test #'eq)
  "Map dedicated buffers to their `psi-emacs-state'.")

(defvar-local psi-emacs--state nil
  "Buffer-local frontend state for psi chat buffers.")

(defvar-local psi-emacs--owned-process nil
  "Buffer-local owned subprocess for this dedicated psi buffer.")

;;;###autoload (autoload 'psi-emacs-start "psi-entry" "Start a dedicated psi buffer/session." t)
;;;###autoload (autoload 'psi-emacs-project "psi-entry" "Start or switch to a project-scoped psi buffer/session." t)

(require 'psi-run-state)
(require 'psi-regions)
(require 'psi-assistant-render)
(require 'psi-tool-rows)
(require 'psi-session-commands)
(require 'psi-projection)
(require 'psi-dialogs)
(require 'psi-events)
(require 'psi-lifecycle)
(require 'psi-compose)
(require 'psi-mode)
(require 'psi-entry)

(provide 'psi)

;;; psi.el ends here
