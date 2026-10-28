# Requirements (restated)

The brief this project answers is confidential and not included in the repository. These are its
requirements in my own words, normalised into checkable statements. IDs are used in the
[traceability matrix](requirements-traceability.md).

## Objective
Build a working prototype that turns a requirement into a reviewable engineering outcome through
an agentic execution model: understanding, decomposition, multi-step execution, and generation
plus validation of outputs, across the SDLC, with controlled autonomy. The vehicle is a URL
shortener with core APIs, analytics and reliability features, built and evolved with AI
assistance.

## Core requirements

| ID | Requirement |
|---|---|
| R1 | **Requirement understanding**: interpret intent, surface ambiguity, normalise into a clear engineering problem. |
| R2 | **Task decomposition**: actionable tasks with dependencies and sequencing. |
| R3 | **Codebase reasoning (brownfield)**: identify impacted modules, services, APIs and data flows. |
| R4 | **Workflow orchestration** across requirements, design, implementation, testing, documentation and release readiness, non-linear and stateful, with governance. Specifically: |
| R4.1 | an explicit dependency graph with entry and exit gates |
| R4.2 | sequential and parallel paths with synchronization |
| R4.3 | cross-stage context and decision lineage |
| R4.4 | human approval checkpoints for high-impact actions |
| R4.5 | bounded retries, fallback, rollback and safe-stop controls |
| R4.6 | policy guardrails for security, compliance and change control |
| R4.7 | audit-grade observability and traceability |
| R4.8 | reliability metrics: success rate, retry/rollback frequency, MTTR, end-to-end latency |
| R4.9 | dynamic re-planning when upstream outputs change, while keeping governance and controlled autonomy |
| R5 | **Engineering output**: production-quality code, API/schema definitions, unit and integration tests, documentation. |
| R6 | **Validation and risk control**: risks, trade-offs, failure scenarios, validation and safety guardrails. |
| R7 | **Controlled autonomy**: agents execute multi-step work; humans provide oversight, approvals and final quality control. |
| R8 | **Final engineering summary**: plan and rationale, artifacts, risks/trade-offs/validation, assumptions, limitations. |

## Scope
Greenfield (new system or feature), brownfield (enhancement, refactor, bug fix), test and
documentation improvements, and both well-defined and ambiguous requirements.

## Deliverables

| ID | Deliverable | Where |
|---|---|---|
| D1 | Working prototype, runnable end to end | `./agentic run <scenario>`; [README](../README.md#quickstart) |
| D2 | Architecture overview: components, orchestration model, control flow, key decisions | [architecture.md](architecture.md), [ADRs](adr) |
| D3 | Three scenarios (greenfield, brownfield, ambiguous), each showing decomposition, orchestration and validation | [scenarios](scenarios) |
| D4 | Setup instructions | [README](../README.md#quickstart) |
| D5 | Testing approach, limitations and trade-offs | [testing.md](testing.md), [risks-and-limitations.md](risks-and-limitations.md) |

## Assumptions made to make the brief concrete
- "Analytics" means per-link click statistics (brownfield scenario); "reliability features" means
  input validation, abuse protection (rate limiting, URL policy), consistent errors, caching and
  health checks.
- "Humans provide final quality control" means a mandatory release sign-off on a patch the human
  can inspect, not an automatic merge.
- A single-process prototype is acceptable if the seams to a production deployment are explicit
  ([architecture §10](architecture.md#10-production-path)).
