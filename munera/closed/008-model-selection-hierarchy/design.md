# Design: Model Selection Hierarchy

**Status:** Proposal
**Scope:** choosing models by role, constraints, and policy rather than only by explicit provider/id

## Goal

Provide one reusable, explainable way to choose a model for any psi task:
interactive sessions, agent work, helpers, workflows, and future autonomous
features.

## Overview

The design has four layers:

1. **Catalog** — the merged description of available models
2. **Request** — what a caller wants
3. **Resolver** — how a choice is computed
4. **Trace** — why that choice was made

Keeping these layers separate is what gives the design coherence and
explainability.

## 1. Catalog

The catalog is the resolver's merged view of all known models. It may be built
from user-, project-, or org-level sources, but the resolver consumes one
composed catalog view.

Each model is described by a declarative profile with two kinds of attributes.

### Factual attributes

Facts are stable attributes that the model either has or does not have.

Examples:
- provider family
- locality (`:local` vs `:cloud`)
- context window
- max output tokens
- declared capabilities such as tool use, vision, or structured output

These are cheap to check and suitable for hard filtering.

### Estimated attributes

Estimates are asserted or measured attributes that may drift over time.

Examples:
- cost tier
- latency tier
- quality-for-role scores

Estimated attributes should carry provenance, such as:
- source
- last-updated timestamp

Staleness does not automatically suppress an estimate in v1. A stale estimate
still participates in ranking, but its provenance and age should be visible in
the trace.

Role-quality estimates are optional. If a model has no quality estimate for a
role, it gets no ranking advantage from that criterion.

### Provider- and model-level metadata

Some attributes belong more naturally to the provider than the model.

Examples:
- locality
- provider family
- organization approval
- jurisdiction/privacy labels

The design does not require a particular storage layout, only that the resolver
sees a coherent merged view.

## 2. Request

A caller does not merely ask for a model. It asks for a model **for a role**,
with explicit constraints and preferences.

A request has three parts:
- **mode**
- **role**
- **constraint hierarchy**

### Request modes

Psi still needs three request modes.

#### Explicit

Use an exact concrete model.

Use for:
- direct user choice
- exact extension configuration
- tests that require a specific model

#### Inherit-session

Use the current session model.

Use for:
- tightly coupled continuations
- cases where behavioral continuity matters more than optimization

#### Resolve

Choose a model by role, constraints, and policy.

Use for:
- helpers
- compaction
- summarization
- classification
- review
- future autonomous tasks

Explicit and inherit-session use the same result and trace surface as resolved
selection, but operationally they skip ranking if their referenced candidate
survives viability and required-constraint checks.

### Role

A **role** is a named bundle of default intent.

Examples:
- `:summarize`
- `:classify`
- `:review`
- `:compact`
- `:agent`
- `:interactive`

A role supplies default constraints and preferences so callers do not need to
repeat common policy every time.

A role is:
- reusable
- adjustable by project or org policy
- independent of any single feature

A role is not:
- a feature name
- a capability name
- a fallback chain

A role may also appear as a dimension in estimated comparison attributes, such
as quality-for-role scores.

### Constraint hierarchy

The request expresses intent with labeled strengths.

#### Required constraints

These must hold.

Examples:
- locality must be local
- capabilities must include tool use
- context window must be at least 200k
- request configuration must be available

If no model satisfies the required constraints, resolution fails.

#### Strong preferences

These should hold if possible and dominate weaker preferences.

Examples:
- cost tier should be cheap
- latency tier should be low
- quality-for-review should be high

#### Weak preferences

These are nice-to-haves.

Examples:
- provider family should match the current session
- prefer recently used provider

### Preference criterion shape

A preference criterion should be understood as a comparison over a queryable
attribute.

Conceptually, a criterion contains:
- an attribute
- a comparison direction or comparator
- optionally a target value or context-derived value

Examples:
- attribute `:cost-tier`, prefer lower
- attribute `:latency-tier`, prefer lower
- attribute `[:quality-for-role :review]`, prefer higher
- attribute `:provider-family`, prefer equals(session.family)

This keeps preference evaluation uniform even when criteria come from different
sources.

### Queryable preferences only

Preferences must refer to attributes the resolver can actually compare.

Good examples:
- locality
- cost tier
- latency tier
- context window
- quality-for-role estimate
- provider-family affinity

Bad examples:
- vague notions with no catalog attribute or derived comparator

If the relevant metadata is missing for a preference, that preference contributes
no advantage for that candidate.

### Request context

Some preference inputs are derived from context rather than statically stored in
the catalog.

Examples:
- current session provider family
- current session model
- recently used provider/model

Context must be explicit, serializable, traceable, and stable enough to explain.
This preserves the pure-function property of resolution.

## 3. Resolver

The resolver is a library function shared by all callers. Helpers, extensions,
and the session itself all call the same resolver with the same merged catalog
view.

A resolution is a pure function of:
- catalog
- effective request
- request context

That makes caching safe and keeps behavior consistent across the system.

### Policy, request, and effective request

These terms should stay distinct.

- **role defaults**: built-in or configured defaults attached to a role
- **policy**: non-caller configuration layers such as system, user, and project
- **request**: caller-supplied intent for this resolution
- **effective request**: the merged result of role defaults, policy, and caller
  request

The trace should record the effective request actually used for resolution.

### Policy composition

Examples of policy layers:
- system defaults
- user policy
- project policy
- caller request

These must be merged explicitly.

#### Scope precedence

Scope precedence determines which layer wins when the same field is supplied in
multiple places.

