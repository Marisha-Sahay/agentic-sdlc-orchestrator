# Architecture

## 1. Goals

The system takes a requirement (well-defined or vague, for new or existing code) and produces a
reviewable engineering outcome: a patch that compiles, passes tests and quality gates, with
documentation, a traceability matrix, release notes and an audit trail. Three properties drive the
design:

1. **Controlled autonomy.** Agents do multi-step work inside explicit boundaries; the engine, not
   the agent, decides what is accepted; humans approve high-impact changes.
2. **Evidence over assertion.** Every acceptance decision is made by deterministic code (compiler,
   tests, schema and policy checks), recorded with its evidence, and reproducible.
3. **Recoverability.** Any failure is bounded (retries, fallback, budgets), reversible (rollback to
   the last green checkpoint) and resumable (from the event log).

## 2. Components

The same structure at C4 levels 1–3 plus dynamic views is in [c4-model.md](c4-model.md).

```mermaid
flowchart TB
  subgraph APP["orchestrator-app (CLI)"]
    SC[Scenario YAML] --> WT[WorkflowTemplates]
    CA[ConsoleApprovalGateway<br/>or scripted reviewer]
    PR[ProgressPrinter]
    HR[HtmlReport / metrics]
    GP[GovernancePolicy]
  end
  subgraph CORE["orchestrator-core (pure Java 21, no Spring, no LLM)"]
    WG[WorkflowGraph] --> WE[WorkflowEngine<br/>scheduler]
    WE --> NR[NodeRunner<br/>node lifecycle]
    NR --> PE[PolicyEngine + rules]
    NR --> GA[Gates]
    NR --> AG[ApprovalGateway]
    NR --> GW[GitWorkspace<br/>checkpoints]
    NR --> AS[(ArtifactStore<br/>versioned, hashed)]
    WE --> EL[(EventLog<br/>hash-chained JSONL)]
    NR --> EL
    EL --> MC[MetricsCalculator]
  end
  subgraph AGT["orchestrator-agents (LangChain4j)"]
    AGS[SDLC agents] --> SUP[AgentSupport] --> LC[LlmClient]
    LC --> CAS[CassetteLlmClient<br/>live / record / replay] --> L4J[LangChain4jLlmClient<br/>Gemini]
    IDX[CodebaseIndexer<br/>JavaParser]
    AGG[Contract, grounding,<br/>API-compat, docs gates]
  end
  WT --> WG
  NR -. executes NodeTask .-> AGS
  GW --> WS[(run workspace:<br/>private git repo)]
  EL --> PR
  EL --> HR
```

| Module | Responsibility | Key types |
|---|---|---|
| `orchestrator-core` | Execute a graph of governed nodes; record everything | `WorkflowGraph`, `NodeSpec`, `WorkflowEngine`, `NodeRunner`, `Gate`, `PolicyEngine`, `ApprovalGateway`, `GitWorkspace`, `ArtifactStore`, `EventLog`, `MetricsCalculator` |
| `orchestrator-agents` | Do SDLC work with a model; analyse code deterministically | `RequirementsAgent`, `ImpactAnalysisAgent`, `ArchitectAgent`, `PlannerAgent`, `CoderAgent`, `TestAuthorAgent`, `DocsAgent`, `SecurityReviewAgent`, `VerifyAndRepairAgent`, `ReleaseAgent`, `CodebaseIndexer`, `CassetteLlmClient` |
| `orchestrator-app` | Compose workflows per scenario; human interface; reporting | `AgenticCli`, `WorkflowTemplates`, `GovernancePolicy`, `ConsoleApprovalGateway`, `HtmlReport` |
| `url-shortener` | The target service (v1 baseline) | see its [README](../url-shortener/README.md) |

The dependency direction is strict: `app → agents → core`. The core knows nothing about models,
Spring or URL shorteners, which is what makes its behaviour testable with plain lambdas.

## 3. Orchestration model

### 3.1 The graph

A workflow is a `WorkflowGraph` of immutable `NodeSpec`s. A node declares:

