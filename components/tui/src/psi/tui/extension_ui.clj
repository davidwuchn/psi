(ns psi.tui.extension-ui
  "Compatibility wrapper over `psi.ui.state`.

   Extension UI mutation/read logic now lives in one place under
   `psi.ui.state`; this namespace preserves the historical TUI-facing API
   while delegating directly to the shared implementation."
  (:require
   [psi.ui.state :as ui]))

(def create-ui-state ui/create-ui-state)

(def enqueue-dialog! ui/enqueue-dialog!)
(def resolve-dialog! ui/resolve-dialog!)
(def cancel-dialog! ui/cancel-dialog!)
(def active-dialog ui/active-dialog)
(def pending-dialog-count ui/pending-dialog-count)
(def dialog-queue-empty? ui/dialog-queue-empty?)

(def request-confirm! ui/request-confirm!)
(def request-select! ui/request-select!)
(def request-input! ui/request-input!)

(def set-widget! ui/set-widget!)
(def clear-widget! ui/clear-widget!)
(def widgets-by-placement ui/widgets-by-placement)
(def all-widgets ui/all-widgets)

(def set-status! ui/set-status!)
(def clear-status! ui/clear-status!)
(def all-statuses ui/all-statuses)

(def notify! ui/notify!)
(def dismiss-expired! ui/dismiss-expired!)
(def visible-notifications ui/visible-notifications)
(def dismiss-overflow! ui/dismiss-overflow!)

(def register-tool-renderer! ui/register-tool-renderer!)
(def register-message-renderer! ui/register-message-renderer!)
(def get-tool-renderer ui/get-tool-renderer)
(def get-message-renderer ui/get-message-renderer)
(def all-tool-renderers ui/all-tool-renderers)
(def all-message-renderers ui/all-message-renderers)

(def get-tools-expanded ui/get-tools-expanded)
(def set-tools-expanded! ui/set-tools-expanded!)

(def clear-all! ui/clear-all!)
(def create-ui-context ui/create-ui-context)
(def snapshot ui/snapshot)
(def snapshot-state ui/snapshot-state)