Recommended precedence:
1. explicit model override
2. inherit-session model
3. caller request
4. project policy
5. user policy
6. system defaults
7. role defaults

Scope precedence is distinct from preference strength.

#### Merge rules

- required constraints **union / narrow**
- preferences are grouped by strength (`strong`, `weak`)
- each strength tier is an ordered list of preference criteria
- higher-precedence scopes may replace criteria with the same identity key
- new higher-precedence criteria are inserted before lower-precedence criteria
- duplicates are removed by identity key, keeping the highest-precedence
  instance

This yields one effective ordered list per preference tier.

The key property is that a caller does not need to know the ambient project
policy. It requests a role and gets a choice consistent with that policy.

### Stage 1: filtering

Filtering removes every candidate that fails a required constraint.

This includes:
- explicit model requests
- inherited model requests
- resolved requests over the whole catalog

If filtering leaves zero candidates, the resolver fails loudly.

There is no silent fallback that violates required constraints.

This is the core coherence guarantee:
- if a project is local-only, the resolver never returns a cloud model
- if no local model satisfies the other requirements, that is surfaced as a
  configuration or policy problem

A useful corollary: if behavior should degrade from local to cloud when needed,
then locality must be expressed as a preference, not a required constraint.

### Stage 2: ranking

Among surviving candidates, rank by preference strength:
1. strong preferences
2. weak preferences

Within a strength tier, use an explicitly declared aggregation rule.

Default recommendation:
- lexicographic ordering in declared preference order

Why lexicographic by default:
- explainable
- deterministic
- avoids invented weights

The architecture should leave room for alternative aggregators, such as weighted
sum or minimax, but core behavior should not depend on them.

### Ambiguity

Ranking may produce a partial order rather than a unique winner.

If two candidates tie on every effective preference, the resolver still picks
deterministically, but it records that the choice was ambiguous.

Deterministic tie-break should be explicit and canonical.
Recommended default:
- canonical provider/id order unless an explicit stable priority field exists

Ambiguity is useful signal. It means the policy is underspecified.

### Viability distinctions

The resolver should distinguish three things.

#### Reference viability

Can this model reference be used at all?

Examples:
- model exists in the catalog
- sufficient request configuration exists

#### Constraint satisfaction

Does the candidate satisfy required constraints?

Examples:
- required capabilities present
- locality requirement satisfied
- context threshold satisfied

#### Operational execution viability

Not part of model selection.

Examples:
- provider outage
- quota exhaustion
- transient latency spike

Those are runtime execution concerns, not resolver concerns.

## 4. Trace

Every resolution returns a trace alongside the chosen model.

The trace serves two audiences:
- the user asking "why did psi choose this model?"
- the policy author asking "why did these rules produce this result?"

### Trace contents

The trace should record:
- effective request after role + policy composition
- which required constraints eliminated which candidates
- which candidates survived filtering
- how preferences ranked the survivors
- where the chosen model won and lost
- whether the result was ambiguous
- whether the resolver failed because no candidate satisfied required
  constraints

### Presentations

Two presentations are useful:
- a short summary in normal operation
- a full trace on request or on failure

## Failure modes

The resolver has three outcomes:

1. **Unique winner**
   - normal case
2. **Ambiguous winner**
   - deterministic pick, ambiguity recorded in trace
3. **No winner**
   - hard failure with explanation

There is no fourth "best guess" mode.

## What this leaves out of v1

- learned routing or online updating of estimates
- full semiring machinery
- session-wide budget optimization
- elaborate feature-model relationships between catalog entries

The initial system can use:
- declarative facts
- hand-authored estimates
- explicit role defaults
- required / strong / weak constraint hierarchy
- lexicographic ranking with a pluggable aggregation hook

## Examples

### Local-first summarization policy

Intent:
- prefer a local model
- among local models, prefer high capability with acceptable latency
- if local is not required but unavailable or dominated, a cloud model may win
  by strong/weak preference ordering

This should be expressed as:
- locality as a preference unless policy requires local-only
- latency and capability as queryable preference dimensions
- no caller-local fallback chain

### Local-only project

Intent:
- never use cloud models

This should be expressed as:
- required constraint from project policy: `locality = :local`

If no local model satisfies the remaining requirements, resolution fails.

### Session affinity

Intent:
- prefer the current session's provider family when all else is equal

This should be expressed as:
- weak context-derived preference

### Explicit model failure

Intent:
- use an exact model selected by the user
- but still respect required constraints

Example:
- explicit model is cloud-hosted
- project policy requires `locality = :local`

Outcome:
- filtering rejects the explicit model
- resolver returns no winner with trace
- explicit choice does not bypass required constraints

### Minimal custom metadata

A model with only factual text capability and no estimated cost/latency data:
- may still satisfy required text constraints
- gets no advantage from preferences that depend on missing estimates
- remains explainable because the trace can say which comparisons were unknown

## Acceptance criteria

- One shared resolver is used by helpers, extensions, and sessions.
- A request distinguishes mode, role, required constraints, strong preferences,
  weak preferences, and context.
- Roles are reusable bundles of defaults.
- The catalog distinguishes facts from estimates.
- Filtering by required constraints is strict.
- Ranking is deterministic and explainable.
- Ambiguity is surfaced rather than hidden.
- If no candidate satisfies the required constraints, the resolver yields an explicit failure.
- The resolver returns a trace explaining the outcome.
- The same inputs produce the same result.

## Summary

A typed request goes to a shared resolver that:
- filters by required constraints
- ranks by layered soft preferences
- returns a choice plus a trace

against a declarative catalog that separates facts from estimates.

That is the core of the model-selection hierarchy.
