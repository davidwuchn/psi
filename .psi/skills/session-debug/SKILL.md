---
name: session-debug
description: Debug live psi session issues by using the correct runtime context, EQL surfaces, persisted session files, provider captures, and nREPL introspection.
lambda: "λsession. {ctx eql persistence captures nrepl} → diagnose"
---

# Session Debug

Use this skill when debugging live session/runtime issues, especially when EQL data, persisted session history, and nREPL state do not seem to match.

## Core rule

There are multiple session contexts in play.

- `psi.agent-session.main/session-state` is the live runtime session for the running UI/backend process.
- `psi.agent-session.core/global-context` may be a different singleton and can point at the wrong session for debugging.
- `app-query-tool` queries the UI-facing live session context, not necessarily the nREPL global context.

When debugging a live issue, prefer:
1. `app-query-tool` for session-root EQL truth
2. `@psi.agent-session.main/session-state` for nREPL inspection of the actual running session
3. persisted session file for historical confirmation

## Fast workflow

1. Query session identity via EQL:

```clojure
[:psi.agent-session/session-id
 :psi.agent-session/context-active-session-id
 :psi.agent-session/session-file
 :psi.agent-session/model-provider
 :psi.agent-session/model-id
 :psi.runtime/nrepl-port]
```

2. In nREPL, inspect the live runtime context:

```clojure
(require 'psi.agent-session.main)
(require 'psi.agent-session.core)

(let [ctx (:ctx @psi.agent-session.main/session-state)]
  {:session-id (:session-id (psi.agent-session.core/get-session-data-in ctx))
   :active-session-id (:active-session-id (psi.agent-session.core/get-context-index-in ctx))})
```

3. If needed, read the persisted session file recorded by EQL.

4. If debugging provider failures, inspect provider capture rings from `session-state`.

## Provider debugging

### Inspect latest provider captures in nREPL

```clojure
(require 'psi.agent-session.main)
(require 'clojure.pprint)

(let [ctx (:ctx @psi.agent-session.main/session-state)]
  {:provider-request-count (count @(:provider-requests-atom ctx))
   :provider-reply-count   (count @(:provider-replies-atom ctx))})
```

### Inspect latest Anthropic request/reply pair

```clojure
(require 'psi.agent-session.main)
(require 'clojure.pprint)

(let [ctx (:ctx @psi.agent-session.main/session-state)
      reqs @(:provider-requests-atom ctx)
      reps @(:provider-replies-atom ctx)
      anthropic-reqs (filter #(= :anthropic (:provider %)) reqs)
      anthropic-reps (filter #(= :anthropic (:provider %)) reps)
      last-req (last anthropic-reqs)
      turn-id (:turn-id last-req)]
  (clojure.pprint/pprint last-req)
  (clojure.pprint/pprint (vec (filter #(= turn-id (:turn-id %)) anthropic-reps))))
```

If `ANTHROPIC_REQ_COUNT` is zero, the ring buffer may have rolled over. Reproduce and inspect immediately.

## RPC transport tracing (UI/backend boundary)

Use this when the frontend transcript and backend behavior disagree (for example: blank assistant lines, missing events, malformed frames, or parse failures).

### Start with trace enabled from CLI

```bash
clojure -M:psi --rpc-edn --rpc-trace-file /tmp/psi-rpc.ndedn
```

### Query live trace config via EQL

```clojure
[:psi.agent-session/rpc-trace-enabled
 :psi.agent-session/rpc-trace-file]
```

### Toggle trace dynamically via graph mutation

Enable:

```clojure
[(psi.extension/set-rpc-trace {:enabled true
                               :file "/tmp/psi-rpc.ndedn"})]
```

Disable:

```clojure
[(psi.extension/set-rpc-trace {:enabled false})]
```

Toggle current state (no `:enabled`):

```clojure
[(psi.extension/set-rpc-trace {})]
```

Note: enabling trace without a non-blank `:file` returns `request/invalid-params`.

### Trace entry shape

Each line in the trace file is EDN with:

- `:ts` timestamp
- `:dir` (`:in` or `:out`)
- `:raw` wire line
- optional parsed `:frame`
- optional `:parse-error`

This gives protocol-level evidence for exactly what crossed the stdio boundary.

## EQL surfaces to use first

### Session identity / runtime

```clojure
[:psi.agent-session/session-id
 :psi.agent-session/context-active-session-id
 :psi.agent-session/session-file
 :psi.agent-session/cwd
 :psi.agent-session/model
 :psi.agent-session/model-provider
 :psi.agent-session/model-id
 :psi.agent-session/phase
 :psi.runtime/nrepl-endpoint]
```

### Provider capture surface

