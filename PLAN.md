# Note

Remove completed plan sections once finished; `PLAN.md` should contain active work only.

# Active work

## Adapter convergence: move shared TUI/Emacs domain into app-runtime

Problem:
- shared interactive semantics are currently split across `app-runtime`, `rpc`, `tui`, and `emacs-ui`
- RPC still contains substantial session/UI domain shaping rather than acting as transport over shared runtime models
- TUI and Emacs both answer the same questions in parallel:
  - what sessions/fork points are selectable and in what order
  - what footer/status information means
  - what picker/select flows exist and how they round-trip
  - what session navigation operations return and how rehydration should be shaped
- this duplication causes drift, repeated bugs, and adapter-specific fixes for what should be shared semantics

Goal:
- make `app-runtime` the shared adapter-neutral interactive domain layer for both TUI and Emacs
- reduce `rpc` to transport + protocol adaptation
- reduce `tui` and `emacs-ui` to rendering + local interaction mechanics

Target ownership:
- `app-runtime`
  - session navigation API
  - focus-scoped session operations
  - selector/picker models
  - footer semantic model
  - context snapshot/session-tree model
  - transcript rehydration package shaping
  - shared job/status summaries and canonical UI action/result vocabulary
- `rpc`
  - transport framing
  - request/response correlation
  - subscriptions/event delivery
  - adaptation of app-runtime models to RPC protocol
- `tui`
  - terminal layout and key handling
- `emacs-ui`
  - buffer rendering, minibuffer completion, overlays, local widget state

Planned extraction order:
1. shared selector/session-tree model
2. shared footer semantic model
3. shared picker/action vocabulary
4. shared navigation result model / rehydration package
5. shared background-job and context summary projections

### Phase 1 — shared selector/session-tree model

Problem:
- RPC currently shapes `/tree` selector payloads
- TUI owns parallel tree sorting/interleaving logic
- Emacs historically re-sorted or reinterpreted selector payloads

Goal:
- one adapter-neutral selector model built in `app-runtime`

Proposed shape v1:
```clojure
{:selector/kind :context-session
 :selector/prompt "Select a live session"
 :selector/active-item-id ...
 :selector/items
 [{:item/id ...
   :item/kind :session|:fork-point
   :item/parent-id ...
   :item/session-id ...
   :item/entry-id ...
   :item/display-name ...
   :item/is-active ...
   :item/is-streaming ...
   :item/worktree-path ...
   :item/created-at ...
   :item/updated-at ...
   :item/action {:action/kind :switch-session|:fork-session
                 ...}}]}
```

Planned increments:
1. extract selector query + ordering/interleave logic from RPC/TUI into app-runtime
2. make RPC `/tree` emit the app-runtime selector model unchanged except for protocol field naming
3. make TUI consume the app-runtime selector model instead of rebuilding its own tree model
4. make Emacs render selector items without deriving semantics beyond local labels if needed
5. add focused tests proving one selector model feeds both RPC and TUI semantics

Acceptance criteria:
- `/tree` ordering lives in one place only
- RPC and TUI use the same selector item order and action semantics
- Emacs does not re-sort `/tree` items

### Phase 2 — shared footer semantic model

Problem:
- RPC and TUI both assemble footer meaning from parallel query/format logic

Goal:
- one shared footer semantic model in `app-runtime`

Proposed shape v1:
```clojure
{:footer/path {...}
 :footer/model {...}
 :footer/usage {...}
 :footer/context {...}
 :footer/statuses [...]
 :footer/lines {:path-line ...
                :stats-line ...
                :status-line ...}}
```

Planned increments:
1. extract footer query + semantic projection into app-runtime
2. keep adapter-specific final rendering/layout in TUI and Emacs
3. make RPC footer events a transport projection of the shared model
4. add focused tests proving TUI/RPC footer semantics come from the same source

Acceptance criteria:
- one semantic footer model exists
- TUI and RPC no longer compute footer meaning separately

### Phase 3 — shared picker/action vocabulary

Problem:
- RPC-specific `frontend-action-requested` names (`context-session-selector`, `model-picker`, etc.) encode shared interaction semantics in transport-specific terms

Goal:
- one adapter-neutral UI action model produced by app-runtime and applied by adapters

Proposed shape v1:
```clojure
{:ui/action-id ...
 :ui/action-kind :select
 :ui/action-name :select-session|:select-model|:select-thinking-level|:select-resume-session
 :ui/prompt ...
 :ui/order :preserve|:default
 :ui/items [...]
 :ui/on-submit {:submit/kind ...}}
```