| Field | Meaning |
|---|---|
| `dependsOn` | Nodes that must succeed first. A node with several dependencies is a join. |
| `task` / `fallback` | Primary and fallback strategies (`NodeTask`: context → proposal) |
| `retry` | Bounded attempts per strategy with exponential backoff |
| `entryGates` / `exitGates` | Preconditions checked once; checks every attempt must pass |
| `approval` | `ALWAYS` (mandatory sign-off) or `POLICY` (when policy flags the change) |
| `expansion` | Optional: turns accepted outputs into new nodes (the planner's tasks) |
| `timeout` | Wall-clock limit for one attempt |

Every mutation (creation, expansion, removal) is validated: unknown dependencies, duplicates and
cycles are rejected (Kahn's algorithm), so the graph is a DAG at all times.

### 3.2 Node lifecycle

```mermaid
stateDiagram-v2
  [*] --> PENDING
  PENDING --> RUNNING: all dependencies SUCCEEDED
  PENDING --> SKIPPED: a dependency FAILED or was SKIPPED
  RUNNING --> SUCCEEDED: gates pass (and approval, if required)
  RUNNING --> FAILED: entry gate fails, or all attempts of all strategies rejected
  RUNNING --> PENDING: safe stop before the next attempt
  SUCCEEDED --> PENDING: an input artifact changed (re-plan)
  FAILED --> PENDING: resume
```

### 3.3 One attempt

```mermaid
sequenceDiagram
  autonumber
  participant S as Scheduler
  participant R as NodeRunner
  participant A as Agent
  participant P as PolicyEngine
  participant H as Reviewer
  participant W as GitWorkspace
  participant G as Exit gates
  S->>R: run(node) on a virtual thread
  R->>R: entry gates (once)
  loop each attempt (primary strategy, then fallback)
    R->>A: execute(context: inputs, feedback, read-only snapshot of base revision)
    A-->>R: proposal: artifacts, file changes, decisions
    R->>P: evaluate proposal against the base revision (nothing written yet)
    alt BLOCK finding
      R-->>R: reject; feedback = findings
    else execution-sensitive change (build file)
      R->>H: AUTHORIZATION of proposal hash, with a preview diff
      H-->>R: authorize / refuse (refuse: reject, nothing was written)
    end
    R->>W: lock; touched files changed since base? reject as stale
    R->>W: apply; tree hash T
    R->>G: compile / test / contract / grounding checks on T
    alt a gate fails
      R->>W: rollback to last checkpoint; reject; feedback = gate evidence
    else gates pass
      opt ALWAYS, policy REQUIRE_APPROVAL, or task-declared reason
        R->>H: ACCEPTANCE of proposal hash on tree T (diff, gate evidence, reasons)
        H-->>R: approve / reject with comment / approve with validated amendments
      end
      R->>W: tree still T? inputs still current? then checkpoint commit; unlock
      R-->>S: SUCCEEDED (success event = commit point) + consumed hashes + changed keys
    end
  end
  S->>S: expand graph, invalidate stale consumers, schedule next
```

Every path out of the locked section either commits an accepted checkpoint or restores the last
one: a gate failure, a reviewer rejection, an unreachable reviewer (the run pauses), an
exception while applying, approving, committing or publishing, or a failed audit write. If the
restore itself fails the workspace is quarantined, the failure and the rollback error are both
recorded, and the run safe-stops. The rejection reason (gate output, policy findings, reviewer
comment, parse error, stale files) becomes the next attempt's feedback, so a retry is a corrected
attempt rather than a blind repeat.

### 3.4 Scheduling, parallelism and synchronization

The scheduler loop runs on the caller's thread and owns all scheduling state. Each round it:
enforces stop conditions, marks nodes whose dependencies failed as `SKIPPED`, submits every
`PENDING` node whose dependencies all `SUCCEEDED` (bounded by `maxParallelNodes`), and processes
one completed node. Node runners execute on virtual threads, so a node blocked on a model call or
a human approval costs almost nothing.

Parallel nodes may all touch one workspace. Agent work (the slow part: model calls) runs fully in
parallel, each attempt reading its own snapshot (a git worktree of the revision it started on);
only *apply, gate, approve and commit* is serialized by a workspace lock, as are gates that read
the workspace (`Gate.usesWorkspace()`). Before applying, the engine checks that no file the
proposal touches changed since its snapshot; if one did, the proposal is rejected as stale and
retried on a fresh snapshot, so full-file proposals never overwrite accepted work. Planned tasks
that list the same file are serialized at expansion; disjoint tasks stay parallel.

### 3.5 Dynamic planning and re-planning

**Expansion.** When `plan` succeeds, its expansion parses the plan artifact and inserts one node
per task *between* `plan` and its dependents: each task depends on `plan` (and on the tasks it
names), and every existing dependent of `plan` (here `integrate`) additionally waits for all of
them. A static template ("plan, then integrate") thus accommodates a task list that only exists at
run time. Expansion is a pure function of stored artifacts, so on resume the same subgraph is
rebuilt without calling a model.

**Re-planning.** Each accepted node records the exact version (SHA-256) of every artifact it read.
When an artifact gets a new version (a reviewer amendment, or a re-run producing different output),
the scheduler invalidates every accepted node whose recorded input hash no longer matches, plus
its downstream closure, and runs them again. Nodes spawned by an invalidated planner are removed
and re-expanded. A node that is still running when it becomes stale cannot publish: under a
publish lock it sees that its generation changed or that an input it read has a newer version,
rolls back and reports `AttemptDiscarded`, and is re-queued. This is the same idea as an incremental build system (Bazel, Make): re-run what is affected,
keep what is not.

## 4. Cross-stage context and decision lineage

Stages communicate only through the `ArtifactStore`: versioned, content-addressed artifacts
(`requirements.spec`, `design.openapi`, `plan.tasks`, ...). Reading through `NodeContext.input()`
records the consumed version, so every decision an agent records (`DecisionRecorded`) carries
lineage like `requirements.spec@v2#58ea1cf39043`: which version of which input it was based on,
and who produced that version (a node, or `reviewer:<name>` for amendments).

## 5. Governance

### 5.1 Autonomy boundary

Agents return proposals; only the engine writes. The `WorkspaceScopeRule` limits writes to
`src/**`, `api/**`, docs and the build file, and **protects the guardrails themselves** (CI
workflows, Checkstyle rules, policy config); `build-integrity` does the same for the gate
configuration inside `pom.xml`. Otherwise an agent could make a failing gate pass by weakening the
gate. `stage-write-scope` narrows each stage further (documentation writes only docs, tests only
`src/test`, analysis, review and release nothing). Paths escaping the workspace are rejected twice
(policy and `GitWorkspace`).

### 5.2 Policy rules (`GovernancePolicy`)

| Rule | Severity | Why |
|---|---|---|
| `workspace-scope` | BLOCK | Agents stay inside allowed paths; governance files are immutable |
| `stage-write-scope` | BLOCK | Each stage writes only its own kind of file |
| `build-integrity` | BLOCK | Test, coverage, Checkstyle and skip settings in `pom.xml` cannot change |
| `secret-leak` | BLOCK | Keys, tokens, private keys, hard-coded passwords in code or artifacts |
| `schema-migration` (edit existing) | BLOCK | Applied migrations are immutable (Flyway checksums, environment drift) |
| `schema-migration` (new) | REQUIRE_APPROVAL | Schema changes are high impact |
| `dependency-change` (`pom.xml`) | REQUIRE_AUTHORIZATION | Supply-chain review, and the build executes it: a human authorizes the exact proposal before it is written or built |
| `api-contract-change` (`openapi.yaml`) | REQUIRE_APPROVAL | Consumer impact |
| `security-config-change` | REQUIRE_APPROVAL | Security-sensitive code |
| `change-size` (> 800 lines) | REQUIRE_APPROVAL | Large changes deserve a human look |

The engine fails closed: a rule that throws produces a BLOCK, and a gate that throws fails.
`REQUIRE_APPROVAL` cannot be disabled per node: `ApprovalMode` has no "never" option.

### 5.3 Gates

| Gate | Where | Checks |
|---|---|---|
| `requirements-quality` / `requirements-resolved` | requirements | Contract parses; FRs identified with acceptance criteria; NFRs measurable; no open questions after clarification |
| `impact-grounding` | impact-analysis | Every MODIFY/DELETE path exists in the JavaParser index; no ADD of an existing file |
| `api-compatibility` | design | Valid OpenAPI 3; structurally backward compatible with the baseline: operations, success statuses, request properties accepted and not newly required, required response properties kept (types, formats and behaviour are not checked) |
| `plan-validity` | plan | 1..8 tasks, kebab ids, acyclic resolvable dependencies, files within `src/main` / `api` |
| `maven-compile` | each implementation task | The project compiles |
| `maven-unit-tests` | tests | Unit tests pass |
| `docs-coverage` | docs | README documents every API path |
| `security-gate` | security-review | No HIGH finding from the deterministic scanner |
| `maven-verify` | verify | Unit + integration tests on PostgreSQL + Checkstyle + 80% coverage |
| `evidence-freshness`, `maven-verify`, `security-scan`, `final-api-compatibility`, `final-docs-coverage` | final-validation | Every deterministic check again, on the one tree that will be released; the security review must still describe it |
| `validated-tree` | release (entry) | The workspace is the tree final validation validated |
| `required-artifacts` | entry/exit of several nodes | Stage contracts are met |

Model judgment is advisory only: the security reviewer's findings surface at release approval but
never block, because a model false positive should not stall the pipeline and a model false
negative should not wave a scanner finding through.

### 5.4 Human checkpoints

| Checkpoint | When |
|---|---|
| `clarify` (ambiguous) | Always: the stakeholder answers the open questions by amending `clarification.answers` |
| `design` | Always: architecture, decisions and API contract |
| Any node | When policy flags the proposal (migration, dependency, contract, security config, size) |
| `release` | Always: patch, traceability, security review, release notes |

There are two kinds of decision ([ADR-0016](adr/0016-authorization-acceptance-and-tree-bound-evidence.md)):
**authorization** before anything in a proposal is written or built (execution-sensitive
changes), and **acceptance** after the gates, on the exact validated tree. Each is recorded with
the proposal hash (and the tree hash); a changed proposal is a new request.

The reviewer sees why a decision is needed, the gate evidence, the artifacts and the diff, and can
**approve**, **reject with a comment** (rolled back; the agent retries with the comment as
feedback) or **approve with amendments** to any artifact (validated against that artifact's
contract and the secret policy first; stored as `reviewer:<name>` versions, triggering
re-planning). `ConsoleApprovalGateway` is interactive. The scripted gateway replays
demonstration decisions from the scenario file, recorded as `scripted-demo (...)`; it has no
default, and each decision answers only a request of its kind whose reason codes it covers.
Anything else, a closed terminal or a failing gateway pauses the run: nothing is ever approved
by default, and a run without a terminal must select scripted decisions explicitly.

## 6. Reliability controls

| Control | Mechanism |
|---|---|
| Bounded retries | `RetryPolicy` per node (default 3 attempts, exponential backoff); failure evidence fed forward |
| Timeouts | Per-attempt wall-clock limit; the attempt is cancelled and counts as a failure |
| Fallback | Second strategy after the primary is exhausted: fallback model for design/coder/tests; deterministic `TemplateDocsWriter` for docs |
| Self-repair | `verify` runs the full build first and only asks a model for the smallest fix if it fails, still gated and rolled back |
| Rollback | Every accepted change is a git checkpoint in a private run workspace; rejected attempts reset to it |
| Safe stop | Kill switch file (`agentic stop`), Ctrl-C, run-time / attempt / model-call budgets, failure threshold: no new attempts start, running ones finish atomically, the run ends `PAUSED` |
| Resume | `agentic resume`: refuses a log whose hash chain breaks, an artifact whose content no longer matches its recorded hash, or a different scenario, requirement or workflow; restores only nodes whose success event was written (the commit point) and only their artifact versions; resets the workspace to the last accepted checkpoint, or the baseline; carries attempt and model-call budgets over unless `--reset-budgets <who>` is given, which is recorded |
| Audit fail-stop | If an event cannot be written the log refuses further writes, the attempt's changes are rolled back and the run stops; it never continues unaudited |
| Degraded verification | Without Docker, builds skip integration tests and the coverage gate; final validation records `DEGRADED` with the skipped checks, release notes say so, and release acceptance requires a decision that explicitly covers `degraded-verification` |
| Execution sandbox | Gate commands run with an allow-listed environment (no model keys). `--sandbox docker` adds a container per build: no network, CPU/memory/pids limits, only the workspace and a dedicated dependency cache mounted, integration tests against a private PostgreSQL sidecar, no Docker socket |

## 7. Observability and traceability

**Event log.** Every state change is a typed `RunEvent` (25 types: node started, gate evaluated,
policy evaluated, attempt failed, fallback, rollback, approval requested/resolved, artifact
produced, decision recorded, model called, graph expanded, node invalidated, stop requested, ...)
appended to `events.jsonl`. Each envelope carries a sequence number, timestamp and
`hash = SHA-256(prevHash, seq, runId, time, event)`, so editing or deleting any earlier line breaks
the chain; `agentic verify-log` checks it.

**Metrics** are a projection of the log (`MetricsCalculator`), so they can be recomputed for any
historical run and can never disagree with the audit trail:

| Metric | Definition |
|---|---|
| Node success rate | succeeded / (succeeded + failed), final states |
| First-pass yield | accepted nodes that passed on their first attempt / accepted nodes |
| Retry rate | attempts beyond the first / all attempts |
| Rollback rate | rollbacks / all attempts |
| MTTR | mean time from a node's first failed attempt to its eventual success |
| End-to-end latency | first to last event; split into human wait (approvals) and machine time |
| Cost | model calls, input/output tokens |
| Run success rate | across runs (`agentic metrics`) |

**Run report.** `report.html` renders the graph coloured by final state, the KPIs, every human
checkpoint, every decision with its lineage, every gate/policy/recovery event with evidence, all
artifact versions and the full audit log.

## 8. Agents

- **One seam to the model.** Agents call `AgentSupport.ask()` → `LlmClient`. The request uses
  JSON mode, and the prompt carries the exact shape of the response record (`JsonShape`). A reply
  that does not parse into the record fails the attempt with the parse error as feedback.
  Provider-side schema enforcement is off: with nested schemas it made Gemini 2.5 Flash hang
  ([ADR-0010](adr/0010-langchain4j-structured-output.md)).
- **Record/replay.** `CassetteLlmClient` stores each exchange keyed by its position in the run
  (`node/e<execution>/attempt-<n>/<strategy>/<agent>`), not by prompt hash, because prompts contain
  non-deterministic text such as build output. The prompt hash is still stored; a replay whose
  prompt differs is labelled `replay-drift`. Replay needs no network or key, which makes runs
  reproducible for reviewers and CI. Hand-authored fixtures are labelled `fixture`.
- **Grounding first.** Brownfield runs index the codebase with JavaParser (types, roles,
  constructor dependencies, endpoints including constant-based paths, migrations, tests) before
  any model reasons about impact; the grounding gate checks the model's claims against that index.
- **Context.** Code-writing agents receive the task, the approved design and contract, the
  engineering conventions ([`config/agent-skills`](../config/agent-skills/java-spring-boot.md)) and
  the workspace sources under a character budget, most relevant files first.

## 9. Run directory

```
.runs/<run-id>/
  run.json          scenario, model mode, approval mode (for resume)
  events.jsonl      hash-chained audit log: the source of truth
  artifacts/        every artifact version (key/v<n>.txt)
  workspace/        private git repo: baseline commit + one checkpoint per accepted change
  report.html       run report        metrics.json   computed metrics
  graph.mmd         final graph       release.patch  the reviewable outcome
  STOP              kill switch (created by `agentic stop`)
```

## 10. Production path

The prototype runs in one process. The seams are where production would differ:

| Concern | Prototype | Production |
|---|---|---|
| Event log | JSONL file, hash-chained | Postgres or Kafka, signed checkpoints, WORM storage |
| Scheduler | In-process | Durable workflow runtime (Temporal-style) or the same engine behind a lease per run |
| Workspace | Local git repo; builds in a host process or the opt-in Docker sandbox | Ephemeral container per run; branch + pull request on the real repository |
| Approvals | Terminal / scripted | Pull-request review or ticketing integration, SSO identities |
| Gates | Local Maven | CI pipeline jobs; SAST/SCA tools as gates |
| Telemetry | Event log + report | OpenTelemetry spans per node/attempt exported from the same events |
| Context | Whole source tree under a budget | Retrieval over a code index for large repositories |
