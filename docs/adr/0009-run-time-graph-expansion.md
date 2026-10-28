# ADR-0009: The planner expands the graph at run time

Status: accepted, 2026-10-28

## Context
The workflow template is static, but implementation tasks are only known after design: how many,
and which depend on which.

## Decision
The `plan` node has a `GraphExpansion`: after its output is accepted, the plan is parsed and one
node per task is inserted between `plan` and its dependents, with the planner's dependencies
mapped to node dependencies. The `plan-validity` gate checks the plan with the same graph
validation the engine uses (acyclic, resolvable, at most 8 tasks) before it is accepted.

## Consequences
- Independent tasks run in parallel; dependent ones are sequenced; `integrate` joins them all.
- A bad plan is rejected with evidence and retried, never half-inserted.
- Expansion is a pure function of stored artifacts, so resume rebuilds the subgraph without
  calling a model.
