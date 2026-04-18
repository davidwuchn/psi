Goal: make graph attrs discoverable at runtime so agents never have to guess attr names.

Context:
- The EQL graph already exposes :psi.graph/resolver-syms and :psi.graph/root-queryable-attrs
- root-queryable-attrs reveals entry points but not the attrs produced by nested resolvers
  (e.g. :psi.session-info/id vs :psi.session-info/session-id within context-sessions)
- Agents currently rely on prompt hints or source inspection to find nested attr names —
  both are fragile and lag behind the actual resolver definitions
- psi.graph.analysis/operation->metadata already extracts ::pco/input and ::pco/output
  from the live Pathom operation config — the data is available now
- Crucially, ::pco/output already carries the full nested join structure, e.g.:
    {:output [:psi.agent-session/session-id
              {:psi.agent-session/context-sessions
               [:psi.session-info/id :psi.session-info/name ...]}]}
  This is the join graph we need — it's in the live resolver metadata, not hardcoded

Implementation path:
- operation->metadata is the source of truth — no new data capture needed
- Add :psi.graph/resolver-index to query-graph-bridge output:
    [{:psi.resolver/sym    <qualified-symbol>
      :psi.resolver/input  [<attrs>]
      :psi.resolver/output [<attrs-and-joins>]}]  ; preserves join maps
  Filter to psi.* namespace attrs only (exclude :psi/agent-session-ctx internals)
- Add :psi.graph/resolver-detail resolver that takes :psi.resolver/sym as input
  and returns the full I/O for a single resolver (mirrors tool-detail/skill-detail pattern)
- Add :psi.graph/attr-index derived from resolver-index:
    {:psi.session-info/id   {:produced-by [<sym> ...]
                              :reachable-via {:psi.agent-session/context-sessions [...]}}}
  The :reachable-via map shows the join path — which root attr to query and what
  join shape to use to reach this attr
- Both resolver-index and attr-index are derived from (session-resolver-surface) at
  query time, so they stay in sync with resolver definitions automatically

Workflow this enables:
  Agent wants to query child session attrs:
  1. psi-tool(action: "query", query: "[:psi.graph/root-queryable-attrs]")
     → sees :psi.agent-session/context-sessions in the list
  2. psi-tool(action: "query", query: "[{:psi.graph/attr-index [:psi.agent-session/context-sessions]}]")
     → or simpler: query resolver-detail for the resolver that produces context-sessions
     → sees output join: {:psi.agent-session/context-sessions [:psi.session-info/id :psi.session-info/name ...]}
  3. Agent knows exactly which attrs to request — no guessing, no source reading

  Or more directly:
  1. psi-tool(action: "query", query: "[:psi.graph/resolver-index]")
     → scan for resolver whose output contains :psi.agent-session/context-sessions
     → read the join shape directly

Design questions resolved:
- Q: Is ::pco/output available from live env?
  A: Yes — pco/operation-config returns it; operation->metadata already uses it
- Q: Does output carry the join graph?
  A: Yes — join maps are preserved in the output vector as-is
- Q: resolver-detail vs resolver-index?
  A: Both. Index for scanning, detail for focused lookup (consistent with tool/skill pattern)
- Q: Filter scope?
  A: Exclude :psi/* internal seed keys (:psi/agent-session-ctx etc); include all :psi.* attrs

Acceptance:
- :psi.graph/resolver-index is queryable and returns all resolver I/O including join shapes
- :psi.graph/resolver-detail resolver takes :psi.resolver/sym and returns single resolver I/O
- :psi.graph/attr-index maps each psi.* attr to the resolver(s) that produce it and the
  join path needed to reach it
- An agent can discover all valid attrs for any resolver output without reading source
- All three are derived from the live (session-resolver-surface) — no manual maintenance
- System prompt graph-discovery guidance can drop hardcoded attr hints
