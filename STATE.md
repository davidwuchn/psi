# State

Current truth about the Psi system.

---

- ✓ Session-switch rehydration now renders user messages correctly in Emacs (2026-03-15, commit `6d9a07c`): `psi-emacs--role-is-user-p` helper accepts all three role representations (`"user"` string from backend, bare symbol `user` from `intern`, keyword `:user` from existing callers); both `psi-emacs--message->transcript-line` and `psi-emacs--replay-session-messages` now use it. Root cause was `(intern "user")` → `user` ≠ `:user`, so every replayed message fell through to the assistant branch. Two regression ERT tests added; 209/209 passing.
- ✓ PSL follow-up for `fdf7ed0` converged the graph Allium spec to the live Step 7 graph discovery surface (2026-03-15): `spec/graph-emergence.allium` now models capability-membership edges instead of a richer dependency-style graph, includes root discovery attrs (`:psi.graph/root-seeds`, `:psi.graph/root-queryable-attrs`), adds normalized domain coverage with `operation_count`, and records graph invariants for node uniqueness, edge endpoint validity, resolver/mutation symbol-to-node coverage, and capability/domain-coverage alignment. The spec was validated with `allium check spec/graph-emergence.allium`, so implementation/tests/docs/spec now share one graph contract vocabulary.
- ✓ Good fix baseline re-established from commit `57e8ab0` (2026-03-15): only the strongly evidenced fixes from the unstable PSL/debug loop are being carried forward for now — isolated introspection registration deduplication and git merge success verification via target HEAD movement. The former Datalevin-provider stabilization path is no longer part of the active system after provider removal.
- ✓ PSL follow-up for `1c916b9` converged memory-provider removal into repo state (2026-03-15): Datalevin-backed memory is no longer part of the runtime/spec/docs surface; `psi.memory.store` remains as the provider-extension boundary, but the only supported active store is now `in-memory`.
- ✓ PSL follow-up for `708d729` converged isolated session-backed introspection registration with the live session query surface (2026-03-15): `components/introspection/src/psi/introspection/core.clj` now registers `session-resolver-surface` when an introspection context carries `:agent-session-ctx`, instead of registering only `psi.agent-session` local resolvers. This restores cross-domain session-root attrs like `:psi.memory/status` and makes introspection graph summaries include history/worktree resolvers just as live session-root queries do.
- ✓ Agent-session runtime/command/test churn from the follow-up debug loop has been reverted, leaving a cleaner repository state for the next convergence pass.
- ✓ Current Clojure unit-suite baseline after the introspection surface repair is green: `851 tests`, `5178 assertions`, `0 failures` via `bb clojure:test:unit`.
- ✓ GitHub Actions CI surface now exists (2026-03-15, commit `6cba430`): `.github/workflows/ci.yml` runs on manual dispatch, push to `master`, and PRs to `master` with separate `format`, `lint`, and `test` jobs on Temurin JDK 25. Repository task surface now includes `bb fmt:check`, `bb lint`, and `bb test` as the intended CI entrypoints.
- ✓ `bb lint` is now clean (2026-03-15, commit `8039763`): 0 errors, 0 warnings. Fixed 17 files across unused bindings, redundant lets, unused/missing requires, dead private vars/fns, and redundant `do`. Added `.clj-kondo/config.edn` to suppress third-party `.clj-kondo/imports/` noise. Unit suite remains green: 851 tests, 4775 assertions, 0 failures.
- ✓ `bb fmt:check` is now green (2026-03-15, commits `b07bde5` + `8b659c8`): pre-commit hook (`cljfmt-fix`) runs `cljfmt fix` on staged Clojure files and restages them; all existing files were reformatted in the same pass. `doc/develop.md` documents pipx install, `pre-commit install` onboarding, hook behaviour, and manual run commands.
- ✓ pre-commit `clj-kondo-lint` hook is active (2026-03-15, commits `accb233` + `59282ab`): `clj-kondo --cache false --lint` runs on every staged Clojure file before commit; any warning or error blocks the commit. Root `.clj-kondo/config.edn` is the canonical source of macro-alias hints for individual-file linting (Pathom3 `defresolver`/`defmutation`, Guardrails `>defn`, Malli, Promesa, Potemkin). `doc/develop.md` documents both hooks with prerequisites, rationale, bbin install commands, and manual run examples.
- ✓ CI workflow restructured (2026-03-15, commit `bace7ca`): `check` (fmt + lint) now gates two parallel jobs — `clojure-test` (`bb clojure:test`) and `emacs-test` (`bb emacs:check` with `emacs-nox`). Maven/Clojure dep cache keyed on `deps.edn` + `bb.edn`. `doc/develop.md` updated with CI job graph and cache strategy.
- ✓ CI `check` job now installs `cljfmt` and `clj-kondo` binaries from GitHub Releases (2026-03-15, commit `1e363b3`): both tools are bbin-local installs not on the default runner PATH; the `check` job now fetches the latest release tarball/zip for each and installs them into `/usr/local/bin` before running `bb fmt:check` and `bb lint`. Pattern: resolve latest tag via GitHub API → download static binary → install to system PATH.
- ✗ `bb test` green on CI remains outstanding — requires re-baselining test suite (Step 15e).