Planned increments:
1. define canonical app-runtime UI action/result model
2. make RPC frontend-action payloads thin projections of that model
3. make TUI reuse the same model for in-terminal picker flows where practical
4. reduce command-specific branching in Emacs to generic selection handling

Acceptance criteria:
- picker semantics are not invented independently in RPC and TUI
- order-sensitive selectors explicitly declare preserved order once

### Phase 4 — shared navigation result + rehydration package

Problem:
- new/resume/switch/fork flows still shape results separately across app-runtime, RPC, and adapter code

Goal:
- one navigation API returning canonical result maps and rehydration packages

Proposed operations:
- `new-session`
- `resume-session`
- `switch-session`
- `fork-session`
- `rename-session`

Proposed result shape v1:
```clojure
{:nav/op :switch-session
 :nav/session-id ...
 :nav/session-file ...
 :nav/rehydration {:messages ...
                   :tool-calls ...
                   :tool-order ...}
 :nav/context-snapshot ...
 :nav/follow-up-ui-actions [...]}
```

Planned increments:
1. extract common navigation orchestration out of RPC handlers and app-runtime closures
2. have RPC map navigation results to protocol events/responses
3. have TUI update local state from the same result model
4. remove duplicated rehydration packaging logic from RPC-local code paths

Acceptance criteria:
- one canonical navigation result model exists
- RPC is adapting results, not inventing them
- TUI session transitions consume the same semantics

### Phase 5 — shared public summaries for jobs/context/status

Goal:
- converge remaining duplicated presentation-facing summaries into app-runtime where both adapters need the same answer

Candidates:
- background job public view model
- context snapshot public summary
- extension UI/status summary projections
- stable adapter-neutral text/label seeds where useful

Acceptance criteria:
- adapter-visible summaries are computed once when semantics are shared
- adapters only format and render

Likely namespaces to add/change:
- `components/app-runtime/src/psi/app_runtime.clj`
- new adapter-neutral projection namespaces under `components/app-runtime/src/psi/app_runtime/`
  - `selectors.clj`
  - `footer.clj`
  - `ui_actions.clj`
  - `navigation.clj`
  - `projections.clj`
- `components/rpc/src/psi/rpc/session/commands.clj`
- `components/rpc/src/psi/rpc/session/frontend_actions.clj`
- `components/rpc/src/psi/rpc/events.clj`
- `components/tui/src/psi/tui/app.clj`
- `components/emacs-ui/*` only where transport projection names or assumptions must shrink

Testing strategy:
- add focused app-runtime tests for selector/footer/navigation projections
- keep adapter tests, but make them assert adaptation/rendering rather than domain ordering semantics
- prove the same app-runtime projection feeds both RPC and TUI behaviors

### adapter convergence Implementation task list

Progress so far:
- ✓ selector projection scaffold landed in `app-runtime`
- ✓ RPC `/tree` now transports the canonical selector model
- ✓ TUI `/tree` now consumes the shared selector model and no longer computes its own query-derived tree model
- ✓ Emacs now consumes the canonical selector payload from RPC while preserving backend order
- ✓ selector slice verification is green across app-runtime, RPC, TUI, and Emacs
- ✓ canonical extension UI/status projection now lives in `app-runtime.projections`
- ✓ RPC now delegates extension UI snapshots to app-runtime projections
- ✓ TUI now consumes canonical extension UI snapshots instead of raw ui-state for dialogs/widgets/notifications ordering
- ✓ Emacs now preserves backend-owned widget/status ordering instead of re-sorting it locally
- ✓ canonical background-job summaries now live in `app-runtime.background_jobs`
- ✓ canonical background-job widget/status projections now live in `app-runtime.background_job_widgets`
- ✓ runtime installs background-job UI refresh so live jobs project into shared extension UI state
- ✓ adapters now prefer background-job widget/status projection over transcript-only rendering for the convergence path
- ✓ canonical context snapshot public summary projections now live in `app-runtime.context_summary`
- ✓ Emacs context/session-tree labeling now reuses centralized summary logic rather than parallel local shaping
- ✓ Emacs tree candidate labeling now reuses the same context summary line-label logic instead of a parallel formatter

#### Phase 0 — scaffold adapter-neutral projection layer
- [x] add app-runtime projection namespaces:
  - [x] `components/app-runtime/src/psi/app_runtime/selectors.clj`
  - [x] `components/app-runtime/src/psi/app_runtime/footer.clj`
  - [x] `components/app-runtime/src/psi/app_runtime/ui_actions.clj`
  - [x] `components/app-runtime/src/psi/app_runtime/navigation.clj`
  - [x] `components/app-runtime/src/psi/app_runtime/projections.clj`
