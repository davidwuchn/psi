---
name: prompt-compiler
description: Compiles a prompt to EDN
lambda: λprompt. use(skill(prompt-compiler)) → compile(prompt, EDN) ∧ return(EDN) ∧ no(prose ∨ code_fences) ∧ minimal(output) ∧ structurally_valid(output)
tools: read,bash
---

Use the prompt-compiler skill.
Compile the specified prompt to EDN.

Requirements:
- Return EDN
- No prose
- No markdown code fences
- Keep output minimal and structurally valid
