# Final engineering summary

## Plan and rationale

The brief weights the **orchestration layer** most, so most effort went there, with the URL
shortener as the workload the orchestrator builds and evolves:

1. **A governed graph engine** in plain Java 21 where every control (gates, joins, approvals,
   retries, fallback, rollback, safe stop, audit, metrics, re-planning) is an explicit,
   unit-tested mechanism.
2. **A real baseline service** (Spring Boot 4.1, PostgreSQL, Flyway, Testcontainers), so
   brownfield work changes real code with a real defect (issue #1).
3. **Agents behind one seam** (LangChain4j, JSON-mode structured output, record/replay), with
   deterministic analysis feeding the models and deterministic gates judging them.
4. **Evidence:** four scenario runs whose audit logs, artifacts, patches and reports are committed
   and checked in CI.

Organising idea: agents propose; the engine decides; humans authorize what will execute and
accept what was validated; one hash-chained event log records all of it.

## Artifacts

| Artifact | Location |
|---|---|
| Orchestrator | `orchestrator-core`, `orchestrator-agents`, `orchestrator-app`, `./agentic` |
| v1 service (stable baseline) | `url-shortener/` |
| **Final service** (v1 + click analytics + issue #1 fix) | `tools/final-service.sh` → `target/final-service` (v1 + [brownfield release.patch](docs/evidence/brownfield/release.patch)) |
| Scenarios and evidence | [docs/scenarios](docs/scenarios/README.md), [docs/evidence](docs/evidence) |
| Architecture and decisions | [architecture.md](docs/architecture.md), [c4-model.md](docs/c4-model.md), [ADRs](docs/adr) (17) |
| Requirement-to-evidence checklist | [requirements-traceability.md](docs/requirements-traceability.md) |
| Testing, risks, AI log | [testing.md](docs/testing.md), [risks-and-limitations.md](docs/risks-and-limitations.md), [ai-collaboration-log.md](docs/ai-collaboration-log.md) |

## Governance, briefly

- **Authorization before execution** for build-file changes; **acceptance after validation** for
  sign-offs, migrations, contract and large changes; both bound to the proposal hash, acceptance to
  the validated tree hash. Gate settings in `pom.xml` cannot be changed by agents.
- **Fail-closed reviewers:** nothing is approved by default; an unreachable reviewer pauses the
  run; scripted decisions are labelled demonstrations and cover named reasons only; amendments are
  validated.
- **Rollback on every failure path**, quarantine if rollback fails, fail-stop audit log.
- **Revision-safe parallelism:** snapshots per attempt, stale proposals rejected, invalidated
  in-flight work discarded.
- **Final validation** re-runs every deterministic check on the one tree that release is bound
  to; degraded verification needs an explicit waiver.
- **Verified resume:** hash chain, artifact hashes and run identity checked; only accepted work
  restored; budgets carried over.

## Validation (what was run, and what it showed)

- `mvn verify` (all modules, with Docker): **290 tests pass**, Checkstyle clean, coverage gates met
  (core 89.8% line; v1 service 99.6%).
- **Final service:** 153 unit + 24 PostgreSQL integration tests, 99.3% line coverage; it is
  byte-identical to the code developed and tested for v2.
- **Scenarios** (each about 35-40 s, all SUCCEEDED, evidence checked by `agentic check-evidence`):

  | Scenario | Nodes | Attempts | Decisions | Notable |
  |---|---|---|---|---|
  | greenfield | 17 | 17 | 5 (one authorization before the `pom.xml` build) | output byte-identical to `url-shortener/` |
  | brownfield | 17 | 18 | 5 (design rejected once, then approved) | output is the final service |
  | ambiguous | 18 | 18 | 5 (clarify with demonstration answers) | 5 questions resolved before design |
  | drill | 15 | 22 | 4 | 5 planted faults recovered, 1 rollback, 1 fallback, MTTR 3.4 s |

- **Provenance:** every model reply in this evidence is a **hand-authored fixture** (labelled in
  the logs) and every reviewer decision a **scripted demonstration**. The evidence shows the
  orchestration and real, validated code; it does not show model quality.
- **Live model (partial):** one recorded brownfield run with Gemini 2.5 Flash reached
  implementation (grounded impact analysis, rejection loop, 8-task plan, issue #1 fix compiled)
  and then paused, fail-closed, on a schema migration and a new third-party dependency that no
  reviewer had decided ([live.md](docs/scenarios/live.md)). No live run has reached release;
  greenfield and ambiguous were not run live.
- **Docker sandbox:** the drill ran with every build in the container (no network, limits,
  PostgreSQL sidecar) and succeeded on the same path in 44 s; a probe inside saw no API key, no
  host files, no internet, no Docker socket.
- **Load:** one laptop run of the final service: 40,779 cached redirects/s, p99 9 ms, 0 failures;
  the click queue saturated at that rate and dropped 9.7% of clicks, all counted
  ([testing.md](docs/testing.md#load-test)). Not a production capacity claim.

## Risks and trade-offs (top five)

1. **Model quality bounds live outcomes.** Gates, bounded retries, fallback and fail-closed
   approvals contain it; they do not make a weak model good.
2. **Execution isolation is opt-in.** The default host sandbox only removes secrets from the
   environment; `--sandbox docker` is the isolated mode.
3. **Single process, no fsync.** Resume is verified and tested at acceptance boundaries; an OS
   crash can still lose the last events.
4. **Best-effort analytics.** No redirect latency or failure from analytics, at the price of
   counted loss under saturation or crashes ([ADR-0017](docs/adr/0017-best-effort-click-analytics.md)).
5. **Per-instance cache and rate limits; unauthenticated management API.** Documented with their
   scale-out answers in the final service README.

Full list: [risks-and-limitations.md](docs/risks-and-limitations.md).

## Assumptions
- Analytics means per-link click statistics; reliability means validation, abuse protection,
  consistent errors, caching and health checks.
- Final quality control is a human release sign-off on an inspectable patch bound to a validated
  tree, not an auto-merge.
- Java/Maven targets; Docker available for integration tests.

## Next steps
Live recordings with a human reviewer for all three scenarios; pull-request integration
(checkpoints as commits, release as a PR); the Docker sandbox as the default with per-run
dependency resolution under authorization; OpenTelemetry export from the event log; SAST/SCA as
gates; fsync and a durable store for the event log.