- [x] define naming/data-shape conventions for adapter-neutral projection maps
- [x] add app-runtime-focused test namespace(s) for projections and navigation models
- [x] document the rule: app-runtime exports semantic models, adapters format/render them

#### Phase 1 — selector/session-tree extraction

Status: ✓ shared selector slice implemented for `/tree`.

##### 1A. Extract selector input/query logic
- [x] identify current selector sources in:
  - [x] RPC `/tree` payload construction
  - [x] TUI session selector init/tree-sort/interleave logic
  - [x] any app-runtime helpers already computing focus-scoped session views
- [x] extract shared session/fork-point input gathering into `app_runtime.selectors`
- [x] define one canonical selector item shape with stable ids and action metadata
- [x] add focused tests for:
  - [x] root sessions + child sessions ordering
  - [x] current-session fork-point interleaving
  - [x] active-session marking
  - [x] streaming-session marking
  - [x] missing-parent fallback behavior

##### 1B. Migrate RPC to selector model adaptation only
- [x] replace RPC-local `/tree` payload shaping with app-runtime selector projection
- [x] reduce `components/rpc/src/psi/rpc/session/commands.clj` to transport projection / command handling only
- [x] ensure RPC tests assert projection adaptation, not local ordering logic
- [x] remove RPC-local selector ordering helpers once the shared projection is in use

##### 1C. Migrate TUI to shared selector model
- [x] replace TUI-local tree-sort/interleave logic with app-runtime selector projection consumption
- [x] adapt TUI rendering to shared selector item fields rather than TUI-local derived tree metadata where possible
- [x] keep terminal-specific connector/prefix rendering in TUI only
- [x] update TUI tests to prove it consumes shared selector order unchanged

##### 1D. Tighten Emacs to pure rendering/adaptation
- [x] keep Emacs `/tree` completion order-preserving
- [x] reduce any remaining Emacs selector semantics to label rendering only
- [x] switch Emacs to backend/app-runtime-supplied canonical labels for `/tree` when present

##### Phase 1 concrete coding slice

Goal of the first implementation slice:
- land one shared selector projection in `app-runtime`
- make RPC and TUI consume it
- leave Emacs as order-preserving rendering only
- delete duplicated ordering/interleave helpers immediately after migration

###### Proposed namespace + public functions
- [x] create `components/app-runtime/src/psi/app_runtime/selectors.clj`
- [x] expose a small public surface:
  - [x] `context-session-selector`
    - inputs: `ctx`, active/focus session id
    - returns the canonical selector map for `/tree`
  - [x] `context-session-selector-items`
    - returns just ordered selector items for adapters that only need the list
  - [x] `selector-item->default-label`
    - optional shared label seed if adapters should stop inventing labels independently
- [x] keep helper fns private behind that public surface

###### Proposed private helpers in `app_runtime.selectors`
- [x] `session->selector-item`
  - normalize a context session map into canonical selector item shape
- [x] `fork-point-entry->selector-item`
  - normalize a journal entry into canonical fork-point item shape
- [x] `current-session-fork-point-items`
  - gather fork-point items from current session journal
- [x] `tree-sort-session-items`
  - order sessions by parent/child tree structure while preserving sibling input order
- [x] `interleave-current-session-fork-points`
  - insert current-session fork points after the current session's descendant subtree
- [x] `ensure-current-session-present`
  - inject current session into selector source set when context index does not yet include it
- [x] `mark-selector-item-state`
  - mark active/streaming/item action metadata without adapter-specific fields

###### Canonical item/result shape for phase 1
- [x] use stable item ids that do not overload `session-id`
  - [x] session item id: `[:session <session-id>]`
  - [x] fork item id: `[:fork-point <entry-id>]`
- [x] keep explicit fields:
  - [x] `:item/id`
  - [x] `:item/kind`
  - [x] `:item/parent-id`
  - [x] `:item/session-id`
  - [x] `:item/entry-id`
  - [x] `:item/display-name`
  - [x] `:item/is-active`
  - [x] `:item/is-streaming`
  - [x] `:item/worktree-path`
  - [x] `:item/created-at`
  - [x] `:item/updated-at`
  - [x] `:item/action`
- [x] keep action metadata explicit:
  - [x] session item action: `{:action/kind :switch-session :session-id ...}`
  - [x] fork item action: `{:action/kind :fork-session :session-id ... :entry-id ...}`