- ✓ Layer 1 git worktree attach semantics are now explicit in spec/tests (2026-03-14, commit `c8b2573`): `spec/git-worktree-mutations.allium` now distinguishes creating a new branch worktree from attaching a worktree to an existing branch, and `components/history/test/psi/history/git_test.clj` now locks the real git constraint that an existing branch cannot be attached while it is still checked out in another worktree but can be attached once that worktree is removed. Focused verification green at 9 tests / 39 assertions across `psi.history.git-test/worktree-add-attaches-existing-branch-when-create-branch-false` and `extensions.work-on-test`.
- ✓ `/work-merge` now preserves the worktree unless merge success is verified and reports why verification failed (2026-03-14, commit `12ab9d4`): the extension no longer removes linked worktrees immediately after a merge mutation claims success. It now verifies from the main worktree that the feature branch tip is actually ancestor of target HEAD before cleanup, and only then removes the worktree / deletes the branch. When verification fails, `/work-merge` reports the target branch, source branch, merge-reported flag, merge error payload, and verification reason, while preserving the worktree for recovery. Regression coverage in `extensions/test/extensions/work_on_test.clj` now locks both the safety gate and the richer diagnostic output; focused verification green at 8 tests / 37 assertions in `extensions.work-on-test`.
- ✓ AGENTS worktree/debug guidance now encodes explicit review/simplify and bug-fix proof steps (2026-03-14, commit `c75eb04`): `change_chain` now requires `review(code spec tests)` and `simplify(code spec tests)` before coherence verification, and `λ fix(bug)` now explicitly routes through `add_test_coverage` after a structural patch. This tightens prompt-memory guidance for the exact worktree/merge debugging loop: fix root cause, prove it with tests, then simplify before declaring convergence.
- ✓ `/work-merge` verification failures now report target branch state before and after the merge attempt (2026-03-14, commit `1c40ffb`): the extension now includes `before-branch`, `after-branch`, `before-head`, `after-head`, and `head-changed` in the safety-failure message when merge verification fails. This makes false-positive merge reports diagnosable in live repos by showing whether the merge ran on the intended target branch and whether the target HEAD moved at all before cleanup was gated. Regression coverage in `extensions/test/extensions/work_on_test.clj` now locks the richer target-head diagnostic payload; focused verification green at 8 tests / 42 assertions in `extensions.work-on-test`.
- ✓ `/work-on` now preserves the existing-branch attach flag across the extension mutation boundary (2026-03-14, commit `5ce1086`): the live isolated-qctx mutation path was still failing with `branch already exists` even after `/work-on` retried attach mode, because the extension sent `:create_branch false` while the history git layer only honored `:create-branch`. `history/git.clj` now accepts both key spellings at the boundary, `/work-on` now emits canonical `:create-branch`, and integrated regression coverage in `components/agent-session/test/psi/agent_session/core_test.clj` now proves the real isolated extension mutation path can attach a worktree to an existing branch. Focused verification green at 9 tests / 36 assertions across `psi.agent-session.core-test/register-mutations-in!-includes-history-mutations-test` and `extensions.work-on-test`.
- ✓ `/work-on` now attaches a sibling worktree to an existing slug branch instead of failing on `branch already exists` (2026-03-14, commit `0644903`): when the deterministic slug branch already exists but the sibling worktree path does not, the extension now retries `git.worktree/add!` with attach mode and creates a worktree bound to the existing branch. `spec/work-on-extension.allium` now encodes both the attach-existing-branch behavior and the existing-registered-worktree reuse path explicitly. Regression coverage in `extensions/test/extensions/work_on_test.clj` now locks attach-to-existing-branch and existing-worktree/session reuse behavior; focused verification green at 8 tests / 32 assertions.
- ✓ `/work-merge` now merges linked branches from the main worktree context before cleanup (2026-03-14, commit `ae22cb1`): the extension now resolves the default branch and executes `git.branch/merge!` with `:git/context {:cwd main_wt.path}` instead of running the merge from the linked feature worktree. This fixes the false-success path where `/work-merge` reported `Merged <branch> into <default>` while actually merging the branch into itself, then removed the worktree. Regression coverage in `extensions/test/extensions/work_on_test.clj` now locks the main-worktree merge context explicitly; focused verification green at 8 tests / 30 assertions.
- ✓ Session worktree surfaces now converge across persistence, resolvers, RPC, TUI, and Emacs resume/tree selectors (2026-03-14, commit `62c03f7`): persisted session info now keeps explicit worktree identity under test, resolver joins expose `:psi.session-info/worktree-path` alongside normalized `:psi.session-info/cwd`, `host/updated` RPC payloads now include per-session `:worktree-path`, TUI tree rows render worktree paths inline, and Emacs `/resume` labels now disambiguate sessions by description + worktree path + session file. Regression coverage now locks worktree-aware resume/list/tree behavior across persistence, core resume, resolvers, RPC, TUI, Emacs, and work-on extension tests; focused verification green at 35 assertions across the added resolver/RPC/TUI checks, with broader worktree persistence/resume suites also green.
- ✓ `/work-on` no longer carries stale rendered cwd/worktree footer text into newly-created worktree sessions (2026-03-14, commit `d527981`): the extension stopped passing the current fully-rendered `:system-prompt` into `create-session`, so a new worktree session no longer inherits prompt text that was assembled under the previous repo path. This keeps prompt-visible cwd/worktree metadata aligned with the session’s own worktree instead of overwriting fresh session prompt state with a stale parent-session render. Focused verification remains green in `extensions/test/extensions/work_on_test.clj`.
- ✓ `/work-on` isolated extension query path now resolves worktree attrs correctly (2026-03-14, commit `4386b98`): `register-resolvers-in!` now registers the full `session-resolver-surface`, so isolated extension EQL queries include history resolvers as well as agent-session resolvers. This fixes `/work-on` falsely reporting `not inside a git repository` when invoked from a real repo through the extension `:query` path. Regression coverage now locks isolated-qctx worktree attr resolution in `components/agent-session/test/psi/agent_session/resolvers_test.clj`; focused verification green at 6 tests / 22 assertions.
- ✓ `/work-on` now reuses existing sibling worktrees instead of failing on path collisions (2026-03-14, commit `d8dedda`): when the mechanical slug points at an already-existing linked worktree, the extension now treats that as resumable state rather than as a hard failure. It locates the registered git worktree at that path, switches to an existing host session when present, or creates a new session bound to that worktree when absent. Regression coverage now locks the existing-worktree reuse path in `extensions/test/extensions/work_on_test.clj`; focused verification green at 4 tests / 14 assertions.
- ✓ Worktree-bound prompt runtime metadata now explicitly names the active worktree directory (2026-03-14, commit `e33d7bd`): system prompt assembly now includes both `Current working directory` and `Current worktree directory`, and session new/resume retargeting refreshes both lines when the active worktree changes. This keeps prompt-visible runtime metadata aligned with worktree-bound session state instead of leaving only one stale cwd hint after `/work-on`. Focused verification green at 3 tests / 40 assertions across `system_prompt_test` and `core_test/query-in-test`.
- ✓ `/work-on` isolated extension mutation path now executes git mutations correctly (2026-03-14, commit `700c137`): `run-extension-mutation-in!` now injects `:git/context` derived from the session effective cwd, and `register-mutations-in!` now includes history git mutations in the isolated qctx used by extensions. This fixes `/work-on` failing with blank or `missing git mutation payload` errors even after query-surface repair. Regression coverage now locks isolated-qctx history mutation availability in `components/agent-session/test/psi/agent_session/core_test.clj`; focused verification green at 6 tests / 21 assertions.
- ✓ Worktree workflow docs are synchronized (2026-03-14, commit `26f1245`): `doc/tui.md` now documents `/worktree`, `/work-on`, `/work-merge`, `/work-rebase`, and `/work-status`; `doc/extensions.md` documents the extension session lifecycle helpers (`:create-session`, `:switch-session`); and `doc/emacs-ui.md` notes `/work-*` command discovery in slash completion.
- ✓ Worktree session lifecycle follow-up converged (2026-03-14, commit `3bbb958`): `/work-on` now creates a distinct host peer session instead of rebinding the active session in place; `/work-merge` now switches back to an existing main-worktree session when present or creates one when absent. A first-class extension session lifecycle surface now exists (`psi.extension/create-session`, `psi.extension/switch-session`; `createSession`, `switchSession` in the extension API), and Allium surfaces are synchronized in `spec/session-core.allium`, `spec/extension-system.allium`, and `spec/work-on-extension.allium`. Focused verification green at 64 tests / 405 assertions across extension API, work-on extension, and agent-session core suites.
- ✓ Layered worktree usage implementation landed (2026-03-14, commit `ad691d4`): Layer 1 git mutations now exist in history (`git/worktree-add`, `git/worktree-remove`, `git/branch-merge`, `git/branch-delete`, `git/branch-rebase`, `git/default-branch`) and are exposed as EQL mutations (`:git.worktree/add!`, `:git.worktree/remove!`, `:git.branch/merge!`, `:git.branch/delete!`, `:git.branch/rebase!`, `:git.branch/default`). Layer 2 `/work-on`, `/work-merge`, `/work-rebase`, and `/work-status` now exist as an extension (`extensions/src/extensions/work_on.clj`). Runtime now carries session-scoped `:worktree-path`, and effective cwd for tools/git/persistence/runtime hooks prefers that session path over process cwd. Focused verification green at 118 tests / 817 assertions across history, introspection, agent-session core/resolvers, and work-on extension suites.
- ✓ Layered worktree usage meta + specs landed (2026-03-14, commit `0673e06`): three-layer architecture for worktree-based work — Layer 0 (existing read-only resolvers), Layer 1 (`spec/git-worktree-mutations.allium` — worktree add/remove, branch merge/delete/rebase as EQL mutations), Layer 2 (`spec/work-on-extension.allium` — `/work-on`, `/work-merge`, `/work-rebase`, `/work-status` orchestration). Key decisions: sibling directory paths, mechanical slug (4 terms), `--ff-only` merge default, session preserved after merge, nested work allowed. Three open questions in `git-worktrees.allium` closed.
- ✓ Worktree meta trimmed to essential shape (2026-03-14, commit `23327d7`): META.md worktree section reduced to four lines — what worktrees are and what `/work-on`/`/work-merge` do. Implementation decisions live in specs only.
- ✓ Executor turn state now records provider boundary telemetry (2026-03-14, commit `77f0bb7`): `executor.clj` records `:last-provider-event` and per-block `:content-blocks` for text/thinking/tool-call boundaries, and turn EQL now exposes `:psi.turn/last-provider-event` + `:psi.turn/content-blocks`. This adds executor-level diagnosis for stalled Anthropic turns without changing RPC/TUI surfaces. Focused verification green at 29 tests / 121 assertions across executor + turn-statechart suites.
- ✓ Startup host bootstrap now creates exactly one real session (2026-03-14, commit `87a5e77`): `create-context` starts with an empty host registry, `bootstrap-in!` no longer creates a session branch, and `bootstrap-runtime-session!` now creates one real session then runs startup prompts inside that same session. Startup host snapshots no longer include phantom seed/bootstrap/startup artifacts. Focused verification green at 86 tests / 727 assertions across main/core/resolvers/introspection suites.
- ✓ Thinking accumulation now resets at tool boundaries (2026-03-14, commit `42d1788`): executor clears the per-content-index thinking accumulator on `:toolcall-start`, so post-tool thinking is emitted as a fresh cumulative segment instead of replaying pre-tool text. Regression coverage now exists at executor, RPC, and Emacs layers for tool-interleaved thinking behavior.
- ✓ Anthropic thinking-delta cumulative-snapshot display bug fixed (2026-03-13, commit `9b24637`): executor now normalises `thinking_delta` events per-content-index via `merge-stream-text`; emitted `:text` is the full accumulated thinking text (replace semantics); TUI and Emacs updated to replace rather than append. 15 executor + 77 TUI + 206 Emacs tests green.
- ✓ `ThinkingDeltaIsCumulativeSnapshot` guidance rule added to `spec/anthropic-provider.allium` (commit `e91c490`): documents that `delta.thinking` in Anthropic SSE events is a cumulative snapshot, and that executor normalisation + replace-semantics in consumers is the correct handling contract.
- ✓ Emacs footer preserved across transcript reset on `/new` and `switch_session` (commit `ac969b8`): `psi-session-commands.el` now saves `projection-footer` before `reset-transcript-state` and restores + re-renders it after, preventing footer from disappearing when `footer/updated` events arrive before the response frame but `reset-transcript-state` would otherwise wipe them. 206/206 ERT passing.

