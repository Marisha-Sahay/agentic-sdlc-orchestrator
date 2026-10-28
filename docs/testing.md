# Testing approach

Two systems are under test: the orchestrator (does governance work?) and the service it produces
(is the output production quality?). Both are tested at several levels, and the orchestrator's
own outputs are tested by real builds during every run.

```bash
mvn verify                 # everything below except scenario replays (~20 s; needs Docker)
./agentic run drill        # end-to-end governance drill (~25 s)
```

## Levels

| Level | What | Where | Count |
|---|---|---|---|
| Engine behaviour | One test per governance mechanism, with plain-lambda tasks: parallel joins, retries with feedback, fallback, rollback on gate failure, policy block before write, approval rejection loop, amendment-driven re-planning (incl. execution numbering), plan expansion and joins, failure skip + safe stop, kill switch + resume, attempt budget, timeouts, entry gates | `orchestrator-core` `WorkflowEngineTest` | 13 |
| Core components | Graph validation and rewiring, policy rules (table-driven), git workspace (rollback, untracked files, path traversal), hash-chained log (tamper detection), metrics (exact durations with a manual clock), command gates (timeouts, fail-closed) | `orchestrator-core` | 33 |
| Agent contracts | Each agent runs inside a real engine with a **scripted model** and a real git workspace (`AgentHarness`): prompts carry the right context, replies map to artifacts/changes/decisions, malformed replies are retried with the parse error, scanners and gates behave as specified | `orchestrator-agents` `AgentsTest` | 10 |
| Agent infrastructure | Contract parsing, record/replay (drift, fixtures, simulated outages, no key leakage), model settings, LangChain4j integration offline (every contract yields a JSON schema; request shape; token mapping; Gemini client construction), JavaParser index **against the real baseline service**, gates and validators | `orchestrator-agents` | 28 |
| Workflow shape and reporting | The three templates (parallel branches, joins, mandatory checkpoints, bounded retries, fallbacks), scenario files, HTML report, progress output, console approvals including the edit-and-amend flow | `orchestrator-app` | 9 |
| Service unit | Code generator, URL and alias policies (table-driven), service collision retry, resolver/cache, rate limiter with a mutable clock, web layer (every status code and ProblemDetail shape), wiring | `url-shortener` (surefire) | 124 |
| Service integration | Full application against **real PostgreSQL 17** (Testcontainers, one shared container): lifecycle, repository, rate limiting, actuator | `url-shortener` (failsafe, `*IT`) | 16 |
| End to end | Scenario runs through the CLI: real agents, policy, gates and Maven builds; model replies replayed (recorded or hand-authored). The drill plants one fault per control | `./agentic run <scenario>`; CI step | 1 drill + recorded scenarios |

## Gates on the test code itself
Every module runs Checkstyle (Google style, warnings fail the build) and a JaCoCo **80% line
coverage gate**. Current line / branch coverage:

| Module | Line | Branch |
|---|---|---|
| orchestrator-core | 90.3% | 80.7% |
| orchestrator-agents | 91.1% | 76.0% |
| orchestrator-app (excluding the `AgenticCli` composition root) | 90.1% | 76.2% |
| url-shortener (unit + integration, merged) | 99.6% | 94.9% |

`AgenticCli` (argument parsing, wiring, `System.exit`) is excluded from the unit-coverage gate and
covered by the end-to-end runs instead; the shortener similarly excludes its `main` class.

## Testing the orchestrator's outputs during a run
The code agents write is tested before it is accepted: each implementation task must compile; the
test stage must pass `mvn test`; `verify` runs the full `mvn verify` (unit + Testcontainers ITs +
Checkstyle + coverage) and only then is release readiness reached. Without Docker, `verify`
degrades to unit tests and the run records `verifyProfile: DEGRADED` so the report cannot
overstate what was verified.

## Design choices in the tests
- **Deterministic time.** Engine metrics tests use a manual clock; service tests inject a mutable
  `Clock`. No test sleeps to wait for time to pass, except the timeout tests which use 100–200 ms
  limits.
- **Real infrastructure where it matters.** Git (rollback), Maven (gates in the drill) and
  PostgreSQL (ITs) are real; only the model is substituted.
- **One assertion focus per test, named for behaviour** (`killSwitchPausesTheRunAndResumeFinishes
  WithoutRepeatingWork`), so the test list reads as a specification of the controls.

## Not covered
- **Live model quality.** Unit tests use scripted models; how well a given Gemini model performs
  is observed in recorded runs, not asserted. Record/replay makes those runs reproducible after
  the fact.
- **Load and soak testing** of the service (the 2,000 redirects/s target in the ambiguous
  scenario would need a Gatling/k6 run against a deployed instance).
- **Concurrency stress** of the engine beyond the parallel-join, timeout and lock tests.