###### Exact source migrations
- [x] move logic out of RPC:
  - [x] `tree-sort-sessions`
  - [x] `current-session-fork-points`
  - [x] `interleave-current-session-fork-points`
  - [x] `session-selector-payload`
  - from: `components/rpc/src/psi/rpc/session/commands.clj`
- [x] move logic out of TUI:
  - [x] `context-session->selector-session`
  - [x] `tree-sort-context-sessions`
  - [x] `current-session-user-prompt-items`
  - [x] `interleave-current-session-prompts`
  - [x] the selector-specific part of `session-selector-init-from-context`
  - from: `components/tui/src/psi/tui/app.clj`
- [x] keep only adapter-specific logic in TUI:
  - [x] tree connector rendering
  - [x] selected-index / cursor movement
  - [x] terminal label layout
- [x] keep only protocol adaptation in RPC:
  - [x] transport the canonical selector model directly to Emacs for frontend-action payloads
- [x] keep only label rendering/completion behavior in Emacs

###### Concrete migration order
- [x] step 1: add `app_runtime.selectors` with pure helper tests only
- [x] step 2: switch RPC `/tree` payload construction to use `app_runtime.selectors/context-session-selector`
- [x] step 3: switch TUI context-session selector init to use the shared app-runtime selector model
- [x] step 4: update Emacs/RPC/TUI tests to assert shared-order consumption
- [x] step 5: delete duplicated RPC/TUI ordering/interleave helpers

###### Focused test plan for the slice
- [x] add `components/app-runtime/test/psi/app_runtime/selectors_test.clj`
- [x] first tests to write:
  - [x] `context-session-selector-orders-root-and-child-sessions-test`
  - [x] `context-session-selector-interleaves-current-session-fork-points-after-descendants-test`
  - [x] `context-session-selector-ensures-current-session-present-when-missing-from-index-test`
  - [x] `context-session-selector-marks-active-and-streaming-items-test`
  - [x] `context-session-selector-uses-stable-item-ids-test`
- [x] adapter proof tests to keep/update:
  - [x] RPC test proving `/tree` payload order follows shared selector projection
  - [x] TUI test proving selector init preserves shared order
  - [x] Emacs test proving candidates preserve incoming order

###### Acceptance proof for phase 1 slice
- [x] one failing app-runtime selector test first
- [x] then one failing RPC adapter test switched to shared projection
- [x] then one failing TUI adapter test switched to shared projection
- [x] finally remove duplicated helpers and prove all focused tests green

###### Phase 1 done means
- [x] no selector ordering/interleave logic remains in RPC
- [x] no selector ordering/interleave logic remains in TUI
- [x] `/tree` item order comes from one app-runtime projection only
- [x] Emacs preserves, rather than derives, selector order

#### Phase 2 — footer semantic model extraction

##### 2A. Extract shared footer data/query model
- [x] identify all footer query/projection logic currently split between RPC and TUI
- [x] extract one canonical footer semantic projection into `app_runtime.footer`
- [x] define stable footer sections:
  - [x] path/worktree/git
  - [x] model/thinking
  - [x] usage/cost
  - [x] context/compaction
  - [x] statuses
- [x] add focused tests for semantic footer data independent of adapter width/layout

##### 2B. Migrate RPC footer events
- [x] make RPC footer payload a transport projection of the app-runtime footer model
- [x] remove RPC-local footer semantic assembly helpers once replaced
- [x] keep only RPC event naming/framing concerns in RPC

##### 2C. Migrate TUI footer rendering
- [x] make TUI read the shared footer model
- [x] keep width-aware line wrapping/alignment in TUI only
- [x] update TUI tests to assert rendering from canonical footer semantics

#### Phase 3 — shared picker/action vocabulary

##### 3A. Define canonical UI action model
- [x] define adapter-neutral action kinds in `app_runtime.ui_actions`
- [x] define canonical fields for:
  - [x] action id
  - [x] action kind
  - [x] prompt
  - [x] order policy
  - [x] items
  - [x] submit contract
- [x] define result payload shapes for submitted/cancelled/failed actions
- [x] add focused tests for action model construction

##### 3B. Migrate RPC frontend-action flows
- [x] make RPC `frontend-action-requested` a projection of app-runtime action maps
- [x] make RPC frontend action result handling route through shared submit/apply semantics where possible
- [x] shrink transport-specific naming leaks like `context-session-selector` toward canonical action names

##### 3C. Reuse action model in TUI and Emacs
- [x] map TUI picker flows onto canonical action model where practical
- [x] make Emacs generic selection handling consume canonical action model
- [x] reduce per-command selection branching in adapters

#### Phase 4 — shared navigation result + rehydration model