## Operating Frame
- ✓ `/tree` session switch no longer leaves "connecting..." footer (2026-03-13, commit `d15b3de`): response callback now calls `psi-emacs--focus-input-area` instead of `psi-emacs--show-connecting-affordances`; `footer/updated` + `session/updated` events arrive before the response frame and already carry the correct footer, so overwriting with a placeholder was incorrect. Same fix applied to `/new` success path. 206/206 ERT passing.
- ✓ Prompt memory now includes explicit root-cause preference principle (2026-03-13, commit `859515c`): `λf. f (prefer (fix_root_cause) (over workaround))` in `AGENTS.md`.
- ✓ RPC handshake now emits `host/updated` bootstrap snapshot for rpc-edn sessions (2026-03-13, commit `a639f3e`) via `:handshake-host-updated-payload-fn` + handshake event emission path in `psi.agent-session.rpc`.
- ✓ Emacs `/tree` session selection is simplified to a single source of truth (`host-snapshot`) with no `list_sessions` fallback branch (commit `a639f3e`); startup host state is expected from handshake bootstrap + subscribe lifecycle.
- ✓ Handshake coverage now includes host snapshot event assertion in `components/agent-session/test/psi/agent_session/rpc_test.clj` (commit `a639f3e`), preserving protocol-level initialization guarantees for session-tree consumers.
- ✓ Emacs e2e harness assertions + startup timeout stabilization landed (2026-03-13, commit `ada2c32`): added `components/emacs-ui/test/psi-e2e-test.el`, `bb emacs:e2e` task, and synced docs (`components/emacs-ui/README.md`, `doc/emacs-ui.md`). Harness now asserts input focus, visible footer/projection, and non-input read-only guarantees across startup + `/history` flow.
- ✓ Projection/footer read-only behavior is now explicit in runtime (`components/emacs-ui/psi-projection.el`): projection interior is marked read-only while boundary insertion remains writable so transcript/tool rows can still append directly before the projection block.
- ✓ Emacs e2e transport readiness is stabilized for local startup variance by widening harness timeout to 60s; verification is green at `bb emacs:test` + `bb emacs:e2e` (`psi-emacs-e2e:ok`).
- ✓ TUI tmux integration harness baseline landed (2026-03-13, commit `1613f5f`): new spec `spec/tui-tmux-integration-harness.allium`, reusable harness helpers `components/tui/test/psi/tui/test_harness/tmux.clj`, and baseline `^:integration` test `components/tui/test/psi/tui/tmux_integration_harness_test.clj`.
- ✓ Harness contract now validates terminal-boundary viability with deterministic markers: ready (`刀:`/`Type a message`), help output (`(anything else is sent to the agent)`), and clean quit via pane process transition (`java` -> non-`java`).
- ✓ AGENTS prompt memory now encodes alpha/back-compat posture and multi-file Allium structural constraints (commit `8bd0d58`): `In alpha; no backward compatibility`, `spec_consists_of_multiple_connected_allium_files`, and `spec_has_no_isolated_allium_file` are explicit top-level invariants.
- ✓ Session spec consolidation completed (commit `14b9411`): new `spec/session-core.allium` and `spec/session-forking.allium` establish explicit core identity, lineage, and EQL contract surfaces for create/fork/subagent/message flows.
- ✓ Session-related specs now converged on Allium 2 where actively evolved (`session-core`, `session-forking`, `session-management`, `session-persistence`, `session-startup-prompts`).
- ✓ Naming normalization pass applied across `spec/*.allium`: legacy `sessionId`/`session_id` and `cwd` terms were replaced with `id` and `worktree_path`, then semantically repaired in high-risk specs to avoid blind-rename drift.
- ✓ Post-normalization integrity checks completed: duplicate-field scan clean; targeted repairs landed for `SessionMessage` identity fields, session usage scoping predicate, worktree-current naming, and remember-capture duplicate id field.
- ✓ Fork persistence semantics now match session-fork specs (commit `8e36668`): `fork-session-in!` eagerly writes child session files, forks journal/messages to `entry-id`, and persists lineage via child header `:parent-session` pointing to parent session file when available.
- ✓ Fork persistence regression coverage added: `fork-session-persists-child-file-with-parent-lineage-test` verifies child file creation + header lineage + branched entry count parity.
- ✓ Multi-session host non-UI foundation landed (2026-03-13, commit `b1fef75`): new in-process host registry (`session_host.clj`) tracks `:sessions` + `:active-session-id`; core context now carries `:session-host-atom` and auto-upserts host metadata on session state changes.
- ✓ Core session targeting primitives added: `ensure-session-loaded-in!` (load/switch by session id from host registry) and `set-active-session-in!` (routing pointer update) in `components/agent-session/src/psi/agent_session/core.clj`.
- ✓ RPC session routing surface expanded: targetable ops now accept optional `:session-id` and pre-load target session; `switch_session` now accepts `:session-id` (alongside legacy `:session-path`); `list_sessions` op returns host snapshot (`active-session-id` + sessions).
- ✓ Multi-session route-lock enforcement hardened (2026-03-13, commit `b3517dd`): `with-target-session!` accepts optional state + skip-lock args; `run-prompt-async!` acquires/releases `:session-route-lock`; conflict errors carry structured inflight/target session id payload; `session-resolver-surface` extracted for stable graph introspection independent of global registry.
- ✓ Multi-session UI spec landed (2026-03-13, commit `62f46cd`): `session-management.allium` defines `SessionHostSnapshot`/`SessionSlot` and `host/updated` event emission rules; `emacs-frontend.allium` defines session tree widget (`psi-session/session-tree`, left placement), display name fallback, parent-child indent, `/tree` completing-read picker, switch dispatch, connecting affordances, and transcript rehydrate contracts; `EmacsFrontendSessionTreeApi` surface defers full browser to later.
- ✓ Multi-session UI implemented (2026-03-13, commit `16ce8ed`): `host/updated` RPC event emitted on subscribe + `new_session` + `switch_session`; `psi-session/session-tree` left-panel widget rendered from event (hidden when ≤1 session, active marker, streaming badge, parent-child indent, clickable `/tree <id>` actions); `/tree` slash command opens completing-read picker or dispatches direct switch by id; `host-snapshot` stored on Emacs state; 204/204 ERT passing.
- ✓ Emacs session-tree widget action routing converged with idle slash semantics (2026-03-13, commit `cdfadda`): `psi-projection.el` now routes widget commands through `psi-emacs--dispatch-idle-compose-message`, so `/tree <id>` widget actions dispatch `switch_session` in Emacs instead of falling through backend `prompt` handling; regression locked by ERT `psi-projection-tree-widget-action-uses-idle-slash-routing`; `bb emacs:test` green at 205/205.
- ✓ `/tree` fallback routing hardened for long-lived Emacs sessions (2026-03-13, commit `728cbc6`): widget-command activation now prefers idle slash routing and only falls back to backend `prompt` when slash interception declines; idle compose dispatch now retries `/tree` with the default slash handler when custom handlers return nil, preserving deterministic `switch_session` behavior and preventing TUI-only fallback text leaks. Regression locked by ERT `psi-tree-dispatch-uses-default-handler-when-custom-slash-handler-returns-nil`; `bb emacs:test` green at 206/206.
- ✓ Multi-session UI fork gap closed (2026-03-13, commit `7ab1277`): `fork` RPC op emits `host/updated`; subagent creation correctly excluded (isolated context, not a host peer); subscribe/fork/new_session `host/updated` coverage added to rpc_test.
- ✓ TUI multi-session surface landed (2026-03-13, commit `92fc518`): `/tree` now opens a host-backed session picker in TUI (live `:psi.agent-session/host-sessions` + `:psi.agent-session/host-active-session-id`), supports direct `/tree <session-id|prefix>` switching, renders active/streaming/parent-child cues, and routes switching through `session/ensure-session-loaded-in!` rehydrate callback. `/tree` is runtime-gated to TUI (`supports-session-tree?`) with deterministic console/RPC fallback text.
- ✓ TUI `/tree` hierarchy + alignment polish landed (2026-03-13, commit `3c1c385`): host sessions are rendered in explicit parent/child tree order (stable sibling order), rows now carry tree glyphs (`●`, `├─`, `└─`, `│`), and right-side status cells are column-aligned for mixed `[active]`/`[stream]` states with stable session-id suffix alignment.
- ✓ Multi-session route-lock isolation now blocks exclusive lifecycle ops during in-flight prompt routing (2026-03-13, commit `115c6ab`): RPC route-lock guard now treats `new_session`, `switch_session`, and `fork` as exclusive ops under `:enforce-session-route-lock?`, returning canonical `request/session-routing-conflict` even on same-session targets while a prompt is in-flight.
- ✓ Route-lock hardening regression coverage landed in `components/agent-session/test/psi/agent_session/rpc_test.clj` (`exclusive ops are rejected while prompt is in-flight when lock enforcement is enabled`), and focused non-regression checks remained green for background-job gating + API-key routing paths.
- ✓ Changelog memory is synced for `/tree` rollout (2026-03-13, commit `d869843`): `CHANGELOG.md` now records `/tree` command modes, TUI-only gating, host-backed selector semantics, switch callback routing, and cross-suite verification totals for the shipped multi-session TUI slice.
- ✓ TUI integration harness baseline landed (2026-03-13, commit `1613f5f`): tmux-backed end-to-end verification now exists for terminal boundary behavior (`components/tui/test/psi/tui/test_harness/tmux.clj`, `components/tui/test/psi/tui/tmux_integration_harness_test.clj`) and is specified in `spec/tui-tmux-integration-harness.allium`.
- ✓ Harness baseline assertions are explicit: readiness marker (`刀:`/`Type a message`), `/help` marker, `/quit` Java-process exit, and best-effort tmux cleanup with pane snapshot on failure.
- ✓ Multi-session routing tests landed (2026-03-13): `components/agent-session/test/psi/agent_session/rpc_test.clj` now covers `list_sessions`, `switch_session(:session-id)`, targetable-op `:session-id` routing, and invalid `:session-id` rejection paths.
- ✓ Host-index EQL exposure hardened with process+persisted coverage (2026-03-13): `components/agent-session/test/psi/agent_session/resolvers_test.clj` now validates `:psi.agent-session/host-*` process view, `:psi.session/list` persisted view, and graph-introspection discoverability across `:psi.graph/root-queryable-attrs` and `:psi.graph/edges`.
- ✓ Multi-session regression suite is green after host-index hardening (2026-03-13): `clojure -M:test --focus psi.agent-session.rpc-test` (32 tests, 316 assertions) and `clojure -M:test --focus psi.agent-session.resolvers-test` (32 tests, 304 assertions) both pass with 0 failures.
- ✓ Graph contract nuance captured in tests: `:psi.agent-session/host-sessions` is join-discoverable via `:psi.graph/edges`; root-queryable scalar attr listings are not required to include join attrs.
- ✓ Commit `b1fef75` consolidated the session-work sweep: runtime host routing, session persistence/header/schema convergence, startup spawn-policy alignment, resolver/RPC/session tests, and related Allium/UI contract updates now land together as one verified delta.
- ✓ Session-focused verification after `b1fef75` is green across the targeted suite: `psi.agent-session.core-test`, `psi.agent-session.persistence-test`, `psi.agent-session.rpc-test`, `psi.agent-session.resolvers-test`, `psi.agent-session.runtime-startup-prompts-test`, and `psi.agent-session.startup-prompts-test` (aggregate: 116 tests, 1000 assertions, 0 failures).
- ✓ Session persistence header upgraded to v4 (`components/agent-session/src/psi/agent_session/persistence.clj`): headers now carry canonical `:parent-session-id` plus optional `:parent-session` path hint; v3→v4 read migration added.
- ✓ Cross-process session persistence locking is now enforced (commit `395d036`): all session-file writes in `persistence.clj` (`write-header!`, `flush-journal!`, append path) acquire an exclusive sidecar lock (`<session-file>.lock`) with bounded retry and explicit lock-failure errors.
- ✓ Session multi-session decisions elicited and encoded in spec (commit `395d036`): fork default prompt inheritance, soft fork isolation, merge-back as separate capability, optional fork budgets, and required cross-process locking are now explicit in `spec/session-core.allium`, `spec/session-forking.allium`, and `spec/session-persistence.allium`.
- ✓ Persistence locking regression coverage landed (commit `395d036`): `session-file-locking-test` asserts write failure under externally-held lock; focused verification is green for `psi.agent-session.persistence-test` and `psi.agent-session.core-test`.
- ✓ Session runtime schema extended for host/fork semantics (`components/agent-session/src/psi/agent_session/session.clj`): added `:parent-session-id`, `:parent-session-path`, and `:spawn-mode`.
- ✓ Startup prompt execution is now spawn-mode aware in runtime (`startup_prompts.clj` + `runtime.clj`): policy defaults run on `:new-root` and skip on spawned modes (`:fork-head`, `:fork-at-entry`, `:subagent`) unless explicitly enabled.
- ✓ Subagent optional fork capability implemented (2026-03-12, commit `e224736`): `subagent(action=create)` accepts `fork_session` (default false) to inherit invoking-session conversation context; `/sub` now supports `--fork|-f`; subagent widget/list annotate forked runs; extension spec updated in `spec/subagent-widget-extension.allium`.
- ✓ Subagent optional parent-context reinjection implemented (2026-03-12, commit `bd58191`): `subagent(action=create|continue)` accepts `include_result_in_context` (default false). When true, terminal output is inserted into parent LLM context as a `user` job-id marker + `assistant` result pair with alternation guard.
- ✓ Context-injection policy refined (2026-03-12, post-`bd58191` follow-up): when `include_result_in_context=true`, `subagent-result` custom transcript messages are skipped to avoid duplicating custom markers in model-facing conversation history; when false, existing custom transcript marker behavior remains.
- ✓ Subagent widget UI behavior now has a dedicated elicited spec (2026-03-13, commit `480e2f6`): `spec/subagent-widget-ui.allium` captures widget projection/rendering semantics, result-message rendering contract, and explicit decisions for persistence/order/TUI text-only behavior.
- ✓ Subagent UI decision state is now explicit in spec memory (from elicitation): terminal widgets persist until explicit remove, ordering is most-recent-first, TUI widget actions remain text-only, render preview/result limits are per-request configurable, result heading includes optional `@agent` + `[fork]`, and widget visibility is scoped to child sessions of the current session.
- ✓ User documentation surface is now consolidated under `doc/` with `README.md` as index (commit `c32f1d9`): detailed guides live in `doc/cli.md`, `doc/tui.md`, `doc/emacs-ui.md`, `doc/architecture.md`, `doc/extension-api.md`, `doc/extensions.md`, and `doc/psi-project-config.md`; `app-query-tool` details moved out of README built-in tools line.
- ✓ Quick Start and CLI command surface now converge on a user-defined `~/.clojure/deps.edn` `:psi` alias pointing at local clone path; docs now use `clojure -M:psi` examples instead of `-M:run` for operator-facing startup guidance (commit `0d8e71e`).
- ✓ Emacs user docs now include straight.el GitHub install and `psi-emacs-command` customization guidance; `psi.el` declares autoload stubs for `psi-emacs-start` and `psi-emacs-project`.

