Goal: extend `psi-tool` so it can control and use the managed project REPL.

Context:
- `psi-tool` is the canonical runtime control/query surface for live psi operations.
- Managed project REPL support already exists on the shared command surface as `/project-repl`, including status/start/attach/stop/eval/interrupt behavior.
- Exposing project REPL operations through `psi-tool` would make them available to tool-driven and programmatic workflows without routing through chat command parsing.
- Current `psi-tool` actions are explicit and narrow: `query`, `eval`, and `reload-code`.
- Current `/project-repl` behavior is implemented as a command-formatting layer over direct project nREPL lifecycle/eval helpers.

Problem statement:
`psi-tool` currently has no canonical way to interact with the managed project REPL.
That leaves an important runtime capability available only through command text parsing and command-formatted text responses, which is a poor fit for tool-driven and programmatic workflows.

Design options considered:

Option 1 — allow `psi-tool` to run generic mutations, then expose project-REPL mutations
- Shape:
  - extend `psi-tool` with a generic mutation/invocation mode
  - expose project REPL operations as graph mutations or an equivalent generic imperative runtime call surface
  - have tool callers invoke those generic mutations for status/start/attach/stop/eval/interrupt
- Benefits:
  - could create one general imperative runtime surface instead of adding one-off tool actions
  - could eventually support more than project REPL operations
  - might align with a future broader mutation story if psi wants canonical tool access to many runtime writes
- Costs and risks:
  - broadens `psi-tool` from a small explicit contract into a generic imperative gateway
  - weakens the current action design, where each imperative mode has intentional validation and result shaping
  - pushes this task toward designing a general mutation contract, authorization model, result model, and error model
  - risks making `psi-tool` an unstructured command proxy by another name
  - creates pressure to expose generic runtime mutations before we know the stable public mutation surface we actually want
  - is larger than this task needs, because project REPL support already has clear domain operations and existing shared helpers
- Conclusion:
  - this is architecturally possible, but too broad for this task and in tension with the constraint to keep the extension additive and localized rather than broadening `psi-tool` into an unstructured command proxy

Option 2 — provide direct project-REPL support in `psi-tool`
- Shape:
  - add a dedicated `psi-tool` action for managed project REPL operations
  - validate explicit project-REPL operation names and explicit targeting inputs
  - call existing project nREPL runtime/lifecycle/eval helpers directly
  - return structured machine-oriented results instead of command-formatted text
- Benefits:
  - keeps `psi-tool` explicit, narrow, and intentional
  - matches current `psi-tool` design, where imperative operations such as `reload-code` have their own validated contract and stable report shape
  - reuses existing managed project REPL primitives instead of routing through chat command parsing
  - lets `/project-repl` and `psi-tool` share the same domain helpers while serving different presentation needs
  - keeps scope local to the project REPL capability rather than inventing a generic mutation API prematurely
- Costs and risks:
  - adds another specialized action to `psi-tool`
  - if repeated too often for many domains, `psi-tool` could become a bag of unrelated actions
  - requires conscious overlap management so `/project-repl` remains a presentation layer over the same canonical behavior rather than diverging
- Conclusion:
  - this is the preferred option for this task

Decision:
Adopt direct project-REPL support in `psi-tool`, not generic mutation execution.

Why this decision:
- The task is specifically about exposing managed project REPL capabilities through `psi-tool`, not about inventing a general runtime mutation protocol.
- The existing `psi-tool` contract already favors explicit actions with domain-specific validation and structured results.
- The existing project REPL implementation already has clear domain operations and runtime helpers that can be reused directly.
- This keeps the change additive and localized, and avoids broadening `psi-tool` into a generic imperative escape hatch.

Canonical `psi-tool` surface:
Add a new action:
- `action: "project-repl"`

Add an explicit operation discriminator within that action:
- `op: "status" | "start" | "attach" | "stop" | "eval" | "interrupt"`

Request shape:
- `action`
  - must be `"project-repl"`
- `op`
  - required operation discriminator for project REPL behavior
- `worktree-path`
  - optional explicit absolute target worktree path
  - when present, it is authoritative
- `host`
  - optional for attach mode
- `port`
  - optional/required depending on attach behavior and existing attach/config rules
- `code`
  - required for eval mode

Concrete request examples:
- status using invoking session worktree:
  - `psi-tool(action: "project-repl", op: "status")`
- status using explicit worktree:
  - `psi-tool(action: "project-repl", op: "status", worktree-path: "/abs/path/to/worktree")`
- start using invoking session worktree:
  - `psi-tool(action: "project-repl", op: "start")`
- attach using explicit worktree and explicit endpoint:
  - `psi-tool(action: "project-repl", op: "attach", worktree-path: "/abs/path/to/worktree", host: "127.0.0.1", port: 7888)`
- stop using invoking session worktree:
  - `psi-tool(action: "project-repl", op: "stop")`
