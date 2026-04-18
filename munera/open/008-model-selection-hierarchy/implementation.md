2026-04-17
- Moved the original standalone design note into this munera task, then consolidated it back into `design.md`.
- Reworked the design substantially away from task-class/fallback-chain framing and toward four explicit layers:
  - catalog
  - request
  - resolver
  - trace
- Replaced `task class` with `role` as the main request-default abstraction.
- Tightened the design around:
  - factual vs estimated catalog attributes
  - request modes (`:explicit`, `:inherit-session`, `:resolve`)
  - required / strong / weak constraint hierarchy
  - explicit policy vs request vs effective-request distinction
  - deterministic ranking with ambiguity surfaced in the trace
  - strict no-silent-fallback behavior when required constraints are unsatisfied
- Current implementation-relevant orientation remains:
  - `extensions/src/extensions/auto_session_name.clj` currently creates a helper child session with no explicit model selection beyond inherited runtime/session defaults
  - `components/ai/src/psi/ai/model_registry.clj` is still the natural starting point for catalog construction
  - current runtime paths still resolve explicit concrete models directly in app-runtime / rpc / commands; no shared resolver exists yet

2026-04-17 — Step 1: catalog inventory
- Audited current runtime model metadata across:
  - `components/ai/src/psi/ai/models.clj`
  - `components/ai/src/psi/ai/user_models.clj`
  - `components/ai/src/psi/ai/model_registry.clj`
- Current merged catalog identity is already stable on `[provider id]`.
- Current built-in + custom catalog metadata naturally splits into:
  - factual / hard-filter friendly:
    - `:provider`
    - `:id`
    - `:name`
    - `:api`
    - `:base-url`
    - `:supports-text`
    - `:supports-images`
    - `:supports-reasoning`
    - `:context-window`
    - `:max-tokens`
  - estimated / ranking-friendly:
    - `:input-cost`
    - `:output-cost`
    - `:cache-read-cost`
    - `:cache-write-cost`
  - reference-viability adjacent data available outside the model entry:
    - provider auth config via `model-registry/get-auth`
- Gaps relative to the task design are now explicit:
  - no first-class locality metadata (`:local` vs `:cloud`)
  - no first-class tool-use or structured-output capability metadata
  - no role-quality estimates
  - no estimate provenance / freshness fields
  - no provider policy metadata (approval, jurisdiction, privacy labels)
- Implementation consequence for v1:
  - initial resolver work should use only queryable metadata that already exists
  - locality and richer policy dimensions should not be invented implicitly from provider names or URLs
  - auth/config availability can participate in reference viability, but not as hidden ranking behavior

2026-04-17 — Step 2: merged catalog view
- Added initial shared namespace: `components/ai/src/psi/ai/model_selection.clj`.
- Introduced `catalog-view` as the first resolver-facing catalog surface over `psi.ai.model-registry`.
- v1 catalog-view shape now normalizes each candidate into:
  - identity:
    - `:provider`
    - `:id`
    - `:name`
    - `:api`
    - `:base-url`
  - `:facts`
    - text / image / reasoning support
    - context window
    - max tokens
  - `:estimates`
    - raw cost fields
  - `:reference`
    - `:configured?` derived from provider auth/config presence
- Added `find-candidate` helper for provider/id lookup against the normalized catalog.
- Added focused AI tests proving:
  - built-in candidates project into the normalized catalog view
  - custom models also project into the normalized catalog view
  - provider auth/config participates in explicit reference metadata
  - catalog ordering is deterministic by `provider/id`
- Important v1 choice:
  - the catalog view remains a projection layer only
  - it does not yet implement request composition, filtering, ranking, or trace logic

2026-04-17 — Step 3: request modes and initial roles
- Extended `psi.ai.model-selection` with initial request/role surfaces.
- Added initial role defaults for:
  - `:interactive`
  - `:helper`
  - `:auto-session-name`
- Added `role-defaults` lookup so caller intent can be expressed in terms of reusable role bundles instead of inline heuristics.
- Added `normalize-request` with initial request-shape responsibilities:
  - default `:mode` to `:resolve`
  - default `:role` to `:interactive`
  - normalize missing required/strong/weak/context fields
  - normalize explicit model provider strings to keywords
  - merge role defaults beneath caller-provided fields
- v1 merge semantics are intentionally simple at this stage:
  - role defaults seed the request
  - caller-provided `:required`, `:strong-preferences`, `:weak-preferences`, and `:context` replace seeded values fieldwise
  - richer multi-layer policy composition remains a later step
- Added tests proving:
  - known role bundles are queryable
  - unknown roles return nil
  - request normalization supplies canonical defaults
  - role defaults are injected into normalized requests
  - explicit-model requests normalize provider identity canonically

2026-04-17 — Step 4: policy layering and effective-request composition
- Extended `psi.ai.model-selection` with explicit effective-request composition.
- Added fixed scope precedence for v1 composition:
  - `:role-defaults`
  - `:system`
  - `:user`
  - `:project`
  - `:request`
- Added `criterion-identity` as the initial stable identity function for preference replacement/dedup.
  - v1 identity uses `:identity`, else `:criterion`, else `:attribute`
- Added `compose-effective-request` over four non-caller policy layers plus the caller request.
- v1 merge behavior now matches the task design at a pragmatic level:
  - required constraints union in precedence order
  - strong/weak preference tiers dedupe by criterion identity
  - higher-precedence duplicate criteria replace lower-precedence criteria in place
  - context maps merge shallowly with later scopes winning per key
  - explicit model selection is taken from the highest-precedence scope that supplies it
