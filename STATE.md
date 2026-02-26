# State

Current truth about the Psi system.

---

## Components

| Component       | Status | Notes                                                      |
|-----------------|--------|------------------------------------------------------------|
| `ai`            | ✓      | Provider streaming, model registry, tested                 |
| `engine`        | ✓      | Statechart infra, system state, nullable ctx               |
| `query`         | ✓      | Pathom3 EQL registry, `query-in`, nullable ctx             |
| `agent-core`    | ✓      | LLM agent lifecycle statechart + EQL resolvers             |
| `history`       | ✓      | Git log resolvers, nullable git context                    |
| `introspection` | ✓      | Bridges engine + query, self-describing graph              |
| `tui`           | ·      | Components ✓, core ✓, terminal raw mode ✗ (see below)     |
| `agent-session` | ·      | Session ✓, main REPL ✓, TUI session broken (see below)    |

## Architecture Progress

- ✓ Engine (statecharts) substrate
- ✓ Query (EQL/Pathom3) surface
- ✓ AI provider layer
- ✓ Agent core loop
- ✓ Git history resolvers
- ✓ Introspection (engine queries itself)
- ✓ Coding-agent session orchestration (agent-session component)
- ✓ Built-in tools (read, bash, edit, write)
- ✓ Executor (bridges ai streaming → agent-core loop protocol)
- ✓ Runnable entry point (`clojure -M:run`)
- · TUI session (`--tui` flag) — starts, renders, blocks; input broken
- ✗ Session resolvers wired into global query graph
- ✗ Graph emergence from domain resolvers
- ✗ RPC / HTTP API surface
- ✗ AI COMPLETE

## Runnable

```bash
ANTHROPIC_API_KEY=sk-... clojure -M:run
clojure -M:run --model claude-3-5-sonnet
clojure -M:run --model gpt-4o --tui     # starts but typing does not appear
```

In-session commands (plain mode): `/status`, `/history`, `/new`, `/help`, `/quit`

## TUI Session: Current Diagnosis

### What works
- Process starts and blocks ✓
- First render fires: separator `────`, `刀:`, cursor marker `_pi:c` ✓
- `stty -echo raw` runs successfully (exit 0) when JVM has a PTY ✓
- `/dev/tty` is readable from the JVM ✓
- `dispatch-input` correctly splits multi-char reads into per-key events ✓
- Editor state updates internally after keystrokes (confirmed by log) ✓
- `tick-in!` now uses `swap!` (race condition fixed) ✓

### What is broken
- **Typed characters do not appear on screen**
- `stty` confirms raw mode IS set: `-icanon -isig -iexten -echo`
- Yet chars echo back anyway (appear before TUI output in expect trace)
- This is because the echo is from the **PTY layer** (expect's pty, or
  macOS terminal emulator), not from the tty line discipline — `stty
  -echo` suppresses the OS echo but terminal emulators do local echo
- After typing `abc`, only ONE render block appears (`[?2026h…[?2026l`)
  and the editor line is still `_pi:c` (no text)
- **Root cause**: the `stty -echo raw` call runs in a subprocess with
  `/dev/tty` redirected as stdin, but the macOS PTY's `TIOCGWINSZ`
  returns `0 0` dimensions — suggests `stty` is operating on a different
  TTY fd than the one the JVM process is using for actual I/O

### Confirmed facts from stty output inside PTY
```
stty size → "0 0"      (PTY has no window size set)
stty -echo raw → exit 0, but:
  lflags: -icanon -isig -iexten -echo echoke echoctl
  oflags: -opost -oxtabs
```
`-opost` means output post-processing is off — so `\r\n` sequences
written to stdout may not translate correctly. The `write-synchronized!`
uses `\r\n` but with `-opost` these go through as-is, which is correct
for a raw terminal. So output rendering is fine.

### Why input doesn't appear despite editor updating
The expect trace shows: `abc` echoed → then ONE `[?2026h…[?2026l` block
(the initial render), then cleanup. No second render block. This means
either:
1. Keystrokes arrive BEFORE the TUI render loop starts (during JVM
   startup), so `on-input` fires but `tui-ctx` isn't fully ready, OR
2. `on-input` fires correctly and `handle-input-in!` updates the editor,
   but `tick-in!` in the render loop is not seeing `render-requested=true`