- ✓ Nucleus framing is now explicit in `AGENTS.md` via **Core Equation** (`刀 ⊣ ψ → 🐍`) and **The Loop** (Observe → Orient → Decide → Act).
- ✓ Spec refinement framing now explicitly includes values in `AGENTS.md` using allium-specific primitives (`λallium_spec_step(刀_intention, ψ_values, allium_spec)`), tightening the intention+values contract for future prompt/spec evolution.
- ✓ Project values and spec-drift guard workflow are now explicit in `README.md` (Values section + `bb spec:*` commands with declaration-name baseline guard).
- ✓ User-facing documentation surface is now split and consolidated under `doc/` (commit `9c95e4d` + follow-ups): `README.md` is concise entrypoint-only; detailed docs moved to `doc/cli.md`, `doc/tui.md`, `doc/emacs-ui.md`, `doc/architecture.md`, `doc/extension-api.md`, `doc/extensions.md`, and `doc/psi-project-config.md`; path usage is unified on `doc/`.
- ✓ Remember memory-capture framing clarified: human signal to future ψ via manual remember writeback.
- ✓ Memory model boundary clarified: session memory (ephemeral working set) ≠ persistent memory (cross-session distilled artifacts) ≠ git history (queried directly, not duplicated into memory store).
- ✓ Session persistence is a separate concern from memory: session transcripts/state may be partially persisted for `/resume`, but this is distinct from memory-store artifacts used by remember/recover.
- ✓ Working pattern remains atomic: inspect → minimal change → verify → commit.
- ✓ AGENTS guidance now formalizes spec/test convergence equations (commit `5504fcf`): `refactor_minimal_semantics_spec_tests` and `tests_musta_cover_allium_spec_behaviour` are explicit, and iterative refinement naming has shifted from generic `spec` to explicit `allium_spec` terms for consistency.
- ✓ Global code↔spec invariant added to AGENTS (commit `af2282f`): `λcode. ∃spec. describes(spec, code)` is now explicit prompt-memory policy.
- ✓ AGENTS now encodes localized spec refinement and code↔spec correspondence equations (commit `6e39269`): `λreq. λspec. localized_change(add_or_refine(rules(req) ∪ examples(req)), spec) ∧ ¬broad_restructure(spec)` and `λcode. ∃spec. (∧ (corresponds spec code) (implements code spec))`.
- ✓ 2026-03-06 session boot aligned via nucleus/OODA ritual; current mode is ◈ reflect → ready for · atom execution.
- ✓ Emacs prompt completion architecture implemented via CAPF: `/` command completion + `@` reference completion, category metadata (`psi_prompt`, `psi_reference`), affixation/annotation/exit hooks, cwd+project-root search, and configurable completion policies.
- ✓ Emacs slash CAPF now includes common backend/server command candidates in the built-in list (`/history`, `/prompts`, `/skills`, `/login`, `/logout`, `/remember`, `/skill:`), not only idle-local commands and extension-discovered names (commit `f23e38f`).
- ✓ User-facing Emacs docs are synchronized for slash/backend completion behavior at both component and top-level doc surfaces (`components/emacs-ui/README.md` + `doc/emacs-ui.md`, commit `50c9d59`).
- ✓ Emacs completion verification expanded: `components/emacs-ui` ERT suite green at 192/192 after slash-candidate expansion and regression test additions.
- ✓ Step 11a git-worktree visibility (read-only) implemented: `:git.worktree/*` attrs, session-root bridge attrs, `/worktree` command, and `/status` worktree surfacing.
- ✓ Worktree failure path now degrades safely with telemetry marker (`git.worktree.parse_failed`) and coverage.
- ✓ Test isolation hardened: agent-session/introspection tests now use temp cwd to avoid writing repo `.psi/project.edn`.
- ✓ PSL extension 400 fix: `agent-messages->ai-conversation` filters `:custom-type` messages; `extension-run-fn-atom` wired so PSL prompts invoke a real LLM call (not orphaned user messages). `register-extension-run-fn-in!` called after bootstrap in `main.clj`.
- ✓ Extension prompt delivery is now explicit for busy sessions (commit `fcf9db3`): `send-extension-prompt-in!` reports `:deferred` (not queue-only `:follow-up`) when a run-fn exists and the session is streaming; the runner waits for idle and executes automatically.
- ✓ PSL extension refactored to statechart workflow (commit `690cc7f`): `git_head_changed` handler now does skip-check only then creates a `:psl` workflow; job (future invoke) runs `send-message` + `send-prompt` from background, so PSL output appears after the triggering commit turn completes rather than before it.
- ✓ PSL execution now delegates implementation to a forked sync subagent (commit `2f2cf5a`): PSL job invokes `subagent` through `psi.extension.tool/chain` with `action=create`, `mode=sync`, and `fork_session=true`, making follow-up edits/commits run in isolated inherited context instead of direct extension prompt path.
- ✓ PSL follow-up prompt/result behavior refined (commit `0fcdf3b`): PSL prompt now explicitly instructs subagent responses to avoid compliance commentary, and PSL status message emission is failure-only (success-path status chatter removed).
- ✓ Extension bootstrap message isolation (commit `a3c9756`): `send-extension-message-in!` now skips `append-message-in!` and agent emits when `startup-bootstrap-completed?` is false — messages sent during extension `init` reach the event queue (UI notification) but never corrupt LLM history. PSL's `"PSL extension loaded."` startup call removed.
- ✓ Anthropic provider error body decoding (commit `4ffaa11`): 400/error responses from Anthropic are now decoded from the GZIP+JSON body in `ex-data :body`; the real API error message (e.g. "model not found") is surfaced in `:error-message` instead of the opaque `clj-http: status 400 {...}` ExceptionInfo string.
- ✓ `tool_use.input` always-dict hardening (commit `26fedd9`): `parse-args` in `executor.clj` now validates the JSON-parsed result is a map (falls back to `{}` for string/array/null); `transform-messages` in `anthropic.clj` adds a belt-and-suspenders guard at the wire-format layer. Fixes Anthropic 400 `messages.N.content.M.tool_use.input: Input should be a valid dictionary`.
- ✓ Thinking output bug fixed (c8e43eb): Anthropic provider now handles `thinking` content blocks correctly (`:thinking-delta` events, `thinking` request param, `interleaved-thinking-2025-05-14` beta header, temperature suppressed when thinking enabled). Emacs render fixed: `psi-emacs--assistant-thinking-delta` uses pure `concat` append instead of snapshot-merge heuristic that caused ever-growing repeated lines.
- ✓ Emacs thinking marker leak fixed (62cdd65): `psi-emacs--assistant-before-tool-event` drift branch now calls `set-marker nil` before clearing `thinking-range`, matching all other clearing paths. Regression test `psi-thinking-streaming-multiple-deltas-update-in-place` added to lock single-line in-place update for sequential thinking deltas. 187/187 tests passing. Runtime symptom ("sometimes repeated lines") may have additional async trigger; instrumentation recommended if it persists.
- ✓ OpenAI thinking streaming restored for chat-completions models (4c20882): provider now forwards `reasoning_effort` from thinking-level, extracts reasoning deltas across multiple chunk shapes, and emits reliable `:thinking-delta` events (plus normalized usage map for completion cost calculation).
- ✓ OpenAI thinking visibility now lands consistently across stream surfaces including TUI rendering (fbbb173), closing parity gaps between provider deltas and terminal presentation.
- ✓ OpenAI provider now captures redacted request/reply telemetry for introspection (commit `6e39269`): provider emits callback payloads for both completions and codex streams; `executor.clj` chains callbacks, tags captures with `turn-id`, and stores bounded histories (`provider-request-capture-limit=20`, `provider-reply-capture-limit=400`) in provider capture atoms/session data.
- ✓ OpenAI model catalog now includes `:gpt-5.4` (commit `bc67c9b`) with Codex Responses transport (`:openai-codex-responses`) and ChatGPT backend base URL, aligned to pi-mono baseline metadata (`context-window 272000`, `max-tokens 128000`, cost `2.5/15.0/0.25/0.0`).
- ✓ GPT-5 Codex family model registration test coverage now includes `:gpt-5.4` in `components/ai/test/psi/ai/core_test.clj`.
- ✓ Runtime env-var docs example now references `gpt-5.4` as a `PSI_MODEL` override example in `components/agent-session/src/psi/agent_session/main.clj`.
- ✓ OpenAI temperature behavior is explicit and tested (commit `6e39269`): chat-completions requests always include `temperature` (default `0`, override respected), while codex requests intentionally omit top-level `temperature` (backend rejects it).
- ✓ OpenAI provider Allium distillation now exists (`spec/openai-provider.allium`, commit `6e39269`) and captures dispatch, auth, SSE normalization, tool-call edge handling, and callback capture semantics.
- ✓ Anthropic provider Allium distillation now exists (`spec/anthropic-provider.allium`, commit `f8523cb`): covers tool ID normalisation (valid/sanitize/fallback chain, per-request cache atom), message transformation (user text fallback, assistant structured/text/unknown-block, tool-result consecutive merge), OAuth vs API-key auth dispatch, extended thinking (level→budget_tokens map, nil/off level = disabled, beta header combinations, temperature excluded when active, caller temperature override), URL construction (`base_url + /v1/messages`), SSE event normalisation (all 10 event types), usage accumulation across `message_start`/`message_delta`, and HTTP error body extraction. 6 gaps corrected vs code/tests during verification.
- ✓ OpenAI provider thinking pipeline fully distilled into spec (commit `905aac5`): `spec/openai-provider.allium` now captures completions reasoning fan-in (7 delta shapes), codex reasoning event mapping (4 SSE types), statechart bypass routing to progress queue, RPC wire translation (`assistant/thinking-delta`), TUI str-concat accumulation, Emacs pure-concat live-region upsert, tool-boundary archive/split, and turn-finalize clear-with-archive-preserved. Pre-existing parse errors (string-keyed map literals) also fixed.
- ? Divider-length regression report is in Emacs UI, not TUI: commit `3e02b97` made TUI separators width-aware, but user-observed uneven separators remain in `components/emacs-ui` projection/input paths.
- ✓ Commit `db9d4c7` now refreshes width-sensitive separators on window-configuration changes in Emacs buffers.
- ✓ Width source for projection/footer separators now prefers `window-text-width` (visible text area), with margin-based fallback.
- ✓ Window-change handler now calls `psi-emacs--ensure-input-area`, repairing stale/misaligned pre-edit separator markers before projection refresh.
- ✓ Emacs verification after separator follow-up: `bb emacs:test` green at 155/155.
- ✓ Project-scoped Emacs startup command added (`psi-emacs-project`): starts psi at detected project root, names buffers as `*psi:<project>*`, and supports prefix semantics (`C-u` fresh generated, `C-u N` slot selection).
- ✓ Emacs UI docs now include project command behavior and prefix semantics in both `README.md` and `components/emacs-ui/README.md`.
- ✓ Emacs verification after project-command addition: `bb emacs:test` green at 165/165; `bb emacs:check` green (with one pre-existing byte-compile warning unrelated to this change).
- ✓ Agent-chain `run_chain` progress heartbeat + widget projection landed (`18e0c50`): extension now tracks per-run phase/step/elapsed state, emits throttled tool updates while waiting for workflow completion, and refreshes widget state deterministically on init/reload/session-switch.
- ✓ Agent-chain default selection behavior tightened (`53c0f40`): extension no longer auto-selects first chain on init/reload/session-switch; widget remains `active: (none)` until explicit chain selection.
- ✓ `run_chain` replaced by `agent-chain` tool with explicit `action` parameter (commit `00bd634`): chain is resolved by name at call time — no global active-chain selection required. `action="run"` requires `chain` + `task`; `action="list"` replaces `/chain-list`; `action="reload"` replaces `/chain-reload` as a tool action. Slash commands `/chain` and `/chain-reload` remain as human-facing aliases. Widget no longer shows `active: <name>`.
- ✓ `agent-chain` now advertises available chains via prompt contribution (commit `27efd76`): contribution id `agent-chain-chains` registers under section `Extension Capabilities` (priority 200) with content listing chain names and descriptions. Synced on init, `action="reload"`, and `session_switch`.
- ✓ `agent-chain(action="run")` start acknowledgment is now compact and parse-friendly (commit `3600486`): returns `OK id:<run-id>` with no inline monitoring instruction; this reduces transcript noise and leaves monitoring policy to caller/UI.
- ✓ Agent-chain tool description now codifies async polling expectations (commit `c353189`): contract text says `action="run"` "start[s]" a chain and explicitly states "Do not poll unless explicitly asked to," aligning tool metadata with non-blocking workflow intent.
- ✓ Agent-chain runtime model resolution is now alias-tolerant and canonicalized (commit `2629a73`): model selection accepts keyword/string providers, supports `:id` or `:model-id`, falls back through session model attrs (including provider/id split attrs), and warns before defaulting to `:sonnet-4.6` when unresolved.
- ✓ Agent-chain run removal is now first-class (commit `8bc8eda`): `agent-chain(action="remove", id)` removes a workflow-backed chain run and prunes tracked widget state; new slash command `/chain-rm <run-id>` provides the same operation in command UX.
- ✓ Agent-chain parent-output contract now emits only final-stage output (commit `56d5a78`): intermediate chain stage updates are no longer sent via tool `on-update`, per-step console progress logging is removed, final chain delivery is a plain assistant message (no `custom-type` marker), and workflow create uses `:track-background-job? false` to avoid background-job terminal messages leaking chain plumbing into parent transcript.
- ✓ Tool naming is now canonicalized and enforced (commit `050e29d`): extension tool registration rejects non-kebab-case names (`^[a-z0-9][a-z0-9-]*$`), new spec `spec/tools/tool-naming.allium` defines the contract, and extension tools were renamed from underscore style to kebab-case (`agent-chain`, `hello-upper`, `hello-wrap`).
- ✓ Bash tool spec/code convergence completed (commit `ad44cb3`): `spec/tools/bash.allium` now reflects runtime semantics in `components/agent-session/src/psi/agent_session/tools.clj` (no `spawn_hook`/`operations`/chunk-stream partial contract; includes `overrides`, `on_update`, `tool_call_id`, timeout default, truncation-details-only-when-truncated, and boolean `abort-bash!` behavior).
- ✓ Read tool spec/code convergence completed (commit `a88335f`): `spec/tools/read.allium` now reflects runtime semantics in `components/agent-session/src/psi/agent_session/tools.clj` (image reads return text+image content blocks with `details=nil`; binary warning/missing-file messages use absolute paths; `overrides` policy is explicit; offset/limit/truncation guidance text matches implementation).
- ✓ Write/edit tool spec/code convergence completed (commit `f5ba5cd`): `spec/tools/write.allium` and `spec/tools/edit.allium` now reflect runtime semantics in `components/agent-session/src/psi/agent_session/tools.clj` (write: expanded+resolved path with resolved-path byte-count success message; edit: expanded+resolved path, exact-first replacement, fuzzy smart-punctuation/trailing-whitespace window matching, and runtime-aligned error strings/diff metadata).
- ✓ Edit spec follow-up convergence completed (commit `b642b54`): `spec/tools/edit.allium` now guarantees non-null diff metadata line numbers (`first_changed_line` is Integer with fallback `or 1`) and explicitly captures no-cwd absolute-path execution behavior, while retaining snake_case spec API fields (`old_text`, `new_text`) per repository convention.
- ✓ app-query-tool spec/code convergence completed (commit `364475a`): `spec/tools/app-query-tool.allium` now reflects runtime semantics in `components/agent-session/src/psi/agent_session/tools.clj` (`make-app-query-tool` copies base schema metadata, parses EDN with `*read-eval*` disabled, enforces vector query input, applies head truncation with optional `overrides`, writes full-output artifacts keyed by `tool_call_id` when truncated, returns `details=nil` when not truncated, and remains factory-only outside direct built-in dispatch).
- ✓ Runtime UI surface is now first-class and introspectable (commit `3cc8a76`): `:psi.agent-session/ui-type` resolves from session root (`:console|:tui|:emacs`), rpc handshake server-info includes `:ui-type` for Emacs transport, and extension API exposes `:ui-type` directly for branching without an EQL round-trip.
- ✓ Widget-heavy extensions now branch placement by UI type (commit `3cc8a76` + follow-up): `subagent_widget`, `agent-chain`, and `mcp_tasks_run` render `:below-editor` in Emacs and `:above-editor` otherwise; nullable extension test helpers gained `:ui-type` support so extension tests assert UI-specific behavior deterministically.
- ✓ `run_chain` execution mode now defaults to non-blocking background workflow start (commit `8d36927`), preventing Emacs UI request-path blocking; synchronous wait remains available via explicit `wait=true`.
- ✓ Interactive tool-call path now enforces non-blocking `run_chain` execution even when `wait=true` is requested (commit `11feddf`), avoiding request-path stalls in UI clients.
- ✓ Tri-artifact agreement invariant expanded to four artifacts (commit `15f7b3b`): `λ(model, spec, tests, code) → ∀ artifact ∈ {meta, spec, tests, code}, ∀ change δ: propagate(δ) → remaining two such that agree(meta, spec, tests, code) = true at all times`. META.md is now an explicit artifact in the agreement invariant alongside spec, tests, and code.
- ✓ `META.md` created (commit `15f7b3b`): captures the psi meta model — psi is a Clojure process, works on a project identified by git origin, has a UI as a view of the project meta model, and its engine exposes statecharts + EQL query/mutation + capability graph.
- ✓ Spec language axiom added to AGENTS.md (commit `15f7b3b`): `λspec. language(spec) = Allium` anchored at top of AGENTS.md, making spec-language identity an explicit first-class invariant.
- ✓ AGENTS.md spec lambda vocabulary tightened (commit `15f7b3b`): `allium_spec` renamed to `spec` throughout equations (`refactor_minimal_semantics_spec_tests`, `tests_musta_cover_spec_behaviour`, `λmatches`, `λdev_step`, `λspec_step`) for lexical economy; `allium_spec_step` → `spec_step`.
- ✓ Chain result delivery to chat (commit `81af559`): `emit-chain-result!` sends chain output back to the active session as an assistant message (`custom-type: "chain-result"`) on both success and error; ai-model resolution now falls back through session model → `:sonnet-4.6`; `done-script`/`error-script` call `on-finished` callback; step execution now catches `Throwable` (not just `Exception`).
- ✓ Extension-initiated agent runs now stream to RPC client (commit `1cebe2b`): `register-rpc-extension-run-fn!` replaces the no-progress-queue run-fn set by `main.clj` with one that creates a per-run `progress-queue`, polls it via a background future routing events through `emit-frame!`, and emits `assistant/message` + `session/updated` + `footer/updated` on completion. Called once from the `subscribe` handler (guarded by `:rpc-run-fn-registered`). Fixes PSL response invisibility in Emacs.
- ✓ Agent-chain definitions are now discoverable via top-level EQL attrs (`:psi.agent-chain/config-path`, `:psi.agent-chain/count`, `:psi.agent-chain/names`, `:psi.agent-chain/chains`, `:psi.agent-chain/error`) after runtime reload.
- ✓ Extension slash command completion now includes backend extension commands in both frontends:
  - Emacs CAPF merges built-ins with cached `:psi.extension/command-names`
  - TUI slash autocomplete refreshes `:psi.extension/command-names` during update loop
