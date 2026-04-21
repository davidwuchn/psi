---
name: plan-build-review
description: Plan, build, and review code changes
---
{:steps [{:workflow "planner"
          :prompt "$INPUT"}
         {:workflow "builder"
          :prompt "Execute this plan:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}
         {:workflow "reviewer"
          :prompt "Review the following implementation:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}]}

Coordinate a plan-build-review cycle. Ensure each step builds on the previous output.
