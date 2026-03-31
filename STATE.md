# State

## Now (2026-03-25)

### remove-active-session branch — shared current-session bridge removed from agent-session

The refactor target is now implemented.

#### Completed architecture

- `:active-session-id` is gone from canonical root state
- shared ctx no longer carries `:current-session-id*`
- core has no shared current session concept
- RPC owns its own focus pointer
- TUI owns its own focus pointer
- service is explicit-only
- core/session logic routes through explicit `session-id` or scoped `:target-session-id` ctx

#### What changed

**Core atom / ctx shape**
- removed root-state `:active-session-id`
- removed shared ctx `:current-session-id*`
- `ss/active-session-id-in` now reads only `:target-session-id`
- added explicit `ss/retarget-ctx` helper for scoped execution

**RPC**
- RPC runtime state owns `focus-session-id*`
- targetable ops are scoped from explicit request `:session-id` or RPC-local focus
- per-request session routing is explicit; RPC no longer uses dynamic `*request-session-id*` binding
- lifecycle flows update RPC focus locally
- no shared ctx focus writes remain

**TUI / console**
- TUI owns `:focus-session-id` in UI state
- main runtime closures scope ctx explicitly from adapter-local focus
- CLI/TUI no longer depend on shared ctx current-session fallback

**Resolvers / service**
- removed `:psi.agent-session/context-active-session-id` from resolver surface
- service APIs require explicit `session-id`

**Lifecycle**
- new/resume/fork return session data and re-scope locally
- no lifecycle operation mutates a shared current-session pointer

**Tests / harness**
- tests updated to re-scope ctx explicitly after lifecycle operations
- test support now seeds `:target-session-id` directly

#### Truthful invariant

> agent-session core has no shared current-session concept.
> Session routing is explicit: either adapter-local focus or scoped `:target-session-id`.

#### Tests

All current Clojure tests are passing:
- `bb clojure:test:unit` ✓ 935 tests, 5660 assertions, 0 failures
- `bb clojure:test` ✓ 935 unit + 86 extension tests green

#### What remains next

The bridge-removal plan is complete. Remaining work is follow-on cleanup / next design steps:
- extension instance state storage
- retire remaining agent-core coupling in some consumers
- configuration scoping
- cache stability improvements