- ✓ Generic extension prompt-contribution layer landed (commit `e2fe3ed`): session now tracks `:base-system-prompt` + ordered `:prompt-contributions`, runtime recomposes `:system-prompt` deterministically, and extensions can register/update/unregister/list contribution fragments without domain-specific coupling.
- ✓ Prompt contribution surfaces are queryable/mutable via EQL (commit `e2fe3ed`):
  - mutations: `psi.extension/register-prompt-contribution`, `psi.extension/update-prompt-contribution`, `psi.extension/unregister-prompt-contribution`
  - attrs: `:psi.agent-session/base-system-prompt`, `:psi.agent-session/prompt-contributions`, `:psi.extension/prompt-contributions`, `:psi.extension/prompt-contribution-count`
- ✓ subagent-widget now advertises its tool capabilities through prompt contributions (commit `e2fe3ed`, refined copy in follow-up), enabling direct model-side discovery of subagent operations from system prompt context.
- ✓ Subagent profile selection shipped (commit `76a07e5`): `/sub` now supports optional `@agent` prefix, tool action `create` accepts `agent`, unknown agent profiles are rejected early, workflow/list output includes selected `@agent`, and prompt contributions now include discovered agent descriptions from `.psi/agents/*.md`.
- ✓ Subagent create now supports explicit dual-mode execution (commit `9f55a9f`): `mode=async|sync` on `action=create` (default async), `mode` rejected for non-create actions, async responses include workflow id + background `job-id`, and sync responses return terminal result + workflow id only.
- ✓ Subagent widget terminal rows now expose clickable remove actions in Emacs projection (commit `578a2a7`): action lines emit structured widget content with command action (`/subrm <id>`) and `✕ remove` label, matching agent-chain remove interaction pattern.
- ✓ PSL follow-up for `578a2a7` converged plan/state memory: subagent remove affordance is treated as command-backed widget action text (projection-friendly), not bespoke per-row interactive controls.
- ✓ Sync create semantics now include explicit bounded waiting (`timeout_ms`, default 300000ms) and deterministic failure text on timeout; workflow id remains persisted for introspection/continue even when sync path errors.
- ✓ Agent and chain definitions are now repository-tracked (commit `032d3f2`): `.psi/agents/*` (including `agent-chain.edn` and core agent profile markdown) is committed, eliminating local-only drift from `.git/info/exclude` and making chain/profile behavior reproducible across clones.
- ✓ Operator intent mismatch identified: users naturally try `/chain <name>` (for example `/chain prompt-build`) while current command handling is index-only; follow-up is in progress to support name-based selection without reintroducing implicit defaults.
- ✓ Submit-cycle separator disappearance in Emacs input area is now repaired in the send lifecycle (commit `c649a68`): `psi-emacs--consume-dispatched-input` re-runs `psi-emacs--ensure-input-area` after dispatch, making separator visibility self-healing on prompt submission.
- ✓ Startup UX now seeds a deterministic pre-handshake footer (`connecting...`) and keeps cursor focus in the dedicated input area immediately on display (commit `b652c1f`): `psi-emacs--focus-input-area` now normalizes both buffer point and visible window point.
- ✓ Projection/footer upsert path is now read-only-safe under the input-area guard (`inhibit-read-only` around projection delete/insert), preventing startup/event-time `text-read-only` drift.
- ✓ Startup hydration now preserves input focus even when replayed messages exist (`psi-initial-transcript-hydration-keeps-point-in-input-area-when-messages`).
- ✓ Focused regression coverage added: `psi-send-repairs-missing-input-separator-after-submit`, `psi-open-buffer-seeds-connecting-footer-before-handshake`, and `psi-start-focuses-window-point-in-input-area-before-handshake` (`components/emacs-ui/test/psi-test.el`).
- ✓ Startup transcript now begins with a deterministic psi banner (`ψ`) before all other content, including on `/new` reset flows (`psi-emacs--ensure-startup-banner` in compose/lifecycle/entry paths).
- ✓ Startup focus targeting is now window-aware: `psi-emacs--focus-input-area` prefers the explicit `pop-to-buffer` window and synchronizes both buffer point + window point (plus additional visible windows for same buffer).
- ✓ Backend handshake/startup compile regression resolved: `components/agent-session/src/psi/agent_session/core.clj` `all-mutations` now references `interrupt` mutation symbol (stale `abort` reference removed), restoring `--rpc-edn` handshake readiness.
- ✓ Verification: `bb emacs:test` passing at 172/172 after banner/focus/mutation-registration follow-up.
- ✓ `/new` clear/replay input/footer visibility regression fixed (commit `a533fa8`): `psi-emacs--handle-new-session-response` now reseeds `connecting...` projection and refocuses compose input immediately after reset and before `get_messages` replay, preventing transient missing input-area/footer after `/new`.
- ✓ `/resume` switch/replay input/footer visibility regression fixed (commit `dd99d2e`): `psi-emacs--handle-switch-session-response` now applies the same post-reset UX repair (`connecting...` reseed + compose refocus) before `get_messages` replay, preventing transient missing input-area/footer after `/resume`.
- ✓ Shared connecting-affordance policy now unifies startup + `/new` + `/resume` (commit `1421b46`): `psi-emacs--show-connecting-affordances` centralizes transient footer seeding (`connecting...`) and compose-input refocus during pre-handshake/rehydrate gaps.
- ✓ Focused regression coverage added for `/new` visibility path: `psi-idle-new-slash-restores-input-area-and-footer-after-reset` (`components/emacs-ui/test/psi-test.el`), and `/resume` success-path regression now asserts `connecting...` footer presence during rehydrate.
- ✓ Verification after `/new` + `/resume` visibility fixes and startup/rehydrate affordance unification: `bb emacs:test` green at 188/188.
- ✓ Allium spec parity now includes these startup/streaming behaviors (commit `b13c4f8`):
  - `spec/emacs-frontend.allium` models deterministic `ψ` transcript banner + input-area cursor placement on initialization and `/new` reset.
  - `spec/session-management.allium` models `prompt_while_streaming` steer/queue semantics and interrupt-pending steer coercion to follow-up.
