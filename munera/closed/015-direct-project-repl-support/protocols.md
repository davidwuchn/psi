# Clojure REPL-to-REPL Options

## Options

1. **stdio REPL** — built-in, text protocol over stdin/stdout.
2. **Socket REPL** — built-in (`clojure.core.server`), text protocol over TCP.
3. **prepl** — built-in (Clojure 1.10+), structured EDN over TCP. Variant of socket REPL with a different `:accept`.
4. **nREPL** — bencode-framed messages, sessions, middleware. Standard for editor tooling.
5. **unrepl** — older third-party structured protocol. Largely superseded by prepl.

## Babashka support

| Option      | Clojure | Babashka           |
|-------------|---------|--------------------|
| stdio       | ✅      | ✅                 |
| Socket REPL | ✅      | ✅                 |
| prepl       | ✅      | ❌                 |
| nREPL       | ✅ (dep)| ✅ (bundled, subset; minimal middleware) |

## Trade-offs

- **stdio** — simplest, always available. Single text stream mixes input/output/returns; parsing requires sentinel forms. One client only.
- **Socket REPL** — same text protocol as stdio, but attach/detach, multi-client, connect to running process. Still text parsing.
- **prepl** — structured EDN per form; clean `:ret` / `:out` / `:err` / `:tap`. Built-in, multi-client. **No babashka.**
- **nREPL** — richest: framed messages, sessions, interrupt, completion, info, load-file. Huge client ecosystem (CIDER, Calva, Cursive, Conjure). Cost: dependency + more protocol surface.

## Recommendation

For **Clojure + babashka parity** with start-or-attach:

- **nREPL** — both runtimes ship it, structured framing, broad tooling support. Best pragmatic choice.
- **Socket REPL** — zero-dependency fallback if text parsing is acceptable.
- **prepl** — ideal on Clojure alone; off the table with babashka.

## Note on "attach to running"

Any option requires the target process to have started the server at boot. No retrofitting a REPL into a process that didn't open a port (absent JVM attach tricks). Design implication: document/encourage starting an nREPL server on process startup.
