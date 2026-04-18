Goal: continue the auto-session-name extension from the landed MVP toward stronger model selection and runtime contracts.

Context:
- the extension already schedules rename checkpoints, reads journal-backed source entries, runs a helper child session, and applies validated inferred titles
- current guards prevent recursion, stale checkpoints, and overwrite of diverged manually changed names
- remaining work includes helper model selection, formal source metadata, non-user-facing helper session contracts, and possible persistence/reload semantics

Acceptance:
- helper model selection is driven by the intended model-selection hierarchy
- manual vs auto naming semantics become more explicit if needed
- helper/internal runs are clearly non-user-facing by runtime contract if that slice is chosen
