# Testing approach

Two systems are under test: the orchestrator (does governance hold, including under failure?)
and the service it produces (is the output production quality?). The orchestrator's outputs are
also tested by real builds inside every run.

```bash
mvn verify                                   # 292 tests, Checkstyle, coverage gates (Docker)
./agentic run drill                          # governance drill (~35 s)
./agentic run brownfield --approvals scripted
tools/final-service.sh && mvn -f target/final-service/pom.xml verify   # final service
```

## Levels

| Level | What | Where | Count |
|---|---|---|---|
| Engine mechanisms | Parallel joins, retries with feedback, fallback, rollback on gate failure, policy block, approval loop, re-planning, expansion, safe stop, kill switch + resume, budgets, timeouts | `orchestrator-core` `WorkflowEngineTest` | 14 |
| Governance invariants | Authorization before execution; fail-closed reviewers; rollback on partial apply, checkpoint, gateway failure; quarantine when rollback fails; stale parallel proposals rejected; invalidated in-flight work discarded (input and generation paths); evidence and acceptance bound to the validated tree; a gate that rewrites the tree; amendment validation; audit-log fail-stop | `GovernanceTest` | 13 |
| Recovery | Resume refuses a tampered log, a modified artifact, a different workflow; recovers only accepted work when cut after a checkpoint, after artifact publication, and after the success event (4 crash points, from a real run's truncated log); budgets carried over or reset with a record | `ResumeTest` | 8 |
| Core components | Graph validation, policy rules incl. build integrity (7 ways to weaken a gate) and stage scopes, git workspace incl. snapshots, hash-chained log, metrics, command gates and sandboxes, scripted gateway | `orchestrator-core` | 46 |
| Agent contracts | Each agent in a real engine with a scripted model and git workspace; degraded verification needs a waiver; final validation marks evidence stale after a code repair | `orchestrator-agents` | 52 |
| CLI and reporting | Templates (evidence order, overlap serialisation), approval-mode resolution (no auto-approve, unknown modes rejected), evidence checker (missing or tampered evidence fails, including an exported patch that differs from the accepted artifact), run lock (a second process is refused), report and console | `orchestrator-app` | 17 |
| v1 service | Unit 126, Testcontainers ITs 16 | `url-shortener` | 142 |
| Final service | Unit 153, ITs 24 incl. `ClickAnalyticsIT`, `CachedLinkExpiryIT`, `ContractConformanceIT` | `target/final-service` (CI) | 177 |
| End to end | Four scenarios through the CLI with real agents, policy, gates, Maven builds; replies are hand-authored fixtures | CI step; `docs/evidence` | 4 runs |

Concurrency tests force ordering with latches released by audit events (for example "release
the consumer when the amender's success is logged"), never with sleeps; the governance and
resume tests were run six times in a row without a failure while developing. Failure injection
uses a delegating `FaultyWorkspace` (fail mid-apply, fail the commit, fail the rollback) and an
audit file that becomes a directory mid-run.

## Gates on the test code itself
Every module runs Checkstyle (warnings fail the build) and an 80% line coverage gate. Line
coverage: orchestrator-core 89.8%, v1 service 99.6%, final service 99.3% (unit + integration,
merged). `AgenticCli` (composition root) and the service `main` class are excluded.

## Execution sandbox
Gate commands always run with an allow-listed environment (unit-tested). The opt-in Docker
sandbox (`--sandbox docker`) was checked by hand on this machine (Docker Desktop 29.9, Apple M4):
after `warmup-sandbox` filled the dependency cache, the v1 service's full `mvn verify` (unit +
integration tests against the private PostgreSQL sidecar, Checkstyle, coverage) passed offline in
13 s; a probe script inside the sandbox saw no `GEMINI_API_KEY` (set in the caller's
environment), no `/Users`, no Docker socket and no route to the internet, and containers and
networks were removed afterwards. The whole drill then ran with every gate in the sandbox
(`./agentic run drill --sandbox docker`): SUCCEEDED on the same path (15 nodes, 5 retries, 1
rollback, 1 fallback) in 44 s, evidence check OK, nothing left behind. The committed scenario
evidence used the host sandbox, which is recorded in each run's settings and gate evidence.

## Load test
`tools/loadtest/redirect-load.sh` (ApacheBench) measures the cached redirect path of a running
final service. One run, 2026-10-29: Apple M4 (10 cores, 16 GB), macOS, OpenJDK 21.0.12, service
jar with `-Xmx1g`, PostgreSQL 17 in Docker Desktop, client and server on the same laptop, 100,000
requests at concurrency 50 with keep-alive on one cached link:

| Metric | Result |
|---|---|
| Throughput | 40,779 requests/s |
| Latency p50 / p90 / p99 / max | 1 / 2 / 9 / 112 ms |
| Failed requests | 0 (all 302; ApacheBench reports them as "non-2xx") |
| Clicks | 102,000 redirects incl. warm-up: 92,146 recorded + 9,854 dropped and counted = 102,000 |

At about 20 times the 2,000 redirects/s target the 10,000-click queue saturated and dropped
9.7% of clicks, counted exactly, without slowing redirects: the best-effort design doing what it
says. This is one measurement on a developer laptop with the client competing for the same CPUs;
it supports, but does not verify, the ambiguous scenario's 2,000 redirects/s, p99 < 50 ms target
for a real deployment (network, cache misses and other links are not exercised). Not a CI gate.

## Not covered
- **Live model quality.** Unit tests use scripted models and the scenario evidence uses
  hand-authored replies; see [scenarios/live.md](scenarios/live.md).
- **Crash-safety beyond what is tested.** Recovery is tested at the persistence boundaries of an
  acceptance by truncating the log; there is no fsync, so an OS crash can lose the last events
  (the hash chain then ends early and resume continues from what is there).
- **Concurrency stress** of the engine beyond the deterministic interleavings above.
