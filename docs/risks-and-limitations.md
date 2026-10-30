# Risks, trade-offs, failure scenarios and limitations

## Failure scenarios and how they are contained

| Failure | Detection | Containment |
|---|---|---|
| Model returns malformed or off-contract output | JSON contract parse, `ContractGate` validators | Attempt rejected; parse error fed back; bounded retries; fallback model |
| Model hallucinates files or APIs | `impact-grounding` against the JavaParser index; compiler | Rejected with the list of invented paths |
| Generated code does not compile / tests fail | `maven-compile`, `maven-unit-tests`, `maven-verify` | Rollback to last checkpoint; retry with tool output; `verify` self-repair is bounded and gated |
| Agent tries to weaken a gate (edit Checkstyle, CI, coverage or skip flags in `pom.xml`) or escape the workspace | `workspace-scope`, `build-integrity` and `stage-write-scope` policies; `GitWorkspace` path checks | Blocked before anything is written |
| Agent adds a build plugin or dependency that would run during the build | `dependency-change` (REQUIRE_AUTHORIZATION) | Nothing is written or built until a human authorizes that exact proposal |
| Parallel nodes propose full files from different base revisions | Per-attempt snapshots; touched-file check against accepted work | Stale proposal rejected and retried on current files |
| Upstream input changes while a node is running | Generation counter and input-hash check under the publish lock | Work discarded, not published; node re-runs |
| Verification repairs code after it was reviewed | Evidence order (review after verify), stage scopes, `evidence-freshness` at final validation | Stale evidence fails final validation; release is bound to the validated tree |
| Exception mid-apply, at commit, while approving or publishing | Every path restores the accepted checkpoint | Attempt retried; a failed restore quarantines the workspace and stops the run |
| Reviewer unreachable, terminal closed, scripted decision missing or not covering a reason | `ReviewerUnavailableException` | Rollback; run pauses; resumable with a reviewer |
| Reviewer amendment is malformed or names an unknown artifact | Contract validation and policy on amendments | Refused and re-asked (bounded), then paused |
| Audit log cannot be written | Fail-stop log | Run stops, attempt rolled back, outcome says the log is incomplete |
| A second process runs or resumes a run that is still in progress | Run directory lock | Refused with the owner's pid; prevents interleaved logs and workspace rollbacks |
| Run resumed from a tampered log or modified artifacts, or with another workflow | Hash chain, artifact hashes, identity check | Resume refused |
| Docker unavailable | Degraded build profile | Final validation records DEGRADED; release needs an explicit waiver |
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

- **Scenario evidence uses hand-authored model replies.** It proves the orchestration and the
  produced code, not model quality. Live-model status: [scenarios/live.md](scenarios/live.md).
- **Scripted reviewers are demonstrations.** They are labelled as such and fail closed, but no
  human reviewed the committed runs.
- **Execution isolation is opt-in.** By default builds run as host processes with a scrubbed
  environment only (no model keys), which is not isolation: generated tests can read the user's
  files and use the network. `--sandbox docker` isolates builds and tests (no network, limits,
  only the workspace mounted, PostgreSQL sidecar instead of the Docker socket); it needs a
  one-time networked cache warm-up on the trusted baseline, and a build that needs a dependency
  not in that cache fails rather than reaching the network.
- **Single process; no fsync.** Resume is tested at the persistence boundaries of an acceptance,
  but the log is not fsynced, so an OS crash can lose the last events. No failover.
- **Re-planned code nodes write over the current tree.** Files an earlier execution created and a
  later one does not are left in place (they still pass every gate).
- **Structural, not semantic, API compatibility.** The gate checks operations, success statuses
  and required properties; types, formats and behaviour are covered only by the service's
  contract conformance test.
- **The security scanner is pattern-based**, not SAST/SCA; the model review is advisory.
- **Positional replay can drift** when the workflow changes; drift is labelled.
- **Service scope:** click analytics are best effort ([ADR-0017](adr/0017-best-effort-click-analytics.md));
  the management and statistics API is unauthenticated (production design: [ADR-0018](adr/0018-token-authentication-and-link-ownership.md)); cache, deletion visibility and rate limits
  are per instance (see the final service README, "Scale and reliability boundaries"). The 2,000
  redirects/s target is measured once on a laptop, not verified for production.

## Assumptions

- A human reviewer is available at checkpoints for interactive runs; unattended runs must select
  the scenario's scripted demonstration decisions explicitly, and pause on anything they do not
  cover.
- The target is a Java/Maven project; the gates and the codebase indexer are Java-specific, while
  the engine is not.
- Model credentials are provided via environment or `.env`; they are never logged, stored in
  cassettes, or written by agents.
