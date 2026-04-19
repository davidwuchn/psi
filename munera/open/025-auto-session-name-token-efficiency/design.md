Goal: make the auto-session-name extension more token efficient while preserving current rename behavior.

Context:
- the auto-session-name extension currently creates a helper child session to infer a session title from recent conversation content
- the current implementation should be tightened so the helper prompt uses less context and less prompt overhead
- this is a follow-on optimization task for the landed auto-session-name vertical slice

Constraints:
- keep the existing extension behavior centered on inferred session naming rather than broadening scope
- reduce prompt overhead by using a minimal system prompt for the helper run
- bound helper input so it considers at most the last 4k of the conversation
- preserve existing safety semantics around recursion, stale checkpoints, and manual-name overwrite protection unless a change is explicitly justified

Acceptance:
- helper naming inference runs with a minimal system prompt
- helper naming inference only considers at most the last 4k of conversation content
- tests cover the new prompt-shaping/token-budget behavior
- docs or task notes capture any important trade-offs in truncation strategy or prompt contract
