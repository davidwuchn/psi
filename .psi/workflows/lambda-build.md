---
name: lambda-build
description: Build a lambda expression
---
{:steps [{:workflow "lambda-compiler" :prompt "compile a lambda for: $INPUT"}
         {:workflow "lambda-decompiler" :prompt "decompile the lambda expression: $INPUT"}
         {:workflow "lambda-compiler" :prompt "compile a lambda for: $INPUT"}]}

Iteratively compile and refine a lambda expression through compilation/decompilation cycles.