- eval using invoking session worktree:
  - `psi-tool(action: "project-repl", op: "eval", code: "(+ 1 2)")`
- eval using explicit worktree:
  - `psi-tool(action: "project-repl", op: "eval", worktree-path: "/abs/path/to/worktree", code: "(require ''clojure.string)")`
- interrupt using invoking session worktree:
  - `psi-tool(action: "project-repl", op: "interrupt")`

Targeting semantics:
Project REPL targeting must remain explicit and consistent with the existing project REPL design.

Canonical target selection precedence for `psi-tool(action: "project-repl", ...)`:
1. explicit `worktree-path`
2. invoking session’s canonical worktree-path from explicit session-bound tool context
3. otherwise: explicit error

Explicitly not allowed:
- process cwd fallback
- adapter focus fallback
- hidden dynamic binding or hidden active-session inference unrelated to the invoking tool/session context

Rationale:
- project REPL instances are keyed by canonical worktree identity
- wrong implicit targeting is worse than an explicit failure
- this preserves the same design direction already adopted elsewhere in psi: explicit session routing over inferred focus

Operation semantics:

1. `status`
- returns structured status for the managed project REPL instance for the resolved target worktree
- if no instance exists, return a structured `:absent` result rather than formatted text

2. `start`
- resolves the configured start command using the existing project nREPL config helpers
- starts or ensures the managed instance for the target worktree using existing started-mode helpers
- if configuration is missing, return a structured validation/configuration error with actionable fields
- if an instance already exists and is healthy, return the existing/current instance status rather than silently creating a duplicate

3. `attach`
- attaches to an endpoint for the resolved target worktree using the existing attach helpers
- host/port handling should remain coherent with the existing attach/config behavior
- result shape should distinguish attached vs started acquisition mode clearly

4. `stop`
- stops/detaches the managed instance for the resolved target worktree using existing lifecycle helpers
- result should indicate the prior acquisition mode and whether an instance was present

5. `eval`
- evaluates `code` against the managed project REPL instance for the resolved target worktree using existing eval helpers
- result must be structured for tool consumption, including status and output channels
- this must execute in the project REPL, not psi’s own JVM eval path

6. `interrupt`
- interrupts an in-flight eval for the managed project REPL instance for the resolved target worktree using existing interrupt helpers
- result must indicate whether anything was running and whether interrupt succeeded

Structured result design:
The tool result should be stable and machine-oriented rather than command-formatted text.

Canonical top-level fields:
- `:psi-tool/action` = `:project-repl`
- `:psi-tool/project-repl-op` = one of `:status :start :attach :stop :eval :interrupt`
- `:psi-tool/worktree-path` = resolved canonical target worktree
- `:psi-tool/duration-ms`
- `:psi-tool/overall-status` = `:ok | :error`
- `:psi-tool/project-repl` = operation-specific structured payload
- optional `:psi-tool/error` for validation/config/runtime failures

Operation payload expectations:
- status/start/attach/stop payloads should expose structured instance facts, not formatted strings
- payload should reuse the canonical projected instance model where possible:
  - worktree-path
  - acquisition-mode
  - lifecycle-state
  - readiness
  - endpoint
  - active-session-id
  - last-eval summary
  - last-error summary
- eval payload should expose structured fields such as:
  - status
  - value
  - out
  - err
  - ns if available
  - timing if available
- interrupt payload should expose structured fields such as:
  - status
  - reason when relevant

Concrete result shape examples:

Status when absent:
```clojure
#:psi-tool{:action :project-repl
           :project-repl-op :status
           :worktree-path "/abs/path/to/worktree"
           :duration-ms 2
           :overall-status :ok
           :project-repl {:status :absent
                          :worktree-path "/abs/path/to/worktree"}}
```

Status when ready:
```clojure
#:psi-tool{:action :project-repl
           :project-repl-op :status
           :worktree-path "/abs/path/to/worktree"
           :duration-ms 3
           :overall-status :ok
           :project-repl {:status :present
                          :instance {:worktree-path "/abs/path/to/worktree"
                                     :acquisition-mode :started
                                     :lifecycle-state :ready
                                     :readiness true
                                     :endpoint {:host "127.0.0.1"
                                                :port 7888
                                                :port-source :dot-nrepl-port}
                                     :active-session-id "nrepl-session-id"
                                     :last-eval {:status :ok
                                                 :value "3"}
                                     :last-error nil}}}
```

Start success:
```clojure
#:psi-tool{:action :project-repl
           :project-repl-op :start
           :worktree-path "/abs/path/to/worktree"
           :duration-ms 120
           :overall-status :ok
           :project-repl {:status :started
                          :instance {:worktree-path "/abs/path/to/worktree"
                                     :acquisition-mode :started
                                     :lifecycle-state :ready
                                     :readiness true
                                     :endpoint {:host "127.0.0.1"
                                                :port 7888
                                                :port-source :dot-nrepl-port}}}}
```

