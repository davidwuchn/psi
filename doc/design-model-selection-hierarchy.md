# Design: Model Selection Hierarchy

**Status:** Proposal
**Scope:** model capability description, preference-based selection, helper-task model choice, cross-feature reuse

## Problem

Psi currently supports explicit model selection and a registry of built-in and
custom models. That is sufficient when the user directly chooses a concrete
model, but it is less sufficient when the system or an extension needs to choose
an appropriate model for a task.

Examples:
- an automatic session-renaming helper should ideally use a cheap fast model
  that is good enough for terse summarization
- agent workflows may want a stronger model than lightweight helper tasks
- future background tasks may require image support, long context, or reasoning
- some projects may prefer local models first, while others may prefer hosted
  models first

Today, these choices tend to collapse to one of:
- use the session's current model
- hardcode a fallback model
- add task-specific ad hoc override settings

That does not scale well.

## Goals

- Let features and extensions ask for a model by **intent** or **requirements**,
  not only by exact provider/id.
- Represent both **capability constraints** and **preference ordering**.
- Keep explicit user model choice authoritative when a task should inherit the
  current session model.
- Allow projects and users to express preference for local, cheap, fast, or
  high-quality models depending on the task class.
- Reuse one selection mechanism across session helpers, agents, workflows, and
  future autonomous tasks.

## Non-goals

- Full benchmark-driven model evaluation in v1.
- Automatic online quality scoring from live traffic in v1.
- Dynamic price scraping from providers.
- Perfect global optimization across latency, quality, and cost.
- Replacing explicit concrete model selection for normal interactive sessions.

## Core idea

Introduce a **model selection hierarchy** with two layers:

1. **Capability filtering** — which models are eligible for a task?
2. **Preference ranking** — among eligible models, which one should be chosen?

This makes model choice declarative:
- first establish what a task needs
- then rank available candidates according to user/project/system preferences

## Why this is orthogonal but useful for the rename extension

The automatic session-renaming extension needs to say something like:
- use a model suitable for short text summarization
- prefer cheap/fast models
- do not require reasoning
- fall back safely if the preferred helper model is unavailable

That is broader than the extension itself. The same need appears for:
- agent child sessions
- workflow helpers
- compaction helpers
- title generation
- classification/routing
- future evaluation or review tasks

So the rename-extension need is a good motivating example, but the capability is
cross-cutting.

## Proposed concepts

### 1. Concrete model

A concrete model remains what exists today:
- provider
- id
- capability fields
- auth/transport metadata
- cost/context metadata where known

Examples of currently visible capability-like fields already include:
- reasoning support
- image support
- context window
- token limits

### 2. Capability profile

A capability profile describes what a task requires from a model.

Examples:
- requires text input support
- requires reasoning support
- requires image input support
- requires minimum context window
- requires auth-available model
- forbids reasoning if the task is intended to stay cheap/simple

A profile is about eligibility, not preference.

### 3. Task class

A task class is a reusable semantic label for a kind of work.

Examples:
- `:interactive-chat`
- `:agent`
- `:background-helper`
- `:rename-session`
- `:compaction`
- `:classification`
- `:review`
- `:vision`

A task class should resolve to a capability profile plus a preference profile.

### 4. Preference profile

A preference profile describes how to rank eligible models.

Examples:
- prefer local over hosted
- prefer cheaper over stronger
- prefer stronger over cheaper
- prefer lower latency
- prefer larger context
- prefer reasoning support when available
- prefer current session provider family

A preference profile is ranking logic, not a hard requirement.

### 5. Selector request

A selector request is what a feature asks for.

Example shape conceptually:
- task class: `:rename-session`
- inherit from session model?: yes/no
- hard constraints: capability requirements
- soft preferences: ranking hints
- fallback policy

The selector then returns the best concrete model available.

## Selection pipeline

The proposed selection pipeline is:

1. Start from the available authenticated model registry.
2. Apply hard capability constraints.
3. Apply scope-specific policy overlays:
   - system defaults
   - user preferences
   - project preferences
   - session overrides
   - feature-specific explicit override
4. Rank remaining models by preference profile.
5. Return the highest-ranked model.
6. If no eligible model exists, either:
   - return a structured resolution failure, or
   - use an explicitly allowed fallback policy.

## Precedence model

Concrete explicit choices should override abstract preferences.

Recommended precedence:

1. **Task explicit override**
   - e.g. extension config says exactly which model to use
2. **Session explicit concrete model**
   - when the task says it should inherit the interactive session model
3. **Task-class selection request**
   - capability + preference resolution
4. **Project preference policy**
5. **User preference policy**
6. **System defaults**

This preserves the current simple model-selection path while enabling more
structured automatic selection when needed.

## Two kinds of model choice

The system should distinguish clearly between:

### A. Inherited model choice

Use the session's current model unless impossible.

Good for:
- agent continuations tightly coupled to the current session
- tasks where consistency of behavior matters more than cost optimization

### B. Resolved model choice

Select the best model for a task class using the hierarchy.

Good for:
- background helpers
- summarizers
- classifiers
- rename/title generation
- future autonomous maintenance tasks

This distinction removes a lot of ambiguity.

## Proposed capability dimensions

V1 can start with a small useful set.

### Hard capability filters

- `:supports-text?`
- `:supports-images?`
- `:supports-reasoning?`
- `:auth-available?`
- `:min-context-window`
- `:provider-allowed`
- `:provider-forbidden`
- `:local-only?`
- `:hosted-only?`

### Optional future capability filters

- structured-output support
- tool-calling support
- streaming support
- cache support
- json-schema adherence support
- max-cost ceiling
- jurisdiction/privacy class

