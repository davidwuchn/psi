(ns psi.app.main
  "PSI Application Entry Point - orchestrates UI selection and session startup.
   
   Proper architecture:
   - This component depends on both agent-session and TUI
   - Agent-session contains pure session logic (no UI dependencies)
   - TUI contains pure UI logic 
   - This component bridges them together based on command line args
   
   Breaks circular dependency: tui ↔ agent-session"
  (:require
   [psi.agent-session.main :as session-main]
   [psi.tui.app :as tui-app])
  (:gen-class))

;; ============================================================
;; Command line argument parsing
;; ============================================================

(defn- has-flag?
  "Check if a command line flag is present"
  [args flag]
  (some #(= flag %) args))

;; ============================================================ 
;; Interface Selection and Startup
;; ============================================================

(defn -main
  "PSI Application Entry Point.
   
   Selects interface based on command line flags and delegates to appropriate startup:
   --tui      : Terminal User Interface  
   --rpc-edn  : RPC EDN Protocol
   (default)  : Console Interface
   
   All other flags are passed through to the session layer."
  [& args]
  (let [tui?     (has-flag? args "--tui")
        rpc-edn? (has-flag? args "--rpc-edn")]

    (cond
      tui?
      ;; Launch TUI interface - this is where the circular dependency was
      (session-main/run-tui-session-with-interface!
       tui-app/start!  ; Pass TUI interface as dependency injection
       args)

      rpc-edn?
      ;; Launch RPC interface
      (session-main/run-rpc-interface! args)

      :else
      ;; Launch console interface  
      (session-main/run-console-session! args))

    ;; Explicit exit to handle non-daemon threads
    (System/exit 0)))