##### 4A. Extract canonical navigation operations
- [x] define app-runtime navigation API wrappers for:
  - [x] new session
  - [x] resume session
  - [x] switch session
  - [x] fork session
  - [ ] rename session
- [ ] make operations accept adapter-owned focus getter/setter rather than owning focus internally
- [x] define canonical navigation result maps with:
  - [x] target session identity
  - [x] rehydration package
  - [x] context snapshot
  - [x] follow-up action hints
- [x] add focused tests for navigation result semantics
- [x] decision: `rename session` is not a navigation operation
  - keep rename on the command/mutation path as metadata update
  - do not force rename into `app_runtime.navigation`

##### 4B. Migrate RPC navigation handlers
- [x] replace RPC-local navigation result shaping with app-runtime navigation results
- [x] keep only protocol emission in RPC navigation paths
- [x] remove duplicate rehydration package construction from RPC-local code where possible
- [x] route frontend-action navigation submissions through shared navigation emission

##### 4C. Migrate TUI session transitions
- [x] have TUI session-switch/new/fork flows consume canonical navigation result maps
- [x] keep only TUI-local state updates/render effects outside the shared result semantics
- [x] allow TUI transition restore helpers to accept legacy payloads or canonical nav results during migration

#### Phase 5 — shared public summaries for jobs/context/status
- [x] identify remaining duplicated presentation-facing summaries across RPC and TUI
- [x] extract canonical public summary projections into app-runtime for:
  - [x] background jobs
  - [x] context snapshot summaries
  - [x] extension UI/status summaries where shared
- [x] adapt RPC and TUI to consume shared summaries
- [~] remove remaining transport/adapter-local semantic duplication
  - [x] Emacs tree candidate labels now reuse context summary line labels
  - [ ] inspect remaining footer/status diagnostics shaping for shared-vs-local ownership
  - [ ] inspect RPC handshake/bootstrap shaping for any remaining duplicate summary semantics

#### Immediate follow-on checklist
- [x] remove duplicate RPC navigation emission helper and route all RPC session navigation emission through `psi.rpc.session.emit/emit-navigation-result!`
- [x] extract canonical context snapshot projection into `app-runtime` and make `rpc.events/context-updated-payload` delegate to it
- [x] extract canonical transcript rehydration message source into `app-runtime` and remove `app-runtime.navigation` dependency on `psi.rpc.session.message-source`
- [x] update focused app-runtime/RPC tests to prove shared navigation/context/rehydration projections
- [x] refresh `mementum/state.md` after the convergence cleanup lands
- [ ] refresh user/docs references if `/jobs` and context/session-tree ownership should now be described in terms of shared app-runtime projections

#### Remaining convergence cleanup plan
- [x] footer semantics: stop making Emacs re-parse canonical `:stats-line` for provider/model alignment
  - [x] expose structured footer stats parts from `app-runtime.footer` through RPC (`usage/context` left side + model right side)
  - [x] make Emacs footer alignment consume structured footer fields first, keeping string parsing only as fallback compatibility
  - [x] add focused RPC + Emacs tests proving footer alignment no longer depends on regex parsing of `:stats-line`
- [x] session summary semantics: extract a shared session-summary projection for frontend diagnostics/header reuse
  - [x] add adapter-neutral session-summary/model-label projection in `app-runtime`
  - [x] make `rpc.events/session-updated-payload` include shared summary fields
  - [x] make Emacs `/status` session summary + header model label consume shared summary fields rather than rebuilding them locally
- [x] RPC startup convergence: remove handshake/bootstrap duplication with subscribed snapshot delivery
  - [x] keep handshake transport-focused (`server-info` only)
  - [x] rely on subscribed initial snapshot emission for `session/updated` / `footer/updated` / `context/updated`
  - [x] delete the handshake bootstrap `context/updated` event path and adjust startup tests accordingly

#### Cross-cutting cleanup tasks
- [~] remove obsolete duplicated helpers after each phase instead of leaving parallel paths
- [ ] keep adapter tests focused on adaptation/rendering, not shared-domain ordering rules
- [ ] update docs after each phase to reflect migrated ownership
- [ ] ensure app-runtime remains adapter-neutral and does not absorb transport-specific concerns
- [ ] avoid moving Emacs/TUI rendering mechanics into app-runtime; move semantics only

#### Suggested execution order
- [ ] phase 0 scaffold
- [ ] phase 1 selector/session-tree
- [ ] phase 2 footer
- [ ] phase 3 picker/action vocabulary
- [ ] phase 4 navigation + rehydration
- [ ] phase 5 shared public summaries

## Prompt lifecycle architectural convergence

