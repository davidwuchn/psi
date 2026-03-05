# psi Emacs frontend

This frontend runs psi over `--rpc-edn` in a dedicated Emacs buffer.

## Developer checks

Run repeatable frontend checks from repo root:

- `bb emacs:test` — run ERT suites (`psi-test.el`, `psi-rpc-test.el`)
- `bb emacs:byte-compile` — byte-compile frontend modules (auto-cleans `.elc`)
- `bb emacs:check` — run byte-compile + tests

## Topic subscription

Emacs subscribes to the default topic set (`psi-rpc-default-topics`):

- core topics:
  - `assistant/delta`
  - `assistant/message`
  - `tool/start`
  - `tool/delta`
  - `tool/executing`
  - `tool/update`
  - `tool/result`
  - `session/updated`
  - `error`
- extension/footer topics:
  - `ui/dialog-requested`
  - `ui/widgets-updated`
  - `ui/status-updated`
  - `ui/notification`
  - `footer/updated`

## Idle slash commands

When the frontend is **idle** (not streaming), these built-in slash commands are intercepted locally and do **not** fall through to `prompt` RPC:

- `/quit`, `/exit` — close the frontend buffer/process
- `/resume` — resume prior session (`/resume <path>`), or open selector when no path is provided
- `/new` — request `new_session`, reset transcript/session rendering state, and continue in the new session
- `/status` — append deterministic frontend/session diagnostics text
- `/help`, `/?` — render slash command help

Unknown slash commands (for example `/foo`) are not handled locally and are sent through the normal `prompt` RPC path.

## Run-state model

Frontend routing is driven by an explicit run-state:

- `idle` — compose send/queue routes through idle slash interception first, then `prompt`
- `streaming` — compose send/queue routes to `prompt_while_streaming`
- `reconnecting` — transient state during manual reconnect (cleared to `idle` once transport is ready)
- `error` — set on RPC errors or streaming watchdog timeout

Header status includes this state: `psi [transport/process/run-state] tools:<mode>`.
When `session/updated` carries model metadata, header appends
`model:(<provider>) <model-id>`.

`session/updated` is projected into frontend state and `/status` diagnostics,
including: session id, phase, streaming/compacting flags, pending count,
and retry attempt. `/status` also includes `last-error: ...` when present.

### Transition sketch

- `idle -> streaming`: send/queue dispatches `prompt` (non-slash or non-intercepted slash)
- `streaming -> idle`: `assistant/message` finalize or explicit abort
- `streaming -> error`: watchdog timeout (no streaming progress for `psi-emacs-stream-timeout-seconds`)
- `* -> error`: RPC error event/callback
- `reconnecting -> idle`: RPC transport reaches `ready`

## Streaming behavior

Streaming send/queue behavior:

- send while streaming => `prompt_while_streaming` with steer behavior
- queue while streaming => `prompt_while_streaming` with queue behavior
- assistant stream rendering keeps a single in-progress block:
  - streamed text is shown verbatim while chunking
  - chunk payloads may be cumulative snapshots or true deltas; frontend merge avoids per-chunk line duplication
  - markdown processing is deferred until `assistant/message` finalization
- streaming progress events reset a watchdog timer
- watchdog timeout sends `abort`, upserts a deterministic transcript error line, and sets run-state to `error`

Idle slash interception applies only to idle compose send/queue paths.

## Transport readiness guard

When a rpc client exists but transport is not `ready`, compose send/queue
requests are rejected immediately:

- request is not dispatched
- run-state transitions to `error`
- `last-error` and the persistent `Error: ...` transcript line are updated
- draft text is preserved (not consumed)

## Model and thinking controls

Model/thinking controls dispatch canonical RPC ops and rely on `session/updated`
projection for header/state updates.

- `C-c m m` / `M-x psi-emacs-set-model` -> `set_model`
- `C-c m n` / `M-x psi-emacs-cycle-model-next` -> `cycle_model` (`direction=next`)
- `C-c m p` / `M-x psi-emacs-cycle-model-prev` -> `cycle_model` (`direction=prev`)
- `C-c m t` / `M-x psi-emacs-set-thinking-level` -> `set_thinking_level`
- `C-c m c` / `M-x psi-emacs-cycle-thinking-level` -> `cycle_thinking_level`

When transport is not ready, these requests are rejected by the same deterministic
transport guard used for compose send/queue.

## Extension dialog response flow

On `ui/dialog-requested`, Emacs maps dialog kinds to prompts and sends exactly one response op:

- confirm => yes/no prompt -> `resolve_dialog`
- select => completion over option labels (returns selected option `value`) -> `resolve_dialog`
- input => text prompt -> `resolve_dialog`
- user quit/cancel (`C-g`) -> `cancel_dialog`

RPC request shapes:

- `resolve_dialog`: `{:dialog-id <string> :result <boolean|string|null>}`
- `cancel_dialog`: `{:dialog-id <string>}`

`dialog-id` is always echoed from the inbound event; Emacs never invents IDs.

## Renderer boundary (explicit)

Emacs does **not** execute extension-provided renderer functions from extension UI state.
Tool/message payload text is treated as display-ready text.
Renderer registration/query metadata remains read-only via `query_eql`.

## Error surface

- RPC errors still emit minibuffer messages.
- The frontend also keeps a single persistent in-buffer error line (`Error: ...`) that is updated in place.
- Reconnect/new-session transcript reset clears the persistent error line and `last-error` state.
