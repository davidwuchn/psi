# META

A meta model for psi.

- psi is written in Clojure
- psi runs as a process on a developer's machine
- psi is also a project

- psi works on a project
- a project's source of truth is its git repository
- a project is identified by its GitHub origin URL, or local URL if no origin exists

- a UI is conceptually a view over the project's meta model

- the project has a meta model
- the project meta model describes what is included and excluded in project scope
- the meta model may have aspirational and implemented parts

- psi has an execution engine that runs statecharts
- psi has a query and mutation engine that uses EQL
- psi has EQL resolvers that provide introspection of the project
- psi has EQL mutations that provide extension points for the project
- psi has a capability graph that is queryable via EQL

- psi interacts with AI LLM models via sessions
- a session has a system prompt
- a session contains a sequence of messages (user, assistant, tool, and system/extension messages)
- sessions support tools
- assistant reply messages are streamed
- a session has a start instant
- a session has a unique id
- a session may be named
- a session may have a summary line
- a session runs in the context of a git worktree
- a session may be forked
- a subagent session may optionally fork (inherit) the invoking session conversation context
- a session may contain other sessions (e.g., subagent sessions)