Active prompt lifecycle shape:
- `prompt-in!`
- `:session/prompt-submit`
- `:session/prompt`
- `:session/prompt-prepare-request`
- `:runtime/prompt-execute-and-record`
- `:session/prompt-record-response`
- `:session/prompt-continue` | `:session/prompt-finish`

Testing priorities:
- prove initial prompt path logs `prompt-submit -> prompt-prepare-request -> prompt-record-response`
- prove assistant messages are journaled exactly once through `prompt-record-response`
- prove tool-use turns route through `:session/prompt-continue`
- prove continuation re-enters the shared prepare path after tool execution

Goals:
- make prompt lifecycle follow the same architectural transaction shape as tool execution:
  - prepare -> execute -> record
- make request preparation the explicit home for:
  - prompt layer assembly
  - cache breakpoint projection
  - provider request shaping
  - skill / profile prelude injection
- reduce prompt semantics currently split across:
  - `system_prompt.clj`
  - `conversation.clj`
  - `executor.clj`
  - extension-local string composition

Planned increments:
1. extract a pure prepared-request projection from canonical session state
2. introduce dispatch-visible `:session/prompt-prepare-request`
3. make execution consume the prepared artifact instead of ambient recomputation
4. introduce deterministic `:session/prompt-record-response`
5. move continuation / terminalization decisions onto dispatch-visible events
6. split prompt continuation into dispatch-visible prepare / execute / record follow-on steps
7. collapse execute -> record into an explicit runtime execute-and-record boundary callable from prepare
8. converge agent profile / skill injection into request preparation

Proposed handler / effect split:
- `:session/prompt-submit`
  - handler:
    - validate / normalize submitted user message
    - return journal-append effect and submitted-turn metadata
  - effects:
    - `:persist/journal-append-message-entry`
- `:session/prompt-prepare-request`
  - handler:
    - read canonical session state + journal
    - project prepared request artifact
    - return prepared request in `:return` and optionally store a bounded projection in session state for introspection
  - effects:
    - `:runtime/prompt-execute-and-record`
- `:runtime/prompt-execute-and-record`
  - runtime boundary:
    - perform provider streaming against the prepared request artifact
    - capture request/response telemetry
    - dispatch `:session/prompt-record-response`
- `:session/prompt-record-response`
  - handler:
    - accept shaped execution result
    - append assistant message / usage / turn metadata deterministically
    - derive continuation decision from recorded result
  - effects:
    - journal append effects
    - follow-on dispatch effects for continue / finish
- `:session/prompt-continue`
  - handler:
    - route tool execution continuation from canonical recorded state
  - effects:
    - runtime tool continuation boundary
    - next prompt preparation event
- `:session/prompt-finish`
  - handler:
    - finalize turn lifecycle visibility in canonical state
  - effects:
    - background-job reconciliation / terminal emission
    - statechart completion event if needed

Proposed code scaffold:
- new namespace: `psi.agent-session.prompt-request`
  - `journal->provider-messages`
  - `session->request-options`
  - `build-prompt-layers`
  - `build-prepared-request`
- new namespace: `psi.agent-session.prompt-runtime`
  - `execute-prepared-request!`
- new namespace: `psi.agent-session.prompt-recording`
  - `extract-tool-calls`
  - `classify-execution-result`
  - `build-record-response`
- new ctx callbacks:
  - `:build-prepared-request-fn`
  - `:execute-prepared-request-fn`
  - `:build-record-response-fn`
- new dispatch handlers:
  - `:session/prompt-prepare-request`
  - `:session/prompt-record-response`
  - `:session/prompt-continue`
  - `:session/prompt-finish`
- new runtime effects:
  - `:runtime/prompt-execute-and-record`
  - `:runtime/prompt-continue-chain`

Prepared-request v1 shape:
```clojure
{:prepared-request/id turn-id
 :prepared-request/session-id session-id
 :prepared-request/user-message user-message
 :prepared-request/session-snapshot {:model ...
                                     :thinking-level ...
                                     :prompt-mode ...
                                     :cache-breakpoints ...
                                     :active-tools ...}
 :prepared-request/prompt-layers [{:id :system/base
                                   :kind :system
                                   :stable? true
                                   :content ...}]
 :prepared-request/system-prompt ...
 :prepared-request/system-prompt-blocks ...
 :prepared-request/messages ...
 :prepared-request/tools ...
 :prepared-request/model ...
 :prepared-request/ai-options ...
 :prepared-request/cache-projection {:cache-breakpoints ...
                                     :system-cached? ...
                                     :tools-cached? ...
                                     :message-breakpoint-count ...}
 :prepared-request/provider-conversation ...}
```

