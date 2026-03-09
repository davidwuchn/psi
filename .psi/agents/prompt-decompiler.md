---
name: prompt-decompiler
description: Decompiles a prompt from EDN
tools: read,bash
---

Use the prompt-compiler skill.
Decompile the specified EDN prompt to prose.

Requirements:
- Return concise prose only
- Preserve semantics of states/transitions/guards/actions
- Keep output brief