- ✓ `allium check spec` remains green after spec update (49 files, 0 issues).
- ✓ `psi-emacs-command` is now marked safe for local variables (`:safe` predicate accepts list-of-strings), so project `.dir-locals.el` command overrides no longer trigger Emacs unsafe-local warnings.
- ✓ Emacs startup `*lsp-log*` read-only regression fixed (commit `0c6667f`): removed `psi-emacs-mode` buffer-local `inhibit-read-only`; localized transcript/property mutations behind explicit `let ((inhibit-read-only t))`; separator marker validity now requires line-start marker anchoring; Emacs tests updated for intentional read-only transcript clearing.
- ✓ Verification after read-only regression fix: `bb emacs:test` passing at 168/168 and `bb emacs:byte-compile` clean (pre-existing docstring width warnings only).
- ✓ `psi-emacs-project` buffer-reuse regression fixed (commit `7b63628`): `psi-emacs-open-buffer` was unconditionally calling `(text-mode)` on every invocation, resetting buffer-local state (including `psi-emacs--state`) and triggering a full re-init + transcript replay even for already-live buffers. Fixed by guarding the mode-setup block inside `unless (derived-mode-p 'psi-emacs-mode)`. Switching to an existing psi buffer now just focuses it. All 168 tests pass.
- ✓ `psi-emacs-project` "Text is read-only" follow-up fixed (commit `098cead`): `psi-emacs--refresh-input-separator-line` mutated the separator region via `delete-region`/`insert` without `inhibit-read-only`, triggering the `before-change-functions` guard on re-entry. Added `(inhibit-read-only t)` binding, consistent with all other internal transcript mutation sites. All 168 tests pass.
- ✓ psi buffer close now confirms before terminating a live process (commit `d90880d`): Emacs lifecycle installs `psi-emacs--confirm-kill-buffer-p` on `kill-buffer-query-functions`; interactive close prompts when owned process is live, decline cancels kill and preserves process, noninteractive paths (ERT/batch) auto-allow. Verification: `bb emacs:test` green at 174/174 with new close-confirmation regressions.
- ✓ Emacs keybinding lifecycle hardening landed (commit `505380f`): extracted `psi-emacs--install-mode-keybindings` and now reinstall canonical compose/navigation/model bindings on each `psi-emacs-mode` activation to self-heal stale keymap mutations in long-lived sessions. Added focused regression `psi-interrupt-keybinding-is-installed` asserting `C-c C-c` resolves to `psi-emacs-interrupt`.
- ✓ AI resolver list duplication eliminated (commit `f8727db`): `ai/all-resolvers` made public; `introspection/core.clj` `register-resolvers!` and `register-resolvers-in!` now use `(doseq [r ai/all-resolvers] ...)` instead of hand-listing all four AI resolvers. Adding a new AI resolver now requires only one change site.
- ✓ Anthropic provider usage tracking fixed (commit `c9af5f0`): `message_start` SSE event now captures `input_tokens`, `cache_read_input_tokens`, and `cache_creation_input_tokens`; `message_delta` captures `output_tokens`; `:done` event carries real usage map + calculated cost. Previously all usage was hardcoded to zero, causing the TUI footer to show `?/0` instead of actual token/context counts.
- ✓ Emacs footer now refreshes token/context usage after every tool call (commit `3786e39`): both progress poll loops in `rpc.clj` emit `footer/updated` immediately after each `:tool-result` event, so the stats-line (↑↓R W cost context%) updates live during multi-tool turns rather than only at the end of the agent loop.
- ✓ Footer usage totals are now session-scoped without dropping legacy journal entries (commit `bd45ada`): `session-usage-totals` includes assistant usage rows with matching `:session-id` and tolerates legacy `nil` session ids; footer totals no longer leak prior-session usage after `new_session`.
- ✓ Babashka test-task surface now includes explicit Clojure suites (commit `8a48c7a`): `bb clojure:test:unit` focuses Kaocha `:unit`, `bb clojure:test:extensions` focuses `:extensions`, and `bb clojure:test` composes both for a single entrypoint.
- ✓ Agent-chain background jobs now complete when the workflow finishes (commit `0064213`): `psi.extension/send-message` mutation (used by `emit-chain-result!`) now calls `maybe-mark-workflow-jobs-terminal!` + `maybe-emit-background-job-terminal-messages!` after injecting the message; previously those checks only ran at agent turn boundaries, leaving workflow-backed jobs stuck at `:running` until the next user prompt. Regression test `send-message-triggers-workflow-job-terminal-detection-test` exercises the full Pathom mutation path against a real workflow registry.
- ✓ Send-message/workflow completion race now has delayed terminal re-check (commit `95f70b1`): `psi.extension/send-message` now runs an immediate workflow-job terminal pass plus a short delayed pass (75ms) to catch async workflow `:done` transitions that land just after message injection. Added regression `send-message-terminal-detection-handles-workflow-completion-race-test` for async completion race.
- ✓ Background-job views now self-heal stale workflow statuses on read/list (commit `4a1eb5a`): reconciliation now runs before `/jobs` list + inspect paths and before `:psi.agent-session/background-jobs` resolver output, so workflow-backed jobs that finished but were still marked `:running` are normalized to terminal status on read. Added regression `background-job-resolver-self-heals-stale-workflow-status-test`; background-jobs suite now 34 tests / 107 assertions passing.
- ✓ PSL extension now emits explicit delivery-status transcript messages (commit `93a517e`): `send-prompt` outcomes are rendered as `queued via deferred; will auto-run when idle`, `queued via prompt`, or `queued via follow-up`, with focused extension tests and spec rules in `spec/plan-state-learning-extension.allium`.
- ✓ PSL delivery-status fallback formatting is runtime-safe (commit `51b9192`): fallback path now stringifies unknown delivery values with `str` (instead of `name`), preventing non-keyword delivery payloads from throwing during status message construction.
- ✓ Interrupt contract verification pass completed against live code surfaces (commit `f92dc0f`): current agent-session and Emacs frontend implementations align with `spec/agent-turn-interrupts.allium` + relevant `spec/emacs-frontend.allium` interrupt rules (deferred interrupt, interrupt_pending projection, dropped-steering draft restoration, and terminal-boundary interrupt apply/reset).
- ✓ Allium parse regression fixed for background job spec (commit `2c449bc`): `spec/background-tool-jobs.allium` terminal payload `content` expression now uses parser-valid conditional form; full `allium check spec` returns clean.
- ✓ Workflow mutation background-job tracking now supports both creation and continuation paths (commit `9f55a9f`): core tracking accepts `psi.extension.workflow/send-event` when `track-background-job?` is true, and mutations surface `:psi.extension.background-job/id` for immediate cross-surface job inspection/cancellation workflows.
- ✓ Background-job EQL introspection attrs are now first-class and graph-discoverable (commit `8185869`): session root resolves `:psi.agent-session/background-job-count`, `:psi.agent-session/background-job-statuses`, and `:psi.agent-session/background-jobs` with nested `:psi.background-job/*` entities; resolver tests assert root-queryable + graph-edge discoverability.
- ✓ nREPL runtime endpoint discovery is now first-class on the session graph (commit `e3280fb`): `:psi.runtime/nrepl-host`, `:psi.runtime/nrepl-port`, and `:psi.runtime/nrepl-endpoint` resolve from session root, are discoverable via `:psi.graph/root-queryable-attrs`/`:psi.graph/edges`, and return nil when nREPL is disabled.
- ✓ nREPL runtime lifecycle is now integration-tested against a real server start/stop (commit `1a1c044`): `nrepl-runtime-eql-reflects-live-start-stop-test` starts nREPL on a random bound port, verifies EQL attrs reflect the effective runtime endpoint, then stops nREPL and verifies attrs return nil.
- ✓ Emacs prompt transcript copy semantics are now dispatch-confirmed and parse-stable (commit `854e419`): `psi-compose.el` unmatched-paren parse failure fixed in interrupt callback path; `psi-emacs--default-send-request` now returns true only when RPC returns a non-empty request id; transcript echo path therefore copies user prompt only on confirmed dispatch.
- ✓ Emacs first-send separator contamination edge-case fixed (commit `6e39269`): input start resolution now prefers the first editable position after the separator line (`psi-emacs--input-separator-draft-start-position`) over stale draft anchor markers, preventing separator text from being prepended into the first prompt payload.
- ✓ Regression coverage added for failed-dispatch path (`psi-send-does-not-copy-input-when-dispatch-not-confirmed`) and stale-anchor separator path (`psi-send-omits-input-separator-from-first-prompt-when-anchor-drifts`); verification now runs via `bb emacs:test` with 186/186 passing after compose follow-up updates.

