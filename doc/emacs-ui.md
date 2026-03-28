# Emacs UI (rpc-edn)

The repository includes an Emacs frontend at `components/emacs-ui/` that runs
psi in a dedicated process buffer over rpc-edn.

## Install (straight.el)

Install directly from GitHub with `straight.el`:

```elisp
(straight-use-package
 '(psi-emacs
   :type git
   :host github
   :repo "hugoduncan/psi"
   :files ("components/emacs-ui/*.el")))

(require 'psi)
```

This installs the Emacs frontend files from the repo and makes
`M-x psi-emacs-start` / `M-x psi-emacs-project` available.

## Start

1. Ensure this repository is available locally.
2. In Emacs, add `components/emacs-ui` to `load-path` and load `psi.el`.
3. Run one of:
   - `M-x psi-emacs-start` for the default/global buffer.
     - Use `C-u M-x psi-emacs-start` to force a fresh buffer name (`*psi*<2>`, etc.).
   - `M-x psi-emacs-project` for a project-scoped buffer in the current project.
     - Buffer naming style: `*psi:<project>*` (for example `*psi:psi-main*`).
     - `C-u M-x psi-emacs-project` forces a fresh generated project buffer name.
     - `C-u N M-x psi-emacs-project` opens/uses slot `N`
       (`N<=1` => `*psi:<project>*`, `N>=2` => `*psi:<project>*<N>`).

This opens a dedicated psi buffer (default `*psi*`, or project-scoped `*psi:<project>*`) and starts one
owned subprocess per dedicated buffer using:

- `clojure -M:psi --rpc-edn`

By default, the subprocess runs in the directory where `psi-emacs-start` is
invoked. Override explicitly via `psi-emacs-working-directory` if needed.

## Customizing `psi-emacs-command`

`psi-emacs-command` controls the exact subprocess command used by the frontend.
Default:

```elisp
("clojure" "-M:psi" "--rpc-edn")
```

Customize interactively:
- `M-x customize-variable RET psi-emacs-command RET`

Or set in init:

```elisp
(setq psi-emacs-command '("clojure" "-M:run" "--rpc-edn"))
```

Example with explicit model:

```elisp
(setq psi-emacs-command
      '("clojure" "-M:run" "--rpc-edn" "--model" "sonnet-4.6"))
```

Requirement: command must launch psi in rpc-edn mode (include `--rpc-edn`).

## Developer checks

From repo root:

- `bb emacs:test`
- `bb emacs:e2e` (live end-to-end harness against `clojure -M:psi --rpc-edn`)
- `bb emacs:byte-compile`
- `bb emacs:check`

## Compose and keybindings

In `psi-emacs-mode`:

- `RET` inserts newline (never sends)
- `C-c RET` send prompt
  - slash-prefixed input always uses backend `command`
  - while streaming, non-slash input uses steer (`prompt_while_streaming` with `behavior=steer`)
- `C-u C-c RET` queue override for non-slash streaming input
- `C-c C-q` queue while streaming for non-slash input; slash-prefixed input still uses backend `command`; fallback to normal send when idle
- `C-c C-k` abort active streaming (`abort`)
- `C-c C-r` reconnect (prompts before clearing edited buffer)
- `C-c C-t` toggle tool-output view mode (collapsed ↔ expanded); also available as `M-x psi-emacs-toggle-tool-output-view`
- `C-c m m` set model (`M-x psi-emacs-set-model`)
- `C-c m n` cycle model next (`M-x psi-emacs-cycle-model-next`)
- `C-c m p` cycle model previous (`M-x psi-emacs-cycle-model-prev`)
- `C-c m t` set thinking level (`M-x psi-emacs-set-thinking-level`)
- `C-c m c` cycle thinking level (`M-x psi-emacs-cycle-thinking-level`)

Compose source rules:

- Active region sends region text (for both `C-c RET` and `C-c C-q`).
- Without a region, psi sends the tail draft block from the draft anchor marker to end-of-buffer.
- Normal editing keeps the anchor at the start of the current draft tail, so transcript text above the anchor is not resent unless you explicitly select it as a region.
- Reconnect clear (`C-c C-r` after confirmation) resets the buffer and repositions the draft anchor at the new buffer end; after reconnect, sends come only from text typed after that reset point.

## Prompt completion (`/` and `@`)

`psi-emacs-mode` installs a CAPF (`psi-emacs-prompt-capf`) for compose input.

- `/...` completes slash commands from a local cache:
  - built-in backend-owned commands (for example `/resume`, `/new`, `/status`, `/worktree`, `/jobs`, `/model`, `/thinking`, `/help`, `/history`, `/prompts`, `/skills`, `/login`, `/logout`, `/remember`, `/skill:`)
  - extension commands discovered from backend introspection, including worktree workflow commands such as `/work-on`, `/work-done`, `/work-rebase`, `/work-status` when the extension is loaded
- `@...` completes file references from cwd/project-root (with configurable hidden/excluded policy)

Completion behavior knobs live in customize group `psi-emacs-completion`.

## Rendering, status, and errors

Implementation note: transcript and footer identity are now region/property-backed.
Legacy marker/range fields that remain in frontend state (`assistant-range`,
`thinking-range`, `input-separator-marker`) are compatibility caches only, not
source-of-truth identity.


- Assistant streaming uses a single in-progress block updated by
  `assistant/delta` and finalized by `assistant/message`.
- Tool lifecycle rows render inline for
  `tool/start|delta|executing|update|result`.
- ANSI tool output is rendered with faces (no raw escape noise).
- Header line shows minimal transport + process state + run-state and tool mode:
  `psi [transport/process/run-state] tools:<mode>`.
  When `session/updated` includes model metadata, header appends
  `model:(<provider>) <model-id>`.
- `session/updated` is projected into frontend state for `/status`
  diagnostics (session id/phase, streaming+compacting flags, pending, retry).
- RPC errors are surfaced in minibuffer **and** mirrored as one persistent
  in-buffer `Error: ...` line.

When a rpc client exists but transport is not `ready`, send/queue requests are
rejected immediately with deterministic error text and draft text is preserved.

## Tool output view mode

Tool rows have two rendering modes:

- **collapsed** (default): one summary line per row (`<tool summary> <status>`),
  with live status updates and hidden body output.
- **expanded**: full tool body output is visible.

Header summaries are normalized for readability:

- `bash` uses `$` in headers.
- `read`/`edit`/`write` path arguments are shown relative to project root when possible.

Toggle between modes with `C-c C-t` (or `M-x psi-emacs-toggle-tool-output-view`).
The toggle applies immediately to all existing rows and to future rows.
Reconnect (`C-c C-r`) resets mode to collapsed; `/new` preserves current mode.

## Topic subscription

Emacs subscribes to the full default topic set (core + extension/footer topics):

- core: `assistant/*`, `tool/*`, `session/updated`, `command-result`, `error`
- extension/footer: `ui/*` (including `ui/frontend-action-requested`), `footer/updated`

## Reconnect semantics

- Reconnect is manual only (`C-c C-r`), no auto-restart.
- Confirmed reconnect clears buffer and starts a fresh session.
- No automatic resume/rehydrate occurs during reconnect.

## Still deferred in Emacs frontend

- compaction/auto-compact/auto-retry controls in-buffer UX
- richer multi-session/fork/tree command discoverability UI beyond backend-requested frontend actions
- reconnect-time resume picker / auto-resume
