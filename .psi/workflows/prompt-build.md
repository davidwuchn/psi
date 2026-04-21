---
name: prompt-build
description: Build a new prompt
---
{:steps [{:workflow "prompt-compiler" :prompt "compile the prompt: $INPUT"}
         {:workflow "prompt-decompiler" :prompt "decompile the EDN prompt: $INPUT"}
         {:workflow "prompt-compiler" :prompt "compile the prompt: $INPUT"}]}

Iteratively compile and refine a prompt through compilation/decompilation cycles.
