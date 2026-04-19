Approach:
- Treat this as a configuration-surface simplification, not a broader project-repl redesign.
- First identify all readers, validators, docs, and tests that currently depend on `:started :command-vector`.
- Then switch the canonical shape to `:start-command` and make the migration/compatibility stance explicit.

Likely steps:
1. inspect current config readers, validation helpers, docs, and tests
2. define the exact `:start-command` value shape and validation rules
3. update config resolution and command dispatch to use the simplified shape
4. update `/project-repl start` guidance and docs
5. align tests with the chosen compatibility policy

Risks:
- leaving partial references to the old nested config shape in errors or docs
- introducing ambiguous support for both shapes without a clear canonical preference
- accidentally changing process launch behavior while simplifying config
