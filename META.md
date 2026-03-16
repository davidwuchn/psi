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
- psi hosts multiple sessions concurrently inside one process
- psi has a session host registry and one active session pointer
- psi Context owns resources and a tree of sessions.
- Sessions are the unit of conversation, execution, and lineage.

- active session selection controls default routing for prompts/mutations when session id is omitted
- switching active session does not stop or block in-flight runs in other sessions
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
- a session may be forked from an earlier entry in its history
- a session may be created as a new root session from global baseline configuration
- a subagent session may optionally fork (inherit) the invoking session conversation context
- spawned sessions inherit parent session configuration snapshot (tools/extensions/prompt layers/model), then evolve independently
- parent-child lineage is immutable after session creation
- a session may contain other sessions (e.g., subagent sessions)

- a git worktree is a first-class context boundary for sessions
- psi uses worktrees with minimal ceremony

- extension widgets have a protocol-level content model designed for cross-UI rendering
- widget content is a vector of line values where line ∈ string | map
- string lines are canonical plain text and must render in every UI
- map lines use a minimal canonical shape: {:text string :action {:type "command" :command string}}
- map line :text is required for deterministic non-interactive fallback rendering
- map line :action is optional; when absent, the line is informational text
- UIs that do not support interaction must ignore :action and render :text
- UIs that support interaction may bind :action to native affordances (e.g. Emacs text buttons)
- command actions are interpreted as slash command invocations in session context
- extension widget protocol additions must be backward compatible with existing vector<string> content
- cross-UI interactive widget behavior
