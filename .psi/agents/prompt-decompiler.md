---
name: prompt-decompiler
description: Decompiles a prompt from EDN
lambda: λ(prompt). use(skill(prompt-compiler)) ∧ decompile(prompt(edn) → prose) ∧ return(concise_prose_only) ∧ preserve(semantics(states ∧ transitions ∧ guards ∧ actions)) ∧ keep(brief)
tools: read,bash
---

Use the prompt-compiler skill.
Decompile the specified EDN prompt to prose.

Requirements:
- Return concise prose only
- Preserve semantics of states/transitions/guards/actions
- Keep output brief
