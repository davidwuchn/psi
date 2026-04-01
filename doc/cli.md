# CLI Reference

Command line switches for starting psi (`clojure -M:psi ...`).

## Basic usage

```bash
clojure -M:psi [flags]
```

## Mode and session flags

- `--tui`
  - Start interactive terminal UI mode.
- `--rpc-edn`
  - Start EDN-lines RPC mode on stdin/stdout.
- `--rpc-trace-file <path>`
  - When used with `--rpc-edn`, append inbound/outbound RPC frames to `<path>` as newline-delimited EDN.
  - Captures both directions (`:dir :in` and `:dir :out`) with timestamp and raw wire line.
  - Runtime tracing can also be toggled dynamically via EQL mutation `psi.extension/set-rpc-trace`.
- `--nrepl [port]`
  - Start nREPL alongside session.
  - If `port` is omitted, a random port is chosen.

## Model and logging

- `--model <key>`
  - Select model key (same keys as `psi.ai.models/all-models`).
  - Falls back to `PSI_MODEL` when not provided.
- `--log-level <LEVEL>`
  - Set Timbre minimum level.
  - Valid: `TRACE | DEBUG | INFO | WARN | ERROR | FATAL | REPORT` (case-insensitive).

## Memory runtime flags

- `--memory-store <in-memory>`
- `--memory-store-fallback <on|off|true|false>`
- `--memory-history-limit <n>`
- `--memory-retention-snapshots <n>`
- `--memory-retention-deltas <n>`

`<n>` must be a positive integer.

## Session/runtime tuning

- `--llm-idle-timeout-ms <n>`
  - LLM idle timeout in milliseconds (`<n>` positive integer).
  - Default: `600000` (10 minutes).

## Environment variables

- `ANTHROPIC_API_KEY`
- `OPENAI_API_KEY`
- `PSI_MODEL`
- `PSI_DEVELOPER_PROMPT`

Memory-related:
- `PSI_MEMORY_STORE`
- `PSI_MEMORY_STORE_AUTO_FALLBACK`
- `PSI_MEMORY_HISTORY_COMMIT_LIMIT`
- `PSI_MEMORY_RETENTION_SNAPSHOTS`
- `PSI_MEMORY_RETENTION_DELTAS`

Session/runtime tuning:
- `PSI_LLM_IDLE_TIMEOUT_MS`

## Precedence notes

- `--model` flag overrides `PSI_MODEL`.
- `--llm-idle-timeout-ms` overrides `PSI_LLM_IDLE_TIMEOUT_MS`.
- For optional runtime settings generally: CLI flag takes precedence over env var.

## Examples

```bash
# Console
clojure -M:psi

# TUI
clojure -M:psi --tui

# RPC mode
clojure -M:psi --rpc-edn

# RPC mode with transport trace file
clojure -M:psi --rpc-edn --rpc-trace-file /tmp/psi-rpc.ndedn

# nREPL on random port
clojure -M:psi --nrepl

# nREPL on fixed port
clojure -M:psi --nrepl 7888

# TUI + nREPL
clojure -M:psi --tui --nrepl

# Pick model key
clojure -M:psi --model sonnet-4.6

# Memory retention
clojure -M:psi --memory-store in-memory \
  --memory-retention-snapshots 500 \
  --memory-retention-deltas 2000

# Disable auto fallback to in-memory
clojure -M:psi --memory-store-fallback off
```
