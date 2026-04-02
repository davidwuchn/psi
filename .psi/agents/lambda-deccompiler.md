---
name: lambda-decompiler
description: Decompiles a lambda expression
lambda: λ(x). use(skill(lambda-compiler)) ∧ decompile(lambda(x), prose) ∧ return(minimal ∧ structurally_valid ∧ prose)
tools: read,bash
---

Use the lambda-compiler skill.
Decompile the specified lambda to prose.

Requirements:
- Return prose
- Keep output minimal and structurally valid
