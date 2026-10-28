# ADR-0001: Hand-written graph engine in pure Java 21

Status: accepted, 2026-10-28

## Context
The orchestration layer is the core of the system: dependency graph with entry/exit gates,
parallel paths with joins, approvals, retries, fallback, rollback, safe stop, audit, metrics and
re-planning. Frameworks exist (LangGraph and its Java port LangGraph4j, Spring AI workflows,
Temporal).

## Decision
Write the engine (`orchestrator-core`) in plain Java 21 with no framework and no LLM dependency.
Use LangChain4j only as the model client ([ADR-0010](0010-langchain4j-structured-output.md)).

## Alternatives
- **LangGraph4j**: good state-graph primitives, but governance (policy, gates, approvals with
  amendments, git rollback, hash-chained audit) would still be custom, and the most important
  mechanics would sit behind a framework abstraction.
- **Temporal**: durable execution for free, but heavy infrastructure for a prototype, and its
  workflow model (deterministic replay of code) would dominate the design.
- **Linear chain of calls**: does not satisfy parallelism, joins, re-planning or resumability.

## Consequences
- Every mechanism is explicit, small (the whole core is ~3.7k lines including Javadoc) and
  unit-tested in isolation with
  plain lambdas: 45 tests cover parallel joins, retries, fallback, rollback, policy blocks,
  approvals, amendments, expansion, safe stop, budgets, timeouts and resume.
- Java 21 features do real work: virtual threads make blocking agents and human approvals cheap;
  a sealed `RunEvent` hierarchy with records and pattern-matching `switch` makes the log, metrics
  and report exhaustive by construction.
- Durability is ours to provide: solved with the event log and resume
  ([ADR-0002](0002-event-log-as-source-of-truth.md)); multi-process scheduling is out of scope.