Attach success:
```clojure
#:psi-tool{:action :project-repl
           :project-repl-op :attach
           :worktree-path "/abs/path/to/worktree"
           :duration-ms 15
           :overall-status :ok
           :project-repl {:status :attached
                          :instance {:worktree-path "/abs/path/to/worktree"
                                     :acquisition-mode :attached
                                     :lifecycle-state :ready
                                     :readiness true
                                     :endpoint {:host "127.0.0.1"
                                                :port 7888
                                                :port-source :explicit}}}}
```

Stop success:
```clojure
#:psi-tool{:action :project-repl
           :project-repl-op :stop
           :worktree-path "/abs/path/to/worktree"
           :duration-ms 8
           :overall-status :ok
           :project-repl {:status :stopped
                          :had-instance? true
                          :prior-acquisition-mode :started}}
```

Eval success:
```clojure
#:psi-tool{:action :project-repl
           :project-repl-op :eval
           :worktree-path "/abs/path/to/worktree"
           :duration-ms 6
           :overall-status :ok
           :project-repl {:status :ok
                          :value "3"
                          :out ""
                          :err ""
                          :ns "user"}}
```

Eval error from target REPL:
```clojure
#:psi-tool{:action :project-repl
           :project-repl-op :eval
           :worktree-path "/abs/path/to/worktree"
           :duration-ms 7
           :overall-status :error
           :project-repl {:status :eval-error
                          :out ""
                          :err "Execution error ..."}}
```

Interrupt result:
```clojure
#:psi-tool{:action :project-repl
           :project-repl-op :interrupt
           :worktree-path "/abs/path/to/worktree"
           :duration-ms 3
           :overall-status :ok
           :project-repl {:status :ok
                          :reason :interrupted}}
```

Error design:
Errors should remain intentional and structured.

Validation/configuration failures should report explicit phases such as:
- `:validate`
- `:config`
- `:project-repl`

Examples:
- missing `op`
- missing `code` for eval
- missing resolvable target worktree
- missing configured `start-command`
- attach failure
- eval against absent/unready instance

Concrete error shape examples:

Missing target worktree:
```clojure
#:psi-tool{:action :project-repl
           :project-repl-op :status
           :duration-ms 0
           :overall-status :error
           :error {:phase :validate
                   :message "psi-tool project-repl requires explicit worktree-path or invoking session worktree-path"
                   :class "clojure.lang.ExceptionInfo"}}
```

Missing eval code:
```clojure
#:psi-tool{:action :project-repl
           :project-repl-op :eval
           :worktree-path "/abs/path/to/worktree"
           :duration-ms 0
           :overall-status :error
           :error {:phase :validate
                   :message "psi-tool project-repl eval requires `code`"
                   :class "clojure.lang.ExceptionInfo"}}
```

Missing configured start command:
```clojure
#:psi-tool{:action :project-repl
           :project-repl-op :start
           :worktree-path "/abs/path/to/worktree"
           :duration-ms 1
           :overall-status :error
           :error {:phase :config
                   :message "Project nREPL start requires a configured start-command"
                   :class "clojure.lang.ExceptionInfo"
                   :data {:worktree-path "/abs/path/to/worktree"
                          :config-paths ["~/.psi/agent/config.edn"
                                         "/abs/path/to/worktree/.psi/project.edn"]}}}
```

Relationship to `/project-repl` commands:
`/project-repl` remains the human-oriented command surface.
`psi-tool` becomes the machine-oriented/project-runtime control surface.

Required coherence rule:
- `/project-repl` command handlers should use the same underlying domain helpers as `psi-tool`
- command handlers may keep text formatting and command parsing responsibilities
- `psi-tool` should not call command parsing or command-formatting code
- if current command implementation contains reusable behavior mixed with formatting, extract shared helpers rather than duplicating logic

This yields a clear layering:
- domain/runtime helpers: canonical behavior
- `/project-repl`: human text presentation over canonical behavior
- `psi-tool(action: "project-repl")`: structured machine presentation over canonical behavior

Non-goals:
- do not add generic mutation execution to `psi-tool` in this task
- do not route `psi-tool` project REPL support through slash-command parsing
- do not broaden this task into a general runtime write API
- do not reintroduce hidden cwd/focus fallback for target selection
- do not redesign the whole project REPL subsystem; reuse current lifecycle/eval/config primitives where they already embody the chosen behavior

Acceptance:
- `psi-tool` exposes canonical project REPL operations through `action: "project-repl"` and explicit `op`
- project REPL targeting is explicit by worktree and/or session-derived worktree semantics
- results are structured and stable enough for programmatic use
- `/project-repl` and `psi-tool` share coherent underlying behavior without routing through command text
- tests cover validation, targeting, lifecycle, eval, interrupt, and error paths
- docs explain the canonical `psi-tool` project REPL surface and its relationship to `/project-repl`
