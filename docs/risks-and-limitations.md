# Risks, trade-offs, failure scenarios and limitations

## Failure scenarios and how they are contained

| Failure | Detection | Containment |
|---|---|---|
| Model returns malformed or off-contract output | JSON contract parse, `ContractGate` validators | Attempt rejected; parse error fed back; bounded retries; fallback model |
| Model hallucinates files or APIs | `impact-grounding` against the JavaParser index; compiler | Rejected with the list of invented paths |
| Generated code does not compile / tests fail | `maven-compile`, `maven-unit-tests`, `maven-verify` | Rollback to last checkpoint; retry with tool output; `verify` self-repair is bounded and gated |
| Agent tries to weaken a gate (edit Checkstyle, CI) or escape the workspace | `workspace-scope` policy; `GitWorkspace` path checks | Blocked before anything is written |
| Agent edits an applied migration | `schema-migration` policy | Blocked; new migrations need human approval |
| Breaking API change | `api-compatibility` gate; contract changes need approval | Rejected; design retried |
| Secret written to code or artifacts | `secret-leak` policy | Blocked |
| Model provider outage | Task error | Retries with backoff, then fallback strategy (second model, or deterministic template for docs) |
| Hung model call or build | Per-attempt timeout; command gates kill the process tree | Attempt fails and is retried |
| Runaway cost or loops | Attempt, model-call and wall-clock budgets; failure threshold | Safe stop; run ends `PAUSED`, resumable |
| Operator needs to stop now | `agentic stop` (kill switch) or Ctrl-C | No new attempts; running ones finish atomically; report still written |
| Reviewer disagrees | Reject with comment | Rollback; agent retries with the comment as feedback |
| Requirements change mid-run | Amendment at any checkpoint | Content-hash invalidation re-runs exactly the affected nodes |
| Audit log tampered with | Hash chain | `agentic verify-log` reports the first broken event |
| Policy rule or gate throws | Exception | Fail closed: BLOCK / gate failure, never silent pass |

## Trade-offs

| Decision | Benefit | Cost |
|---|---|---|
| Hand-written engine ([ADR-0001](adr/0001-custom-graph-engine.md)) | Every mechanism explicit and testable; no framework abstraction over the core | We own durability (event log + resume) and there is no distributed scheduler |
| Deterministic gates only ([ADR-0004](adr/0004-deterministic-gates.md)) | Reproducible, defensible acceptance | Semantic quality beyond tools relies on human checkpoints |
| Full-file proposals ([ADR-0003](adr/0003-agents-propose-engine-applies.md)) | No failed patch application | Token cost on large files |
| One shared workspace with a lock | Simple, consistent tree; model work still parallel | Apply-and-gate is serialised; builds run one at a time |
| Positional record/replay ([ADR-0008](adr/0008-positional-record-replay.md)) | Replays survive non-deterministic prompt text | A changed workflow can replay the wrong cassette; mitigated by drift labelling |
| Whole-source context under a budget | Simple and effective for a small service | Does not scale to large codebases without retrieval |
| Scanner-only blocking security gate | No model false positives stalling runs | Logic flaws found only by the model reviewer are advisory |
| In-process cache and rate limiter (service) | No extra infrastructure, low latency | Per-instance state in a multi-instance deployment |

## Limitations

- **Single process.** The engine, its log and its workspace live on one machine; a crash loses
  only in-flight attempts (resume recovers the rest), but there is no failover.
- **Live runs depend on model quality.** The orchestration is model-agnostic; the outcome quality
  of a live run is bounded by the configured model. Record/replay makes runs reproducible but not
  better.
- **Re-planned code nodes write over the current tree.** Files that an earlier execution created
  and a later one does not are left in place (they still have to pass every gate).
- **Approvals are terminal-based or scripted.** No SSO identity, no pull-request integration; the
  scripted reviewer is clearly labelled in the audit trail.
- **Policy is code, not configuration.** Changing a rule is a code change (reviewed, tested); there
  is no runtime policy editor.
- **The security scanner is pattern-based**, not a full SAST/SCA tool; dependency vulnerabilities
  are only caught via the dependency-change approval.
- **Docker is required for integration tests.** Without it, verification degrades to unit tests
  and the run records the degradation.
- **Service scope:** single-region, no authentication on the management API (create/delete), no
  per-user ownership of links. Rate limits and the redirect cache are per instance.

## Assumptions

- A human reviewer is available at design and release checkpoints for interactive runs; unattended
  runs use explicit scripted decisions stored in the scenario file.
- The target is a Java/Maven project; the gates and the codebase indexer are Java-specific, while
  the engine is not.
- Model credentials are provided via environment or `.env`; they are never logged, stored in
  cassettes, or written by agents.
