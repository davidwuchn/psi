# META

A meta model for psi.

- psi is written in clojyre
- psi runs as a process on a developer's machine
- psi is a project

- psi works on a project
- a project's source of truth is its github repository.
- a project is identified by its github origin url, or local url if no origin

- a UI is conceptually a view of the project's meta model

- the project has a meta model
- the project meta model describes what is included and excluded in the project scope
- the meta model may have aspirational and implemented parts

- psi has an execution engine that runs statecharts
- psi has a query and mutation engine that uses EQL
- psi has EQL resolvers that provide introspection of the project
- psi has EQL mutations that provide extension points for the project
- psi has a capability graph that is queryable via EQL
