# Final engineering summary

## Plan and rationale

The brief weights the **orchestration layer** most heavily, so the plan put most of the effort
there and treated the URL shortener as the workload the orchestrator builds and evolves:

1. **Engine first, with fake agents.** A framework-free graph engine in Java 21 where every
   governance requirement (gates, parallel joins, approvals, retries, fallback, rollback, safe
   stop, audit, metrics, re-planning) is an explicit, unit-tested mechanism.
2. **A real baseline service**, production-shaped (Spring Boot 4.1, PostgreSQL, Flyway,
   Testcontainers), so brownfield work happens on real code with a real defect.
3. **Agents behind one seam** (LangChain4j, JSON-mode structured output, record/replay), with
   deterministic analysis (JavaParser) feeding model reasoning and deterministic gates judging it.
4. **Evidence:** a deterministic governance drill that exercises every control under failure,
   live recorded scenarios, a hash-chained audit log, metrics and a run report.

The organising idea: **agents propose, the engine decides, humans approve what matters**, and one
append-only event log records all of it.

## Artifacts

| Artifact | Location |
|---|---|
| Orchestrator (engine, agents, CLI) | `orchestrator-core`, `orchestrator-agents`, `orchestrator-app`, `./agentic` |
| URL shortener v1 (API, schema, tests, docs) | `url-shortener/` (OpenAPI: `url-shortener/api/openapi.yaml`) |
| Scenarios | `scenarios/*.yaml`, greenfield scaffold, `cassettes/` |
| Architecture and decisions | [docs/architecture.md](docs/architecture.md), [docs/adr](docs/adr) (15 ADRs) |
| Requirements and traceability | [docs/requirements.md](docs/requirements.md), [docs/requirements-traceability.md](docs/requirements-traceability.md) |
| Scenario write-ups | [docs/scenarios](docs/scenarios) |
| Run evidence | [docs/evidence](docs/evidence): report, audit log, metrics, patch, traceability, release notes |
| Testing, risks, AI log | [docs/testing.md](docs/testing.md), [docs/risks-and-limitations.md](docs/risks-and-limitations.md), [docs/ai-collaboration-log.md](docs/ai-collaboration-log.md) |

## How the requirements are met (summary)

- **Understanding and ambiguity:** requirements agent → testable spec with explicit questions and
  defaults; a human clarification checkpoint for vague requests; a gate that refuses unresolved
  questions.
- **Decomposition:** planner → tasks with dependencies and requirement ids; validated; expanded
  into the graph at run time; independent tasks run in parallel.
- **Codebase reasoning:** JavaParser index (types, roles, wiring, endpoints, migrations) in
  parallel with requirements; impact analysis grounded against it.
- **Orchestration and governance:** see the [traceability matrix](docs/requirements-traceability.md)
  for R4.1–R4.9, each with code and tests.
- **Output:** code, OpenAPI contract, migrations, unit + integration tests, README/changelog,
  release notes, patch, traceability matrix.
- **Controlled autonomy:** write allow-list, protected guardrails, budgets, mandatory design and
  release sign-off.

## Validation

- **234 automated tests**, Checkstyle and an 80% coverage gate on every module
  (orchestrator 90–91% line coverage; service 99.6% with integration tests on real PostgreSQL).
- **Governance drill** (deterministic, no model): five planted faults (hallucinated file,
  requirement amendment, edit to an applied migration, compile failure, model outage), all caught
  and recovered; final patch passes the full build. 14 nodes, 21 attempts, 5 retries, 1 rollback,
  1 fallback, 4 human checkpoints, 2 re-plan invalidations, MTTR 3.1 s, 129 hash-chained events.
- **Live scenarios** (greenfield, brownfield, ambiguous) run against Gemini through LangChain4j
  and are recorded for replay. See each [scenario page](docs/scenarios) for results.

## Risks and trade-offs (top five)

1. **Model quality bounds live outcomes.** Mitigated by gates, retries with evidence, fallback
   models and human checkpoints; not eliminated.
2. **Single-process engine.** Resume from the log covers crashes and stops; no failover or
   horizontal scale (production path in [architecture §10](docs/architecture.md#10-production-path)).
3. **Deterministic gates miss semantic issues**: covered by human design/release review and the
   advisory model security review.
4. **Positional replay can drift** when the workflow changes; drift is labelled, re-record to fix.
5. **Per-instance cache and rate limits in the service**: documented; shared infrastructure is the
   scale-out answer.

Full list with failure scenarios: [docs/risks-and-limitations.md](docs/risks-and-limitations.md).

## Assumptions
- Analytics means per-link click statistics; reliability features mean validation, abuse
  protection, consistent errors, caching and health checks.
- Final quality control is a human release sign-off on an inspectable patch, not an auto-merge.
- Java/Maven target projects; Docker available for integration tests.

## Limitations
Single process; terminal or scripted approvals (no SSO or pull-request integration);
pattern-based security scanner; whole-source context under a budget (no retrieval for large
repositories); re-planned code nodes write over the current tree. Details in
[docs/risks-and-limitations.md](docs/risks-and-limitations.md#limitations).

## Next steps
Pull-request integration (checkpoints as commits on a run branch, release as a PR), OpenTelemetry
export from the event log, retrieval-based context for large repositories, SAST/SCA tools as
gates, and a durable multi-run scheduler.