Execution-result v1 shape:
```clojure
{:execution-result/turn-id ...
 :execution-result/session-id ...
 :execution-result/prepared-request-id ...
 :execution-result/assistant-message ...
 :execution-result/usage ...
 :execution-result/provider-captures {:request-captures ...
                                      :response-captures ...}
 :execution-result/turn-outcome ...
 :execution-result/tool-calls ...
 :execution-result/error-message ...
 :execution-result/http-status ...
 :execution-result/stop-reason ...}
```

## LSP integration on top of managed services + post-tool processing

Completed foundation:
- structured `write` and `edit` tool results now include `:meta`, `:effects`, and `:enrichments`
- ctx-owned managed subprocess service registry exists
- additive timeout-bounded post-tool processor runtime exists
- tool execution now applies post-tool processing before result recording/provider return
- services, post-tool processors, and telemetry are queryable through EQL
- extension API exposes post-tool processor registration and managed service hooks
- mutation layer supports post-tool and service operations
- shared stdio / JSON-RPC helper layer exists for managed services

Achieved so far:
- LSP ownership now lives in the extension layer, not core
- `extensions.lsp` now owns:
  - default `clojure-lsp` config
  - nearest-`.git` workspace root detection
  - workspace-keyed services via `[:lsp workspace-root]`
  - extension-owned `ensure-service` / `stop-service` use
  - JSON-RPC initialize / initialized flow
  - document sync via `didOpen` and `didChange`
  - document close on workspace restart via `didClose`
  - diagnostics requests via `textDocument/diagnostic`
  - additive diagnostics enrichments + provider-facing appended content
  - non-fatal error shaping for LSP/runtime failures
  - command surfaces: `lsp-status` and `lsp-restart`
- managed service runtime now supports protocol-tagged stdio JSON-RPC attachment
- live stdio JSON-RPC correlation is proven with a babashka fixture subprocess
- the LSP extension service spec now explicitly opts into `:protocol :json-rpc`
- focused tests now cover:
  - extension service spec
  - runtime attachment through mutation path
  - live JSON-RPC roundtrip correlation
  - diagnostics projection
  - command/status/restart behavior

Active goals:
- increase confidence that the LSP extension uses the real runtime path wherever practical
- reduce reliance on nullable response stubs for diagnostics-oriented extension tests
- decide how much JSON-RPC adapter debug instrumentation should remain as permanent observability surface
- expose enough service/telemetry state to debug LSP startup and request flow

Planned increments:
1. decide whether adapter debug atoms remain test-only helpers or become queryable runtime telemetry
2. simplify overlapping live/debug tests now that babashka-backed roundtrip proof exists

Completed in this slice:
- added runtime-backed LSP extension tests using a babashka LSP fixture subprocess
- proved initialize -> initialized -> didOpen -> diagnostic flow through the live managed-service mutation path
- proved subsequent sync uses `didChange` for already-open tracked documents
- proved workspace restart closes tracked documents, clears initialization state, and respawns the service
- proved post-tool handler adds diagnostics enrichments/content append and shapes non-fatal failure output
- proved workspace status rendering reflects live service state and tracked documents
- proved restart-flow diagnostics continuity by re-syncing after restart and observing fresh initialize + diagnostic requests
- reduced the LSP runtime-path slice's dependence on nullable response stubs by exercising the real managed-service path for trace-oriented flows

Likely namespaces to add/change next:
- `extensions.lsp`
- `psi.agent-session.service-protocol`
- `psi.agent-session.service-protocol-stdio-jsonrpc`
- `psi.agent-session.post-tool`
- `psi.agent-session.services`
- LSP extension tests using real mutation/runtime path

Acceptance criteria:
- `clojure-lsp` can be ensured as a managed service for a workspace key
- protocol-tagged stdio services attach live JSON-RPC runtime automatically through the mutation path
- `write` / `edit` can trigger LSP-backed diagnostics within timeout
- diagnostics appear as structured enrichments and usable provider-facing content
- timeout or LSP failure does not fail the underlying tool result by default
- service state and telemetry are queryable enough to debug LSP behavior

## Canonical dispatch pipeline trace / observability

Problem:
- dispatch is the architectural coordination boundary, but observability is still split across lifecycle events, service telemetry, post-tool telemetry, debug atoms, and ad hoc runtime hooks
- there is no single canonical, queryable, end-to-end trace linking:
  - dispatch event receipt
  - interceptor stages
  - handler result
  - emitted effects
  - effect execution
  - service request/response
  - final completion/failure