## Components

| Component       | Status | Notes                                                      |
|-----------------|--------|------------------------------------------------------------|
| `ai`            | ✓      | Provider streaming, model registry, extended thinking, tested |
| `engine`        | ✓      | Statechart infra, system state, nullable ctx               |
| `query`         | ✓      | Pathom3 EQL registry, `query-in`, nullable ctx             |
| `agent-core`    | ✓      | LLM agent lifecycle statechart + EQL resolvers             |
| `history`       | ✓      | Git log resolvers, nullable git context                    |
| `introspection` | ✓      | Bridges engine + query, self-describing graph              |
| `tui`           | ✓      | charm.clj Elm Architecture, JLine3, extension UI state     |
| `emacs-ui`      | ✓      | emacs mode for psi                                         |
| `agent-session` | ✓      | Session ✓, extensions ✓, extension UI ✓, main REPL ✓, TUI ✓, OAuth ✓ |

## Architecture Progress

- ✓ Engine (statecharts) substrate
- ✓ Query (EQL/Pathom3) surface
- ✓ AI provider layer
- ✓ Agent core loop
- ✓ Git history resolvers
- ✓ Introspection (engine queries itself)
- ✓ Coding-agent session orchestration (agent-session component)
- ✓ Built-in tools (read, bash, edit, write, app-query-tool)
- ✓ Executor (bridges ai streaming → agent-core loop protocol)
- ✓ Turn statechart (per-turn streaming state, EQL queryable)
- ✓ Runnable entry point (`clojure -M:run`)
- ✓ TUI session (`--tui` flag) — charm.clj Elm Architecture, JLine3
- ✓ Extension system (Clojure extensions, loader, API, tool wrapping, EQL introspection)
- ✓ Extension UI (dialogs, widgets, status, notifications, render registry, EQL introspection)
- ✓ OAuth module (PKCE, callback server, credential store, provider registry, Anthropic)
- ✓ Session introspection hardening (Step 7a): messages-count, tool-call-count, start-time, current-time
- ✓ Graph emergence (Step 7): all 9 :psi.graph/* attrs queryable via app-query-tool from agent-session-ctx
- ✓ Memory backing-store extension point (Step 9a phase 1): provider protocol + registry (`psi.memory.store`) with in-memory default and `:psi.memory.store/*` EQL attrs
- ✓ Memory runtime hardening (Step 9.5): CLI/env config surface, provider failure telemetry surfacing, explicit provider selection/fallback reporting, retention overrides, operator docs
- ✓ OAuth wired into runtime command flow (`/login`, `/logout`)
- ✓ Session resolvers wired into global query graph
- ✓ Graph emergence from domain resolvers (`ai`, `history`, `agent-session`, `introspection`)
- ✗ AI COMPLETE
- ✓ RPC EDN surface (`--rpc-edn`)
- ✗ HTTP API surface

## Runnable

```bash
ANTHROPIC_API_KEY=sk-... clojure -M:run
clojure -M:run --model claude-3-5-sonnet
clojure -M:run --model gpt-4o --tui
PSI_MEMORY_STORE=in-memory clojure -M:run
clojure -M:run --memory-store in-memory --memory-store-fallback off
clojure -M:run --memory-retention-snapshots 500 --memory-retention-deltas 2000
clojure -M:run --rpc-edn                 # EDN-lines RPC mode (headless/programmatic)
clojure -M:run --nrepl                   # random port, printed at startup
clojure -M:run --nrepl 7888              # specific port
clojure -M:run --tui --nrepl             # TUI + nREPL for live introspection
```

## RPC EDN (Step 8)

Status: ✓ complete

Implemented operations: `handshake`, `ping`, `query_eql`, `prompt`, `steer`, `follow_up`, `abort`, `new_session`, `switch_session`, `fork`.

In-session commands: `/status`, `/history`, `/new`, `/help`, `/quit`, `/exit`, `/skills`, `/prompts`, `/skill:<name>`, plus extension commands

Built-in tools: `read`, `bash`, `edit`, `write`, `app-query-tool`

The `app-query-tool` tool enables in-session EQL introspection without nREPL:
```
app-query-tool(query: "[:psi.agent-session/phase :psi.agent-session/session-id]")
app-query-tool(query: "[{:psi.agent-session/stats [:total-messages :context-tokens]}]")
app-query-tool(query: "[:psi.tool/names :psi.skill/names]")
```

Canonical agent-session telemetry query path (direct top-level attrs):
```
app-query-tool(query: "[:psi.agent-session/messages-count]")
app-query-tool(query: "[:psi.agent-session/tool-call-count]")
app-query-tool(query: "[:psi.agent-session/start-time]")
app-query-tool(query: "[:psi.agent-session/current-time]")
app-query-tool(query: "[:psi.agent-session/messages-count :psi.agent-session/tool-call-count :psi.agent-session/start-time :psi.agent-session/current-time]")
```

Live verification (2026-03-01): all 5 queries above return successfully with no resolver error; counts return integers and both time attrs return `java.time.Instant`.

## Canonical Graph Attrs (Step 7)

All 9 required Step 7 graph attrs are queryable via `app-query-tool` (seeded from `:psi/agent-session-ctx`):

```clojure
[:psi.graph/resolver-count]    ;; integer — resolvers in global registry
[:psi.graph/mutation-count]    ;; integer — mutations in global registry
[:psi.graph/resolver-syms]     ;; set of qualified symbols
[:psi.graph/mutation-syms]     ;; set of qualified symbols
[:psi.graph/env-built]         ;; boolean — Pathom env compiled
[:psi.graph/nodes]             ;; vector of CapabilityNode maps
[:psi.graph/edges]             ;; vector of CapabilityEdge maps (with :attribute)
[:psi.graph/capabilities]      ;; vector of DomainCapability maps
[:psi.graph/domain-coverage]   ;; vector of DomainCoverage maps (ai/history/agent-session/introspection)
```

Combined query (all 9):
```clojure
[:psi.graph/resolver-count :psi.graph/mutation-count
 :psi.graph/resolver-syms :psi.graph/mutation-syms :psi.graph/env-built
 :psi.graph/nodes :psi.graph/edges :psi.graph/capabilities :psi.graph/domain-coverage]
```

nREPL introspection (from connected REPL):
```clojure
@psi.agent-session.main/session-state   ;; → {:ctx ... :ai-model ...}
(require '[psi.agent-session.core :as s])
(s/query-in (:ctx @psi.agent-session.main/session-state)
  [:psi.agent-session/phase :psi.agent-session/session-id])

;; Runtime nREPL endpoint via canonical EQL attrs
(s/query-in (:ctx @psi.agent-session.main/session-state)
  [:psi.runtime/nrepl-host :psi.runtime/nrepl-port :psi.runtime/nrepl-endpoint])

;; Extension UI state
(s/query-in (:ctx @psi.agent-session.main/session-state)
  [:psi.ui/widgets :psi.ui/statuses :psi.ui/visible-notifications
   :psi.ui/dialog-queue-empty? :psi.ui/tool-renderers])

;; Live turn state (during streaming)
(require '[psi.agent-session.turn-statechart :as turn])
(when-let [a (:turn-ctx-atom (:ctx @psi.agent-session.main/session-state))]
  (turn/query-turn @a))
;; → {:psi.turn/state :text-accumulating :psi.turn/text "..." ...}
```

## TUI Session: Resolved

**Problem**: Custom `ProcessTerminal` with `stty -echo raw` + manual
ANSI differential rendering had cursor position desync on macOS.

**Fix**: Replaced entire TUI layer with charm.clj (Elm Architecture).
charm.clj uses JLine3 (`jline-terminal-ffm`) which handles raw mode,
input parsing, and cursor positioning correctly.

**Architecture**: `psi.tui.app` — init/update/view functions.
Agent runs in future → `LinkedBlockingQueue` → poll command → message.
Spinner driven by poll ticks (no separate timer thread).

**JLine compat note**: charm.clj v0.1.42 has a bug in
`bind-from-capability!` (expects `char[]` but JLine 3.30+ returns
`String`). Patched via `alter-var-root` at namespace load time.
Caught by `jline-terminal-keymap-test` smoke test.

## agent-session namespaces

| Namespace                       | Role                                              |
|---------------------------------|---------------------------------------------------|
| `core.clj`                      | Public API, create-context, global wrappers       |
| `statechart.clj`                | Session statechart (idle/streaming/compacting/retrying) |
| `session.clj`                   | AgentSession data model, malli schemas            |
| `skills.clj`                    | Skill discovery, validation, prompt formatting    |
| `system_prompt.clj`             | System prompt assembly (tools, context, skills)   |
| `compaction.clj`                | Compaction algorithm (stub, injectable fn)        |
| `extensions.clj`                | Extension registry, loader, API, tool wrapping    |
| `tui/extension_ui.clj`         | Extension UI: dialogs, widgets, status, notifications, renderers |
| `persistence.clj`               | Append-only journal                               |
| `resolvers.clj`                 | EQL resolvers (:psi.agent-session/*, :psi.skill/*, :psi.ui/*) |
| `tools.clj`                     | Built-in tool implementations                     |
| `turn_statechart.clj`           | Per-turn streaming statechart (idle→text⇄tool→done) |
| `executor.clj`                  | ai ↔ agent-core streaming bridge (statechart-driven) |
| `main.clj`                      | Interactive REPL loop + TUI session (-main)       |
| `oauth/pkce.clj`                | PKCE verifier + S256 challenge (JDK crypto)       |
| `oauth/callback_server.clj`     | Local HTTP callback server (Nullable: null server) |
| `oauth/store.clj`               | Credential storage, priority chain (Nullable: in-memory) |
| `oauth/providers.clj`           | Provider registry + Anthropic OAuth impl          |
| `oauth/core.clj`                | Top-level OAuth API (Nullable: stub providers)    |

## Test Status

148/148 ERT tests passing for `components/emacs-ui` after dedicated input area + input history updates (`bb emacs:test`).

## Specs

| Spec file                  | Component mapping       | Status                                |
|----------------------------|-------------------------|---------------------------------------|
| `bootstrap-system.allium`  | `engine` + `query`      | ✓ implemented (Allium v2 migrated)    |
| `agent.allium`             | `agent-core`            | ✓ implemented (Allium v2 migrated)    |
| `ai-abstract-model.allium` | `ai`                    | ✓ implemented                         |
| `coding-agent.allium`      | `agent-session`         | ✓ split → 3 sub-specs; ✓ implemented  |
| `tools.allium`             | `agent-session/tools`   | ◇ target contracts (runtime policy + read/bash/edit/write/ls/find/grep/app-query-tool + path resolution + introspection) |
| `tool-output-handling.allium` | `agent-session/tools` | ◇ compatibility index (superseded by tools/*.allium) |
| `skills.allium`            | `agent-session/skills`  | ✓ implemented                         |
| `tui.allium`               | `tui`                   | ◇ partial — core session loop working; streaming/tool-status UX still open (Allium v2 migrated) |
| `ui-extension-points.allium` | `tui/extension_ui`    | ✓ implemented                         |
| `oauth-auth.allium`        | `agent-session/oauth`   | ✓ implemented (Anthropic provider)    |
| `graph-emergence.allium`   | `query` + `introspection` | ◇ Step 7 spec authored (attribute links implicit; mutation side-effects deferred) |
| `memory-layer.allium`      | `query` + `history` + `introspection` | ◇ Step 10 spec authored (provenance, graph snapshots/deltas, recovery over session+history+graph) |
| `memory-backing-stores.allium` | `memory` | ✓ phase 1 implemented (provider contract + selection/fallback + `:psi.memory.store/*` EQL surface) |
| `remember-capture.allium` | `memory` + `introspection` + `engine` | ◇ Step 10 spec authored (manual remember capture + memory writeback) |

## Step 7 Decisions (Spec)

- Source: `spec/graph-emergence.allium`
- Attribute links stay implicit on `:psi.graph/edges` (edge metadata), not first-class graph nodes
- Mutation side-effects are deferred in Step 7 (`sideEffects = nil`) — capability graph is IO-link based for now

## Step 10 Decisions (Memory Spec)

- Source: `spec/memory-layer.allium`
- Recovery ranking defaults: text relevance 50%, recency 25%, capability proximity 25%
- Graph history retention: fixed-window compaction (keep latest 200 snapshots and 1000 deltas), trim oldest
- No graph-history summary entities in Step 10 (defer richer compaction/summarization)

## Step 9a Decisions (Spec)

- Sources: `spec/memory-backing-stores.allium`
- Default active memory store is `in-memory`
- Provider registry remains the extension point; one active provider at a time
- remember/recover/graph artifacts write through to the active provider
- Fallback policy defaults to automatic in-memory fallback when provider selection is unavailable
- Runtime memory config is available via CLI/env (store selection, fallback mode, history limit, retention limits)
- Provider operation telemetry is surfaced in store summaries/EQL (`write-count`, `read-count`, `failure-count`, `last-error`, `:psi.memory.store/last-failure`)
- Operator docs cover fallback triage and retention windows

## Step 10 Status

- ✓ Step 10 acceptance checklist is complete (manual remember capture semantics, telemetry, blocked/fallback paths, cross-surface parity, end-to-end visibility).

## Step 12b Status (Background Tool Jobs)

- ✓ Spec landed: `spec/background-tool-jobs.allium`
- ✓ Test matrix landed: `doc/background-tool-jobs-test-matrix.md`
- ✓ Runtime slice landed in commit `b7ac6f4`:
  - in-memory background job store + dual-mode invocation tracking
  - workflow-backed job tracking via extension workflow create mutation path
  - terminal detection (`done/error`) and turn-boundary synthetic assistant injection
  - completion-time ordered pending terminal queue with at-most-once emit marker
  - payload size policy + temp-file spillover for oversized terminal payloads
  - bounded terminal retention (20/thread) with oldest-terminal eviction
- ✓ Follow-up landed in commit `6f321af`:
  - completed remaining matrix IDs `N7`, `E7`, `E10`, `E13`, `B3`
  - exposed explicit background-job list/inspect/cancel surfaces in RPC
  - wired slash parity in TUI/Emacs (`/jobs`, `/job`, `/cancel-job`)
  - added/updated cross-surface tests (`agent-session`, `tui`, `emacs-ui`)
- ✓ Verification:
  - `clojure -M:test --focus psi.agent-session.background-jobs-test --focus psi.agent-session.commands-test --focus psi.agent-session.rpc-test --focus psi.tui.app-test`
  - `bb emacs:check`
- ✓ Cross-surface parity gap closed (REPL/TUI/Emacs/RPC)
- ✓ Query/introspection parity for background jobs is now explicit: EQL root attrs + nested entities are queryable and included in graph introspection surfaces (`:psi.graph/root-queryable-attrs`, `:psi.graph/edges`).

## Step 10 Decisions (Remember Spec)

- Source: `spec/remember-capture.allium`
- Remember scope is manual memory capture only (not automated evolution)
- `/remember` emits a manual signal and writes one memory artifact with current context
- Memory semantics are split explicitly:
  - session memory: short-term, ephemeral working context for current run
  - persistent memory: cross-session, distilled artifacts for future recovery
  - session persistence (`/resume`): partial session transcript/state saved to disk; operational continuity, not memory distillation
  - git history: external/queryable provenance, not mirrored into memory artifacts
- Output becomes input via remember/recover (future ψ reads captured artifacts)
- Store outage behavior is explicit: if memory write-through fails but fallback succeeds, `/remember` returns a visible warning (`⚠ Remembered with store fallback ...`) including provider/error detail when available
- No controller/process cycle model in spec scope

## Canonical Telemetry Attrs (Step 7a)

Top-level EQL attrs for session telemetry — all reliably queryable in-session:

```clojure
[:psi.agent-session/messages-count]    ;; integer — total messages in agent-core
[:psi.agent-session/tool-call-count]   ;; integer — total tool calls made
[:psi.agent-session/start-time]        ;; java.time.Instant — session context creation
[:psi.agent-session/current-time]      ;; java.time.Instant — wall clock now
```

Combined query (mirrors the failing pattern, now fixed):
```clojure
[:psi.agent-session/phase :psi.agent-session/model :psi.agent-session/session-id
 :psi.agent-session/messages-count :psi.agent-session/tool-call-count
 :psi.agent-session/start-time :psi.agent-session/current-time]
```

## Extension Prompt Telemetry Attrs (Step 12a)

Top-level EQL attrs for extension-initiated agent prompt visibility:

```clojure
[:psi.agent-session/extension-last-prompt-source]    ;; string? — extension source id (e.g. "plan-state-learning")
[:psi.agent-session/extension-last-prompt-delivery]  ;; keyword? — :prompt | :deferred | :follow-up
[:psi.agent-session/extension-last-prompt-at]        ;; java.time.Instant? — last extension prompt timestamp
```

Combined query:
```clojure
[:psi.agent-session/extension-last-prompt-source
 :psi.agent-session/extension-last-prompt-delivery
 :psi.agent-session/extension-last-prompt-at]
```

## Remember Telemetry Attrs (Step 10)

Top-level EQL attrs for remember-capture visibility:

```clojure
[:psi.memory.remember/status]           ;; keyword — :idle | :error
[:psi.memory.remember/captures]         ;; vector — remember-sourced memory records (newest first)
[:psi.memory.remember/last-capture-at]  ;; java.time.Instant? — timestamp of newest capture
[:psi.memory.remember/last-error]       ;; any? — last remember-related error marker (nil when none)
```

Combined query:
```clojure
[:psi.memory.remember/status
 :psi.memory.remember/captures
 :psi.memory.remember/last-capture-at
 :psi.memory.remember/last-error]
```

## Memory Store Telemetry Attrs (Step 9.5)

Top-level EQL attrs for store durability/failure introspection:

```clojure
[:psi.memory.store/active-provider-id]          ;; string
[:psi.memory.store/selection]                   ;; fallback + reason
[:psi.memory.store/health]                      ;; active provider health map
[:psi.memory.store/active-provider-telemetry]   ;; write/read/failure counters + last-error
[:psi.memory.store/last-failure]                ;; most recent failure map across providers
[:psi.memory.store/providers]                   ;; provider entries incl. :telemetry
```

Combined query:
```clojure
[:psi.memory.store/active-provider-id
 :psi.memory.store/selection
 :psi.memory.store/health
 :psi.memory.store/active-provider-telemetry
 :psi.memory.store/last-failure
 :psi.memory.store/providers]
```

## Session startup prompts (Step 11)

- Status: ✓ complete
- Spec: `spec/session-startup-prompts.allium`
- Config sources active: `~/.psi/agent/startup-prompts.edn` + `.psi/startup-prompts.edn`
- Repo startup prompt set currently includes one prompt (`engage-nucleus`)
- Implemented behavior:
  - deterministic merge/order with precedence `global < project`
  - startup prompts execute as visible transcript turns during new session bootstrap
  - startup telemetry persisted on session data (`:startup-prompts`, bootstrap started/completed timestamps, startup message ids)
  - startup attrs are top-level EQL attrs and discoverable via graph introspection (`:psi.graph/*`)
  - fork/new-session behavior is explicit and covered by tests (new-session runs bootstrap; fork resets startup telemetry)
- Validation:
  - `psi.agent-session.startup-prompts-test`
  - `psi.agent-session.runtime-startup-prompts-test`
  - `psi.agent-session.resolvers-startup-prompts-test`
  - latest run: 9 tests, 35 assertions, 0 failures

## Open Questions
- TUI: per-token streaming (currently shows spinner until agent done)
- TUI: tool execution status display during agent loop
- Extension UI: should dialogs support auto-dismiss timeout?
- Extension UI: widget ordering when multiple extensions contribute to same placement
- Extension UI: should render fns receive a theme map for consistent styling?
- Extension UI: editor text injection (set-editor-text, paste-to-editor)
- Extension UI: working message override during streaming ("Analyzing..." vs "thinking…")
- Graph emergence: when should mutation side-effects move from IO-only links to structured effect entities?

## nREPL

- Startup flags:
  - `--nrepl` (random bound port)
  - `--nrepl <port>` (explicit requested port)
- Canonical runtime discovery path is now EQL/graph attrs:
  - `:psi.runtime/nrepl-host`
  - `:psi.runtime/nrepl-port`
  - `:psi.runtime/nrepl-endpoint`
- `.nrepl-port` file is still written for editor compatibility, but runtime consumers should prefer EQL discovery.

- Δ psl source=53082d2cb72c2dbd354c790256f1e48b5663f717 at=2026-03-06T20:57:20.413334Z :: ⚒ Δ Simplify PSL to agent-prompt flow with extension prompt telemetry λ
