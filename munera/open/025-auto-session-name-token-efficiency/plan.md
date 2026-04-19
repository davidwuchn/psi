Approach:
- Treat this as a focused prompt-shaping optimization for the existing auto-session-name helper flow.
- Inspect the current helper-session request construction, then reduce fixed prompt overhead and introduce a strict conversation-window cap.
- Keep the behavior change local to the extension unless the runtime already provides a better reusable truncation helper.

Likely steps:
1. inspect how the auto-session-name extension currently builds helper prompt messages
2. implement a minimal system prompt contract for rename inference
3. cap the helper conversation input to at most the last 4k of conversation content
4. update or add tests for prompt shaping and truncation behavior
5. record any notable truncation/prompt trade-offs in implementation notes or docs

Risks:
- over-truncating context so inferred names regress in quality
- using an imprecise size measure that does not match intended token-efficiency goals
- leaking a broader prompt-contract change into unrelated helper-session flows
