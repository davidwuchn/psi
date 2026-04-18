- [x] Define the canonical `psi-tool` session-targeting contract
- [x] Reproduce the current failure modes with focused tests
- [x] Fix the targeted introspection path so non-active sessions can be queried reliably
- [x] Add tests and docs for the supported query forms

Verification
- Focused relevant suites are green:
  - `psi.agent-session.graph-surface-test`
  - `psi.agent-session.resolvers-test`
  - `psi.agent-session.eql-introspection-test`
  - `psi.agent-session.tools-test`
- Last focused run: `58 tests, 1157 assertions, 0 failures`