```clojure
[:psi.agent-session/provider-request-count
 :psi.agent-session/provider-reply-count
 {:psi.agent-session/provider-last-request
  [:psi.provider-request/provider
   :psi.provider-request/api
   :psi.provider-request/url
   :psi.provider-request/turn-id
   :psi.provider-request/timestamp
   :psi.provider-request/headers
   :psi.provider-request/body]}
 {:psi.agent-session/provider-last-reply
  [:psi.provider-reply/provider
   :psi.provider-reply/api
   :psi.provider-reply/url
   :psi.provider-reply/turn-id
   :psi.provider-reply/timestamp
   :psi.provider-reply/event]}]
```

### API error surface

```clojure
[:psi.agent-session/api-error-count
 {:psi.agent-session/api-errors
  [:psi.api-error/message-index
   :psi.api-error/http-status
   :psi.api-error/timestamp
   :psi.api-error/error-message-brief
   :psi.api-error/error-message-full
   :psi.api-error/request-id
   {:psi.api-error/surrounding-messages
    [:psi.context-message/index
     :psi.context-message/role
     :psi.context-message/content-types
     :psi.context-message/snippet]}
   {:psi.api-error/request-shape
    [:psi.request-shape/message-count
     :psi.request-shape/tool-use-count
     :psi.request-shape/tool-result-count
     :psi.request-shape/missing-tool-results
     :psi.request-shape/orphan-tool-results
     :psi.request-shape/alternation-valid?
     :psi.request-shape/alternation-violations
     :psi.request-shape/empty-content-count
     :psi.request-shape/headroom-tokens]}]}]
```

## Why session debugging can go wrong

### Symptom: nREPL state disagrees with app-query-tool
Cause:
- you inspected `global-context` instead of `main/session-state`

Fix:
- inspect `(:ctx @psi.agent-session.main/session-state)`

### Symptom: provider capture query shows requests, but nREPL shows none
Cause:
- wrong runtime context object

Fix:
- verify session ids match between EQL and nREPL

### Symptom: old provider failure has no retained request
Cause:
- provider capture ring buffer rolled over

Fix:
- reproduce and inspect immediately
- if repeated investigation is expected, increase capture persistence or persist error-time request/reply details

### Symptom: EQL query errors on nested attrs
Cause:
- Pathom output shape may differ from guessed attrs, or resolver may only expose a subset at that level

Fix:
- start from `:psi.graph/root-queryable-attrs`
- inspect `:psi.graph/resolver-syms`
- use known tested query shapes from resolver/core tests

## Practical debugging sequence

1. Confirm active session id via EQL
2. Confirm the same session id in `main/session-state`
3. Inspect current turn state
4. Inspect provider capture rings
5. Inspect API error entities
6. Read persisted session file if historical reconstruction is needed
7. Reproduce if captures have rolled off

## Reloading changed code

When changing runtime code, reload the affected namespaces via nREPL, then rebuild the global query env if query surfaces changed:

```clojure
(require 'psi.agent-session.resolvers :reload)
(require 'psi.ai.providers.anthropic :reload)
(require 'psi.agent-session.executor :reload)
(require 'psi.agent-session.core)
(require 'psi.introspection.core)

(psi.agent-session.core/register-resolvers!)
(psi.introspection.core/register-resolvers!)
```

If `app-query-tool` still shows the old graph after this, the live UI/backend process serving `app-query-tool` is likely a different or stale JVM. In that case, restart the actual running psi process.

### Turn-id lookup pattern for provider failures

When a failure has a captured `:psi.api-error/turn-id`, prefer narrow turn-id inspection over dumping the full provider capture ring.

Code-side resolver/test support now exists for:
- `:psi.agent-session/provider-request-for-turn-id`
- `:psi.agent-session/provider-reply-for-turn-id`
- seeded by `:psi.agent-session/lookup-turn-id`

If the live graph has been rebuilt and exposes those attrs, query:

```clojure
[:psi.agent-session/lookup-turn-id
 {:psi.agent-session/provider-request-for-turn-id
  [:psi.provider-request/provider
   :psi.provider-request/api
   :psi.provider-request/url
   :psi.provider-request/turn-id
   :psi.provider-request/timestamp
   :psi.provider-request/body]}
 {:psi.agent-session/provider-reply-for-turn-id
  [:psi.provider-reply/provider
   :psi.provider-reply/api
   :psi.provider-reply/url
   :psi.provider-reply/turn-id
   :psi.provider-reply/timestamp
   :psi.provider-reply/event]}]
```

Set `:psi.agent-session/lookup-turn-id` to the failing turn id before querying, or use the equivalent nREPL/session-local code path if the live graph has not yet reloaded.

Test namespaces may not be available on the live runtime classpath.

## Notes

- `app-query-tool` is often the fastest source of truth for live session-root attrs.
- `bb clojure:test:unit --focus ...` is the fastest way to verify a focused debugging fix.
- Prefer the smallest instrumentation change that preserves exact provider request/reply evidence for the next failure.
