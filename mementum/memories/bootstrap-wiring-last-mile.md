❌ bootstrap-wiring-last-mile

Built 7 clean slices for custom providers with full test coverage — but `/model local gemma-4-31b` returned "Unknown model" because `model-registry/init!` was never called during startup. The registry's lazy fallback to built-ins meant tests passed without init, hiding the integration gap. Lesson: when adding a new subsystem with its own `init!`, wire it into the lifecycle immediately — don't leave it as "remaining work." The entry point was `app-runtime/create-runtime-session-context`, the earliest common path for all UI modes (console, TUI, RPC/Emacs).