## Proposed preference dimensions

V1 ranking signals can also start small.

### Primary soft preferences

- `:prefer-local?`
- `:prefer-hosted?`
- `:prefer-cheap?`
- `:prefer-fast?`
- `:prefer-strong?`
- `:prefer-large-context?`
- `:prefer-same-provider-as-session?`
- `:prefer-reasoning?`

### Important note

Some of these signals require metadata that may be incomplete today. The design
should allow partial metadata and degrade gracefully.

For example:
- cost may be known only for some models
- latency may not be measured yet
- local vs hosted may need explicit provider classification

## Recommended task classes for v1

Start with a few stable classes.

- `:interactive-chat`
- `:agent`
- `:background-helper`
- `:rename-session`
- `:compaction`
- `:classification`
- `:vision`

### Example intent profiles

#### `:rename-session`

Hard constraints:
- supports text
- auth available
- no image requirement
- no reasoning requirement

Soft preferences:
- prefer cheap
- prefer fast
- prefer local
- prefer same provider as session only as a weak tie-breaker

#### `:agent`

Hard constraints:
- supports text
- auth available
- adequate context

Soft preferences:
- prefer strong
- prefer session/provider continuity
- prefer reasoning support when task settings call for it

#### `:compaction`

Hard constraints:
- supports text
- auth available
- adequate context

Soft preferences:
- prefer strong
- prefer large context
- cost moderate, not minimal

## Configuration direction

This design suggests adding preference configuration alongside the existing
concrete model configuration.

Current config already supports choosing a concrete session model. Keep that.

Add an orthogonal config layer for model selection policy.

### Conceptual config categories

- concrete defaults
  - current session model provider/id
- task-class overrides
  - e.g. rename-session helper model or policy
- preference hierarchy
  - project/user/system ranking policy
- provider classification
  - local vs hosted, trusted vs untrusted, etc.

## Proposed config concepts

### 1. Concrete task override

Example concept:
- for `:rename-session`, use exact provider/id if configured

This is the simplest override and should remain supported.

### 2. Task-class policy

Example concept:
- for `:background-helper`, prefer local cheap models
- for `:agent`, prefer strong reasoning-capable models

### 3. Provider classification

The selector needs to know attributes that are not purely model-specific.

Examples:
- provider class: `:local` or `:hosted`
- privacy/trust tier
- expected latency tier

Some of this may come from built-in knowledge; some may need config.

## Resolution semantics

The selector should return structured results, not only a model map.

Conceptually, resolution should answer:
- which model was selected
- why it was selected
- which policy/profile was used
- whether a fallback was required
- why preferred models were rejected

This helps debugging and future introspection.

## Failure modes

The design must define behavior when no model matches.

Possible policies:
- fail closed with structured error
- fall back to current session model
- fall back to project default concrete model
- fall back to any auth-available text model

The fallback policy should be part of the selector request, not implicit magic.

## Interaction with current session model

This design should not weaken the meaning of the current session model.

The current session model still answers:
- what model is used for this interactive session unless explicitly overridden

The new hierarchy answers:
- what model should be used for auxiliary or task-specific work when a concrete
  session model is not the whole story

## Interaction with custom models

The current custom-model registry is a strong foundation for this design.

However, richer selection requires richer metadata and policy attachment.

Likely additions over time:
- provider classification
- optional latency tier
- optional quality tier
- optional task suitability tags
- optional privacy tier

These should remain optional; selection should work with partial metadata.

## Architectural principles

### Separate capability from preference

This is the core design rule.

- capability answers: can this model do the task?
- preference answers: which acceptable model should we prefer?

Do not encode preference as fake capability.

### Explicit fallback policy

Do not hide fallback behavior. A feature should say whether it is allowed to:
- fail if no preferred helper model exists
- inherit the session model
- use any acceptable model

### Reuse one selector everywhere

Avoid task-specific ad hoc model-picking logic in each extension. The selection
mechanism should be shared.

### Introspectability

Model selection should be explainable. It should be possible to inspect why a
model was chosen or why no match existed.

## Open design questions

1. Which preference dimensions are valuable enough for v1?
2. Should quality/latency tiers be explicit metadata, measured telemetry, or
   both?
3. How should local vs hosted be represented for custom providers?
4. Should task classes be fixed keywords, extension-defined keywords, or both?
5. Should project config be allowed to forbid certain providers for policy
   reasons?
6. How much of the selection explanation should be exposed in UI vs query only?

## Acceptance criteria

- A feature can request a model by task class instead of concrete provider/id.
- The selector filters models by hard capabilities.
- The selector ranks eligible models by preference policy.
- Explicit concrete overrides remain authoritative.
- Failure and fallback behavior are explicit.
- The selection result is explainable via a structured reason surface.
- The mechanism is reusable by extensions, agents, and future helper tasks.

## Initial recommendation

Land this in two stages.

### Stage 1: minimal useful hierarchy

- keep existing concrete model config unchanged
- add task-class selection requests
- support a small set of capability filters
- support a small set of preference hints
- support structured explainable selection result
- use it first for helper/background tasks such as session renaming

### Stage 2: richer policy and metadata

- provider classification
- latency/cost/quality tiers
- more task classes
- stronger introspection and config surfaces
- broader adoption in agents and autonomous workflows

## Why this matters

Without this hierarchy, features will increasingly hardcode one-off model rules:
- rename helper uses X
- agent uses Y
- compaction uses Z
- classifier uses local if present else arbitrary fallback

That leads to drift and poor explainability.

With a shared model selection hierarchy, psi can keep one coherent answer to:

> given this task and these constraints, which model should we use, and why?
