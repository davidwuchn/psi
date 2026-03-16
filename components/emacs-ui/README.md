# psi Emacs frontend

This frontend runs psi over `--rpc-edn` in dedicated Emacs buffers.

Entry commands:

- `M-x psi-emacs-start` — global/default psi buffer (`*psi*` by default)
- `M-x psi-emacs-project` — project-scoped psi buffer (`*psi:<project>*`)

By default, subprocesses start from the directory where the start command is
invoked. `psi-emacs-project` uses the detected project root as startup cwd.
Set `psi-emacs-working-directory` to force a specific working directory.

## Project command prefix behavior (`psi-emacs-project`)

`psi-emacs-project` supports buffer naming + prefix behaviors:

- no prefix (`M-x psi-emacs-project`): reuse canonical `*psi:<project>*`
- `C-u M-x psi-emacs-project`: force fresh generated name from project base
- `C-u N M-x psi-emacs-project`: open/use project slot `N`
  - `N <= 1` => canonical `*psi:<project>*`
  - `N >= 2` => `*psi:<project>*<N>`

Examples:

- `M-x psi-emacs-project` -> `*psi:psi-main*`
- `C-u M-x psi-emacs-project` -> `*psi:psi-main*<2>` (first free generated)
- `C-u 3 M-x psi-emacs-project` -> `*psi:psi-main*<3>`

## Developer checks

Run repeatable frontend checks from repo root:

- `bb emacs:test` — run ERT suites (`psi-test.el`, `psi-rpc-test.el`)
- `bb emacs:e2e` — run live end-to-end harness (`psi-e2e-test.el`) against `clojure -M:psi --rpc-edn` (`/history`, `/thinking` frontend-action flow, `/quit`)
- `bb emacs:byte-compile` — byte-compile frontend modules (auto-cleans `.elc`)
- `bb emacs:check` — run byte-compile + tests

## Topic subscription

Emacs subscribes to the default topic set (`psi-rpc-default-topics`):

- core topics:
  - `assistant/delta`
  - `assistant/message`
  - `tool/start`
  - `tool/executing`
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

When the frontend is **idle** (not streaming), slash-prefixed input is routed to the backend `command` RPC op instead of `prompt`.
The backend owns slash parsing and returns either:

- `command-result` — terminal actions and text results
- `ui/frontend-action-requested` — selector/picker flows completed by Emacs and submitted via `frontend_action_result`

Common backend-owned slash commands include:

- `/quit`, `/exit` — backend emits `command-result` type `quit`; Emacs closes the frontend buffer/process when that result arrives
- `/resume` — backend requests a resume selector for `/resume`, or handles direct `/resume <path>` through backend command dispatch
- `/tree` — backend requests a live-session selector for `/tree`, or switches directly for `/tree <session-id>`
- `/new` — backend starts a fresh session and emits canonical command/session/footer/context updates
- `/status`, `/history`, `/help`, `/?`, `/prompts`, `/skills`, `/worktree` — backend returns deterministic text
- `/jobs`, `/job`, `/cancel-job` — backend returns deterministic background-job text/usage results
- `/model` — backend requests a model picker for `/model`, or handles `/model <provider> <model-id>` directly
- `/thinking` — backend requests a thinking-level picker for `/thinking`, or handles `/thinking <level>` directly
- `/login`, `/logout`, `/remember`, and extension commands — backend-owned command results

Model picker scope can still be customized via `psi-emacs-model-selector-provider-scope`:

- `all` (default) — show all runtime models
- `authenticated` — show only models whose providers have configured auth

Unknown slash commands are currently sent to backend `command` handling as well; the backend responds with deterministic not-a-command feedback rather than falling back to `prompt`.

## Dedicated input area + history

The frontend now maintains a dedicated compose input area above the footer/projection block.

- A horizontal separator marks the boundary between transcript output (above) and editable input (below).
- Send (`C-c RET`) or queue (`C-c C-q`) copies input into transcript as `User: ...`, then clears input.
- Input supports multi-line editing (`RET` inserts newline).
- While assistant/tool output streams, point remains in the input area.

Input history navigation (standard Emacs keys):

- `M-p` -> previous input
- `M-n` -> next/newer input

History behavior:

- newest-first history ring
- duplicate-consecutive submissions are collapsed
- unsent draft is restored when navigating back to newest entry

## Prompt completion (`/` and `@`)

`psi-emacs-mode` installs a CAPF (`psi-emacs-prompt-capf`) for compose input.

- `/...` completes slash commands (category `psi_prompt`)
  - includes backend-owned built-ins (`/resume`, `/new`, `/status`, `/history`, `/model`, `/thinking`, …)
  - includes common backend commands (`/prompts`, `/skills`, `/login`, `/logout`, `/remember`, `/skill:`)
  - includes discovered extension commands from backend introspection
- `@...` completes file references (category `psi_reference`)
  - searches current working directory and project root (when distinct)
  - hidden file policy is configurable
  - excluded path prefixes are configurable (default excludes `.git`)
  - marks directories with trailing `/`
  - appends trailing space after accepting file candidates

Completion behavior knobs (`M-x customize-group RET psi-emacs-completion`):

- `psi-emacs-slash-max-candidates`
- `psi-emacs-reference-max-candidates`
- `psi-emacs-reference-match-style` (`substring` or `prefix`)
- `psi-emacs-reference-include-hidden`
- `psi-emacs-reference-excluded-path-prefixes`

The CAPF returns `nil` outside slash/reference token contexts, so normal CAPF composition remains intact.

### Optional category UI tuning (Corfu/Vertico/Default)

You can tune styles per completion category:

```elisp
(setq completion-category-overrides
      '((psi_prompt (styles basic partial-completion))
        (psi_reference (styles basic partial-completion substring))))
```

Unknown slash commands do not currently fall through to `prompt`; they are submitted to backend `command` handling.

## Run-state model

Frontend routing is driven by an explicit run-state:

- `idle` — non-slash input routes to `prompt`; slash-prefixed input routes to backend `command`
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

- `idle -> streaming`: send/queue dispatches `prompt` for non-slash input or `command` for slash input
- `streaming -> idle`: `assistant/message` finalize or explicit abort
- `streaming -> error`: watchdog timeout (no streaming progress for `psi-emacs-stream-timeout-seconds`)
- `* -> error`: RPC error event/callback
- `reconnecting -> idle`: RPC transport reaches `ready`

## Buffer mode + markdown scope

`psi-emacs-mode` uses `text-mode` as its parent. This keeps baseline
fontification neutral and avoids whole-buffer markdown styling drift.

Markdown processing is applied only to finalized assistant reply ranges via
`markdown-fontify-region`.

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

Idle slash routing to backend `command` applies only to idle compose send/queue paths.

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
- Unexpected psi subprocess exits are surfaced as `transport/process-exit` errors,
  including a bounded stderr tail in the error text when available.
- The frontend also keeps a single persistent in-buffer error line (`Error: ...`) that is updated in place.
- Reconnect/new-session transcript reset clears the persistent error line and `last-error` state.
