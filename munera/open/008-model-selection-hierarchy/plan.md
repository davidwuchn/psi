Approach:
- Implement model selection around four explicit layers:
  1. catalog
  2. request
  3. resolver
  4. trace
- Keep one shared resolver in the AI/model layer so all callers use the same selection semantics.
- Treat selection as a pure function of merged catalog view, effective request, and request context.
- Build resolved selection around roles, required constraints, and strong/weak preferences rather than fallback chains or caller-local heuristics.
- Preserve explicit concrete model choice and inherit-session-model as request modes on the same resolver surface.
- Use one real caller to prove the design end-to-end, but keep the resolver independent of that caller.

Implementation slices:
1. Catalog audit and extension
   - inventory current built-in and custom model metadata in `psi.ai.model-registry`
   - separate already-available factual attributes from missing/implicit metadata
   - define any initial provider/model metadata additions needed for resolution
   - decide how user/project/system catalog layers merge into one resolver-visible catalog view

2. Request model
   - define request modes: `:explicit`, `:inherit-session`, `:resolve`
   - define role defaults as named bundles of constraints/preferences
   - define request shape for:
     - role
     - required constraints
     - strong preferences
     - weak preferences
     - context-derived inputs
   - define effective-request composition from role defaults + policy + caller request

3. Policy composition
   - define non-caller policy layers: system, user, project
   - implement explicit precedence and merge semantics
   - required constraints narrow/union
   - preference tiers become effective ordered lists after precedence-aware merge
   - choose identity semantics for preference criteria so replacement/dedup is deterministic

4. Resolver stage 1: filtering
   - implement reference-viability checks
   - implement required-constraint filtering over the catalog
   - support explicit-model and inherit-session-model requests through the same filtering surface
   - make failure explicit when no candidate satisfies required constraints

5. Resolver stage 2: ranking
   - implement deterministic ranking over surviving candidates
   - default aggregator: lexicographic ordering in declared preference order
   - rank strong preferences before weak preferences
   - implement canonical tie-break behavior
   - surface ambiguity when candidates tie on effective preferences

6. Trace surface
   - define core resolution result
   - define trace payload showing:
     - effective request
     - eliminated candidates and reasons
     - surviving candidates
     - ranking comparisons
     - ambiguity/failure state
   - provide short summary + full debug trace forms

7. Initial caller adoption
   - adopt the resolver in one helper/workflow caller as a proving slice
   - current best candidate remains `extensions.auto-session-name`
   - express its model choice in terms of role + preferences rather than hardcoded fallback rules
   - ensure caller-level behavior does not reintroduce selector forks or hidden heuristics

8. Tests and follow-on surfaces
   - unit tests for effective-request composition
   - unit tests for filtering and ranking behavior
   - tests for ambiguity and no-winner failures
   - focused integration tests for the first adopted caller
   - sync docs/config docs if the public policy surface changes

Implementation guidance:
- Prefer a small but correct catalog/request/resolver/trace loop over broad feature coverage.
- The resolver should be a library, not a separate service or extension-local helper.
- Facts and estimates should both be supported, but estimates remain optional and may be sparse.
- Stale estimates still participate in v1; provenance belongs in the trace, not in hidden resolver behavior.
- If behavior should degrade from local to cloud, express that through preferences, not fallback that violates required constraints.

Design decisions to settle during implementation:
- initial role set and where role defaults are declared
- which estimated attributes exist in v1 (cost tier, latency tier, role quality, etc.)
- how provider-level metadata is represented in the merged catalog view
- exact identity shape for preference criteria
- exact trace shape returned by the resolver versus higher-level projections

Risks:
- leaking caller-specific heuristics into the shared resolver
- overfitting early design to one helper caller
- inventing preference criteria that are not backed by queryable metadata
- accidental nondeterminism in preference merge or tie-break behavior
- letting runtime execution health/outage concerns drift into resolver semantics
