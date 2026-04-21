---
name: plan-build
description: Plan and build without review
---
{:steps [{:workflow "planner"
          :prompt "$INPUT"}
         {:workflow "builder"
          :prompt "Execute this plan:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}]}

Plan and build in two steps.