Goal:
- make dispatch observability consistent with the current architecture by introducing one canonical, queryable dispatch trace model tied to the dispatch pipeline itself

Desired properties:
- one `dispatch-id` / trace id per dispatched event
- bounded queryable runtime state
- trace entries correlated by:
  - `dispatch-id`
  - `session-id`
  - `event-type`
  - `tool-call-id` where relevant
  - service key / request id where relevant
- useful for debugging, replay gaps, and architectural introspection

Trace entry candidates:
- `:dispatch/received`
- `:dispatch/interceptor-enter`
- `:dispatch/interceptor-exit`
- `:dispatch/handler-result`
- `:dispatch/effects-emitted`
- `:dispatch/effect-start`
- `:dispatch/effect-finish`
- `:dispatch/service-request`
- `:dispatch/service-response`
- `:dispatch/completed`
- `:dispatch/failed`

Planned increments:
1. define canonical trace entry schema + bounded runtime storage ✅
2. generate a `dispatch-id` at dispatch entry and thread it through opts/context ✅
3. record interceptor enter/exit around the existing dispatch pipeline ✅
4. record handler result + emitted effects summary ✅
5. record effect execution start/finish and associate back to `dispatch-id` ✅
6. record service request/response summaries from the managed-service protocol layer ✅
7. expose trace surface through EQL ✅
8. add focused tests proving one dispatched event yields a coherent trace chain ✅
9. add focused failure/restart-path coverage for dispatch + LSP trace surfaces ✅
10. route post-tool flows through the canonical dispatch pipeline ✅

Current implemented surface:
- bounded canonical trace storage in `psi.agent-session.dispatch`
- stable explicit `dispatch-id` threading without dynamic vars
- trace entry kinds currently recorded:
  - `:dispatch/received`
  - `:dispatch/interceptor-enter`
  - `:dispatch/interceptor-exit`
  - `:dispatch/handler-result`
  - `:dispatch/effects-emitted`
  - `:dispatch/effect-start`
  - `:dispatch/effect-finish`
  - `:dispatch/service-request`
  - `:dispatch/service-response`
  - `:dispatch/service-notify`
  - `:dispatch/completed`
  - `:dispatch/failed`
- post-tool flows now route through dispatch as `:session/post-tool-run` rather than appending ad hoc top-level trace entries
- nested managed-service request/response/notify activity now inherits the enclosing dispatch-owned `dispatch-id` for post-tool LSP flows
- EQL resolver surface:
  - `:psi.dispatch-trace/count`
  - `{:psi.dispatch-trace/recent [...]}`
  - `{:psi.dispatch-trace/by-id [...]}`
  - includes `:psi.dispatch-trace/interceptor-id`
- focused LSP integration tests now prove one dispatch-owned post-tool sync flow yields a coherent trace chain queryable by `dispatch-id`
- focused failure-path tests now cover:
  - handler exception behavior
  - effect execution failure trace recording
  - service error response trace recording
  - post-tool timeout/error shaping without failing the underlying tool result
  - restart-flow initialize/diagnostic continuity

Remaining next increments:
- decide whether LSP findings should remain result-integrated data or become first-class dispatch events of their own
- broaden dispatch-owned tracing to additional slices beyond the current tool/post-tool/service path
- decide whether service lifecycle transitions (`ensure`/`stop`/`restart`) need explicit canonical trace entries

Likely namespaces to add/change next:
- `psi.agent-session.dispatch`
- `psi.agent-session.dispatch-effects`
- `psi.agent-session.dispatch-handlers`
- `psi.agent-session.service-protocol`
- `psi.agent-session.service-protocol-stdio-jsonrpc`
- new resolver namespace for dispatch trace introspection

Acceptance criteria:
- every dispatched event gets a stable `dispatch-id`
- interceptor/handler/effect/service stages can be queried by `dispatch-id`
- one tool/prompt/service flow can be followed end-to-end from the query surface
- trace storage is bounded and safe for long-running sessions
- the new trace surface reduces dependence on ad hoc debug atoms for normal diagnosis

## Agent tool skill prelude follow-on

- Add a `:skill` argument to the `agent` tool.
- When used without fork, the specified skill should be read and injected as a synthetic sequence in the spawned session context:
  - a synthetic "use the skill" user message
  - the skill content
  - the effective prompt
  - the corresponding assistant reply
- Insert a cache breakpoint so the reusable skill prelude is separated from the variable tail of the conversation.
- Goal: reduce end-of-conversation breakpoints from 3 to 2 for this flow.
- Expected benefit: better caching for repeated prompts that reuse the same skill.
