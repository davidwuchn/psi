# TUI Guide

This document covers interactive terminal usage for psi (`--tui`).

## Start

```bash
# Basic TUI start
clojure -M:run --tui

# OpenAI example
clojure -M:run --model gpt-4o --tui

# TUI with live nREPL introspection
clojure -M:run --tui --nrepl 8888
```

## OAuth Login (no env var needed)

```text
/login    # browser-based OAuth flow
/logout
```

## In-session commands

`/status` `/history` `/new` `/help` `/quit` `/skills` `/prompts` `/remember [text]`
`/skill:<name>` plus any extension commands

`/remember` performs a single manual memory capture from the runtime command surface.
It writes one memory artifact with the provided text (or a default note when blank).

If provider write-through fails but in-memory fallback succeeds, `/remember` returns a
visible warning (not silent success):
- `⚠ Remembered with store fallback`
- includes `provider` when available
- includes `store-error` and `detail` when available

Example warning output:

```text
⚠ Remembered with store fallback
  record-id: 3b31b4d2-4a4b-4fb9-87e2-5f66a8f8f0b1
  provider: failing-store
  store-error: boom
  detail: write failed
```