- This is intentionally still pre-resolver:
  - no candidate filtering yet
  - no ranking yet
  - no ambiguity/no-winner result logic yet
- Added tests proving:
  - role defaults seed the effective request
  - required constraints union across layers
  - preference replacement by identity is deterministic
  - context merges by precedence
  - explicit model precedence is deterministic

2026-04-17 — Step 5: resolver stage-1 filtering
- Extended `psi.ai.model-selection` with stage-1 filtering over the normalized catalog and effective request.
- Added mode-aware candidate pool selection:
  - `:resolve` → whole catalog
  - `:explicit` → exact provider/id candidate only
  - `:inherit-session` → candidate implied by explicit session-model context
- Added initial required-constraint evaluation over the normalized catalog surface.
- v1 required-constraint operators now support:
  - `:match`
  - `:at-least`
  - `:at-most`
  - `:equals`
  - bare truthiness fallback
- Candidate attributes are read from the normalized candidate across:
  - `:facts`
  - `:estimates`
  - `:reference`
  - top-level identity fields as final fallback
- Added `filter-candidates` returning:
  - `:pool`
  - `:survivors`
  - `:eliminated` with explicit failed-constraint reasons
- Important v1 note:
  - this is still filtering only
  - no winner selection or failure/ambiguity result type exists yet
- Added tests proving:
  - resolve-mode filtering across the full catalog
  - explicit-mode pool restriction
  - inherit-session pool restriction
  - missing explicit references yield an empty pool
  - eliminated candidates retain explicit failed-constraint reasons

2026-04-17 — Step 6: resolver stage-2 ranking
- Extended `psi.ai.model-selection` with lexicographic survivor ranking.
- Added stage-2 ranking semantics aligned with the task design:
  1. strong preferences in declared order
  2. weak preferences in declared order
  3. canonical provider/id tie-break
- v1 supported preference forms now include:
  - `{:prefer :lower}`
  - `{:prefer :higher}`
  - `{:prefer :context-match}` for context-derived comparisons such as session provider/model affinity
- Added canonical tie-break key based on provider/id ordering.
- Added `rank-candidates` returning:
  - `:ranked`
  - `:ambiguous?`
- Ambiguity in v1 is surfaced when the top two ranked candidates tie across all effective strong+weak preferences and are separated only by the canonical tie-break.
- Added tests proving:
  - strong preferences dominate weak ones
  - weak context-derived affinity can break otherwise equal strong preference results
  - canonical tie-break stays deterministic and marks ambiguity when preferences fully tie

2026-04-17 — Step 7: ambiguity and no-winner outcomes
- Extended `psi.ai.model-selection` with `resolve-selection` as the first full selection entrypoint.
- `resolve-selection` now runs:
  1. effective-request composition
  2. stage-1 filtering
  3. stage-2 ranking
- Added explicit resolver outcomes for v1:
  - `{:outcome :ok ...}`
  - `{:outcome :no-winner :reason :reference-not-found ...}`
  - `{:outcome :no-winner :reason :required-constraints-unsatisfied ...}`
- Explicit/inherit-session requests with an empty candidate pool now fail as `:reference-not-found`.
- Requests whose pool exists but whose survivors are empty now fail as `:required-constraints-unsatisfied`.
- Successful outcomes now surface `:ambiguous?` directly when the winner was chosen only after a canonical tie-break.
- Added tests proving:
  - `:ok` outcomes return a selected candidate and ambiguity flag
  - missing explicit references return `:no-winner / :reference-not-found`
  - required-filter failures return `:no-winner / :required-constraints-unsatisfied`
  - ambiguity remains surfaced on successful outcomes

2026-04-17 — Step 8: core result and trace payloads
- Extended `resolve-selection` so the shared resolver now returns a first-class trace payload.
- Added `short-trace` for terse operational explanation:
  - outcome
  - selected candidate summary when present
  - ambiguity flag
  - pool/survivor counts
- Added `full-trace` for debug/explainability use:
  - effective request
  - filtering details
  - ranking details
  - outcome / reason
  - selected candidate
  - ambiguity flag
- `resolve-selection` now includes:
  - `:trace {:short ... :full ...}`
- This completes the initial catalog → request → effective-request → filtering → ranking → outcome → trace loop for the shared resolver library.
- Added tests proving:
  - successful resolutions expose both short and full traces
  - short traces project the selected candidate summary cleanly
  - failure traces retain reason and filtering evidence

2026-04-17 — Step 9: initial caller adoption in auto-session-name
- Adopted the shared resolver in `extensions.auto-session-name` as the first real caller.
- Added source-session model-context query in the extension:
  - `:psi.agent-session/model-provider`
  - `:psi.agent-session/model-id`
- Added helper-model selection via `psi.ai.model-selection/resolve-selection` with:
  - role `:auto-session-name`
  - mode `:resolve`
  - context seeded from the source session model
- The helper child session still runs with explicit `:thinking-level :off`, but the helper turn now passes an explicitly resolved `:model` when selection succeeds.
- Current role defaults make auto-session-name choose a cheap text-capable helper model, with session-model affinity only as a weak preference.
- In the current catalog this resolves to `:openai/gpt-5.3-codex-spark` for the tested cases, which is consistent with the current cost-first role defaults.
- Added/updated tests proving:
  - extension unit path passes the resolved helper model into `run-agent-loop-in-session`
  - runtime path also passes the resolved helper model into the helper run mutation
  - existing rename/manual-override/stale-checkpoint behavior remains intact