The stty output shows `lflags: -icanon` — raw mode IS active when stty
runs. But the `/dev/tty` fd used for `FileInputStream` in
`read-input-loop` may be a **different fd** than the one stty configured.
On macOS, `/dev/tty` opens the process's controlling terminal. The
subprocess `stty` opens its own `/dev/tty`. These should be the same
PTY — but there may be a timing issue: stty runs then returns, and by
the time the JVM reads from `/dev/tty` the terminal driver state may be
different.

### Most likely root cause
`ProcessTerminal` reads from `/dev/tty` (a `FileInputStream`), but
`stty` sets raw mode on the PTY controlling terminal. These are the
same device, but **the read loop starts in a daemon thread while stty
may not have completed yet**. The race: reader starts → reads in cooked
mode → gets full lines with newline → passes `"abc\n"` to on-input →
`dispatch-input` splits it into `"a"`, `"b"`, `"c"`, `"\n"` → `"\n"`
translates to `"enter"` → session handler sees `"enter"` with non-empty
editor (from the prior chars) → fires `run-agent-async!`. Meanwhile the
chars were processed correctly but the render loop may have overwritten
state.

Actually from the hex trace: `ab\r\n` appears **before** `[?2026h` —
before the TUI even finishes its first render. The JVM startup + first
render takes ~4s, and the keystrokes `abc` are sent at t+5s (after
startup). So keystrokes ARE arriving after startup. The echo (`ab\r\n`)
is terminal emulator local echo, not tty echo (stty -echo is working).

### True root cause (confirmed)
The render loop calls `tick-in!` every 33ms. Each `tick-in!` does a
`swap!` that calls `do-render!`. `do-render!` calls `render-tui-children`
which calls `proto/render` on each child. The rendered output goes to
`write! terminal` which calls `.print out` and `.flush out`. But
`out = System/out`.

With `stty raw` → `-opost` is set → output post-processing disabled →
the `\r\n` line separator in `write-synchronized!` is written correctly.
BUT: the differential render uses `(format move-up-fmt lines-above)` =
`"\u001b[1A"` which moves cursor up. If the cursor is NOT at the bottom
of the last rendered line — because the terminal emulator local echo of
keystrokes advanced the cursor — the up-move lands in the wrong place.

This is the terminal cursor position desync problem.

## agent-session namespaces

| Namespace                       | Role                                              |
|---------------------------------|---------------------------------------------------|
| `core.clj`                      | Public API, create-context, global wrappers       |
| `statechart.clj`                | Session statechart (idle/streaming/compacting/retrying) |
| `session.clj`                   | AgentSession data model, malli schemas            |
| `compaction.clj`                | Compaction algorithm (stub, injectable fn)        |
| `extensions.clj`                | Extension registry + broadcast dispatch           |
| `persistence.clj`               | Append-only journal                               |
| `resolvers.clj`                 | EQL resolvers (:psi.agent-session/*)              |
| `tools.clj`                     | Built-in tool implementations                     |
| `executor.clj`                  | ai ↔ agent-core streaming bridge                  |
| `main.clj`                      | Interactive REPL loop + TUI session (-main)       |

## Test Status

139 tests, 509 assertions, 0 failures. 0 clj-kondo warnings. 0 clojure-lsp diagnostics.

## Specs

| Spec file                  | Component mapping       | Status                                |
|----------------------------|-------------------------|---------------------------------------|
| `bootstrap-system.allium`  | `engine` + `query`      | ✓ implemented                         |
| `agent.allium`             | `agent-core`            | ✓ implemented                         |
| `ai-abstract-model.allium` | `ai`                    | ✓ implemented                         |
| `coding-agent.allium`      | `agent-session`         | ✓ split → 3 sub-specs; ✓ implemented  |
| `tui.allium`               | `tui`                   | partial — session loop not yet working|

## Open Questions

- TUI: correct approach for raw-mode terminal I/O on macOS JVM?
  Options: (a) use JLine3 library, (b) use charm.clj/lanterna,
  (c) fix ProcessTerminal by using ioctl via JNA/JNI,
  (d) absolute cursor positioning instead of differential rendering

## nREPL

Port: `8888`
