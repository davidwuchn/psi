# CLI Reference

Command line switches for starting psi (`clojure -M:run ...`).

## Basic usage

```bash
clojure -M:run [flags]
```

## Mode and session flags

- `--tui`
  - Start interactive terminal UI mode.
- `--rpc-edn`
  - Start EDN-lines RPC mode on stdin/stdout.
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

- `--memory-store <datalevin|in-memory>`
- `--memory-store-root <path>`
- `--memory-store-db-dir <path>`
- `--memory-store-fallback <on|off|true|false>`
- `--memory-history-limit <n>`
- `--memory-retention-snapshots <n>`
- `--memory-retention-deltas <n>`

`<n>` must be a positive integer.

## Session/runtime tuning

- `--llm-idle-timeout-ms <n>`
  - LLM idle timeout in milliseconds (`<n>` positive integer).

## Environment variables

- `ANTHROPIC_API_KEY`
- `OPENAI_API_KEY`
- `PSI_MODEL`
- `PSI_DEVELOPER_PROMPT`

Memory-related:
- `PSI_MEMORY_STORE`
- `PSI_MEMORY_STORE_ROOT`
- `PSI_MEMORY_STORE_DB_DIR`
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
clojure -M:run

# TUI
clojure -M:run --tui

# RPC mode
clojure -M:run --rpc-edn

# nREPL on random port
clojure -M:run --nrepl

# nREPL on fixed port
clojure -M:run --nrepl 7888

# TUI + nREPL
clojure -M:run --tui --nrepl

# Pick model key
clojure -M:run --model sonnet-4.6

# Memory store selection + retention
clojure -M:run --memory-store datalevin \
  --memory-store-db-dir /tmp/psi-memory.dtlv \
  --memory-retention-snapshots 500 \
  --memory-retention-deltas 2000

# Disable auto fallback to in-memory
clojure -M:run --memory-store-fallback off
```
