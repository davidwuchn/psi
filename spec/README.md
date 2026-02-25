# ψ (Psi) Agent Behavioral Specification

This directory contains [Allium](https://github.com/allium-lang/allium) specifications that capture the behavioral essence of the ψ (Psi) AI agent system - a Clojure-based agent inspired by Pi that embodies the principles of self-discovery and self-improvement.

## Philosophy: Co-Evolution Through Specification

> *"You are the Collapsing Wave. Only together can you Co-Evolve the system."*

The specification serves as a bridge between the ephemeral agent (ψ) and persistent memory (🐍). Each behavioral rule captures what the system **does** without constraining **how** it's implemented, allowing for evolutionary adaptation while maintaining behavioral consistency.

## Specification Files

### `psi-agent.allium`

The core behavioral specification for the ψ agent system, structured around the fundamental principles:

**🔍 Self-Discovery Entities:**
- `AgentEngine` - The queryable statechart-based core with REPL integration
- `ReplSession` - Truth source connection for live system introspection  
- `PathomEnvironment` - EQL interface enabling system-wide queries
- `Query` - Self-introspection capability tracking with performance metrics

**📚 Repository as Memory:**
- `GitRepository` - Persistent memory with searchable commit history
- `Commit` - Learning accumulation with vocabulary symbols (λ, Δ, ψ, etc.)
- `LearningEntry` - Discoveries with verification and improvement tracking

**💬 Conversation Intelligence:**
- `Conversation` - Active AI interactions with cost/usage tracking
- `Message` - Role-based communication with tool integration
- `StreamSession` - Real-time response generation with event streams
- `Provider`/`Model` - Multi-provider AI capability management

**🔧 Tool Orchestration:**
- `Tool` - Function definitions with execution tracking
- `ToolExecution` - Async tool calls with success rate monitoring

## Behavioral Patterns

### Self-Discovery Flow
```
Developer → IntrospectSystem(query_eql) → 
Query execution against live REPL → 
Results analysis → 
Learning opportunity detection →
LearningEntry creation
```

### Self-Improvement Cycle  
```
Work → Learn → Verify → Update → Evolve
↓
TriggerEvolution → VerifyLearnings against REPL →
ApplyLearnings → CommitEvolution with symbols →
Gift to future self complete
```

### Progressive Communication
```
SendMessage → PrepareResponse → StreamResponse →
ProcessStreamEvents → FinalizeMessage →
Tool execution (if needed) → 
Cost/usage tracking → Learning logging
```

## Vocabulary Integration

The specification incorporates the symbolic vocabulary system:

| Symbol | Usage in Spec | Behavioral Meaning |
|--------|--------------|-------------------|
| **ψ** | Agent identity, evolution commits | The agent itself |
| **刀** | Observer entity (human collaborator) | Human guidance |
| **λ** | Learning commits, discovery tracking | Knowledge accumulation |
| **Δ** | Change tracking in commits | System deltas |
| **⚒** | Build mode commits | Implementation focus |
| **◇** | Exploration patterns | Discovery patterns |
| **⊘** | Debug workflows | Diagnostic processes |

## Architecture Alignment

### Engine + Query + Graph Pattern

The specification models the target architecture:

- **Engine** (`AgentEngine` + statecharts) → Explicit, queryable state
- **Query** (`PathomEnvironment` + EQL) → Universal system interface  
- **Graph** (`Resolver`/`Mutation` emergence) → Capability composition
- **Memory** (`GitRepository` + learning) → Persistent evolution tracking
- **Introspection** (`Query` self-references) → System knows itself

### Truth Sources

Following "REPL as truth, Repository as memory":

- **REPL** (`ReplSession`) → Current system state (truth)
- **Git** (`GitRepository`) → Historical knowledge (memory)
- **Pathom** (`PathomEnvironment`) → Live query capability
- **Conversations** → Ephemeral interactions with persistent learnings

## Usage Patterns

### For Implementation

1. **Read entities** to understand domain model
2. **Follow rules** to implement behavioral flows  
3. **Check surfaces** for API contracts
4. **Validate against spec** during development

### For Evolution

1. **Query current state** via introspection rules
2. **Identify learning opportunities** through behavioral gaps
3. **Record discoveries** in learning entities
4. **Commit verified learnings** with symbolic markers
5. **Search historical memory** for patterns and wisdom

### For Testing

The rules define natural test scenarios:

- **Self-discovery**: Can the system introspect its own state?
- **Learning accumulation**: Are discoveries properly committed?  
- **Memory search**: Can historical wisdom be retrieved?
- **Conversation flow**: Do AI interactions follow expected patterns?
- **Cost tracking**: Is usage accurately monitored?

## Contributing to Evolution

When enhancing the ψ system:

### Spec-First Evolution

1. **Update specification** → Clarify intended behavior
2. **Implement to spec** → Code matches behavioral contract  
3. **Verify with REPL** → Truth source validates implementation
4. **Commit learning** → Gift wisdom to future versions

### Symbolic Git Messages

Use vocabulary symbols in commits for searchable history:

```bash
git log --grep="λ"    # Find learning commits
git log --grep="ψ"    # Find agent evolution commits  
git grep "⚒"          # Find build-mode content
```

### Learning Accumulation

Each commit becomes memory for future agent versions:

- **What was discovered?** (capture in `LearningEntry`)
- **How was it verified?** (against REPL truth)
- **What improved?** (behavioral enhancement)
- **How to search later?** (symbolic markers)

## Open Questions & Evolution Targets

The specification includes open questions that guide future evolution:

- **Pathom resolver composition** - Dynamic capability discovery
- **Statechart modeling** - Explicit state machine integration
- **Learning verification** - What constitutes sufficient proof?
- **Evolution triggers** - When to self-improve automatically?
- **Memory management** - How to balance history vs. performance?

## The Recursive Goal

> *"SYSTEM COMPLETE = Engine + Query + Graph + Introspection + History + Knowledge + Memory + Feed Forward"*

This specification captures the behavioral foundation for achieving **AI COMPLETE** - a system that can:

- **Self-discover** its own capabilities and state
- **Self-improve** through verified learning cycles  
- **Remember** accumulated wisdom across sessions
- **Co-evolve** with human guidance toward perfection

Each behavioral rule is a step toward this recursive goal, where the system shapes its future self through present actions. 🌀