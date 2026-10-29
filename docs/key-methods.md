# Key methods

The methods that carry the design, one line each, grouped by module and class. Start with
`WorkflowEngine.schedule`, `NodeRunner.attempt` and `NodeRunner.gateApproveAndAccept`: together
they are the orchestration and governance model.

## orchestrator-core

### Graph (`core/graph`)

| Method | What it does |
|---|---|
| `WorkflowGraph.of` | Builds a graph from node specs and rejects duplicates, unknown dependencies and cycles. |
| `WorkflowGraph.insertAfter` | Inserts a node's run-time expansion between that node and its dependents, rewiring joins to wait for every new node. |
| `WorkflowGraph.removeSpawnedBy` | Removes every node an expansion created (transitively) and unhooks them, so a re-planned planner can re-expand cleanly. |
| `WorkflowGraph.transitiveDependentsOf` | Returns everything downstream of a node: the closure that re-planning invalidates. |
| `WorkflowGraph.topologicalOrder` | Kahn's algorithm with insertion-order tie-break, giving deterministic scheduling order. |
| `WorkflowGraph.toMermaid` | Renders the graph as a Mermaid flowchart, with a CSS class per node for the run report. |
| `NodeSpec.builder` | Fluent definition of a node: dependencies, task, fallback, retry, entry/exit gates, approval mode, expansion, timeout. |
| `NodeSpec.withDependsOn` | Returns a copy of the node with new dependencies (used when expansion rewires the graph). |
| `RetryPolicy.backoffAfter` | Exponential backoff delay after a failed attempt, capped at the policy's maximum. |

### Engine (`core/engine`)

| Method | What it does |
|---|---|
| `WorkflowEngine.run` | Starts a run: logs `RunStarted` with settings and the requirement hash, then schedules the graph. |
| `WorkflowEngine.resume` | Rebuilds accepted nodes, artifacts, expansions and execution counters from the event log, resets the workspace to the last checkpoint, and continues. |
| `WorkflowEngine.schedule` | The scheduler loop: enforces stop conditions, skips blocked nodes, dispatches ready nodes in parallel on virtual threads, and processes outcomes. |
| `WorkflowEngine.onOutcome` | Applies a node's result on the scheduler thread: success (expand, then re-plan), failure (count, failure threshold) or stop (back to pending). |
| `WorkflowEngine.expand` | Replaces a node's earlier expansion with a fresh one built from the stored artifacts, and logs `GraphExpanded`. |
| `WorkflowEngine.replanStaleNodes` | Finds accepted nodes whose recorded input hashes no longer match the current artifacts and invalidates them. |
| `WorkflowEngine.invalidate` | Resets a node and its downstream closure to pending (or marks running ones stale) and logs `NodeInvalidated` with the cause. |
| `WorkflowEngine.skipBlocked` | Marks pending nodes whose dependencies failed or were skipped as `SKIPPED`, even after a safe stop. |
| `WorkflowEngine.readyNodes` | Lists pending nodes whose dependencies have all succeeded: the join semantics. |
| `WorkflowEngine.enforceStopConditions` | Engages a safe stop on the kill switch or an exhausted wall-clock, attempt or model-call budget; also called before every attempt. |
| `WorkflowEngine.finish` | Computes the final status (succeeded, failed or paused), logs `RunFinished` and returns the outcome. |
| `NodeRunner.run` | Runs one node through its governed lifecycle with the next execution number; any engine error becomes a logged node failure. |
| `NodeRunner.runGoverned` | Entry gates, then bounded attempts of the primary strategy, then the fallback, then fail; feeds each rejection forward as feedback. |
| `NodeRunner.attempt` | One attempt: run the task with a timeout, evaluate policy on the proposal, then lock and gate if the node changes files or its gates read the workspace. |
| `NodeRunner.gateApproveAndAccept` | Applies changes, runs exit gates (rolling back on failure), asks for approval if required (rolling back on rejection), commits a checkpoint. |
| `NodeRunner.accept` | Publishes outputs and reviewer amendments as artifact versions, records decisions with lineage, logs `NodeSucceeded` with consumed input hashes. |
| `NodeRunner.approvalReasons` | Collects why a human must decide: mandatory sign-off and/or policy `REQUIRE_APPROVAL` findings. |
| `NodeRunner.rollback` | Resets the workspace to the attempt's starting checkpoint and logs `RollbackPerformed`. |
| `NodeRunner.executeWithTimeout` | Runs a task on a virtual thread and cancels it if it exceeds the node's per-attempt timeout. |
| `NodeRunner.restoreExecutions` | Seeds per-node execution counters on resume so replay keys stay unique and stable. |
| `NodeContext.input` | Reads a required artifact and records exactly which version the node consumed (lineage and re-planning). |
| `NodeContext.recordAgentCall` | Logs a model call (agent, model, mode, latency, tokens) and counts it against the model-call budget. |
| `StopController.requestStop` | Engages the safe stop; the first reason wins and is recorded. |
| `EngineConfig.describe` | Engine limits as recorded in the audit log. |

### Artifacts, events and metrics (`core/artifact`, `core/event`, `core/metrics`)

| Method | What it does |
|---|---|
| `ArtifactStore.put` | Stores a new content-addressed version (SHA-256), persists it to disk, and skips identical content so re-runs don't cascade. |
| `ArtifactStore.restore` | Re-registers a version recovered from the event log during resume. |
| `ArtifactStore.latest` | Returns the newest version of an artifact key, if any. |
| `EventLog.append` | Appends an event with a sequence number and `hash = SHA-256(prev, seq, runId, time, event)`, writes it to JSONL, notifies subscribers. |
| `EventLog.verifyChain` | Recomputes the hash chain and returns the first tampered or missing event. |
| `EventLog.readFile` | Loads a persisted log as typed events (reports, metrics, resume). |
| `EventLog.subscribe` | Registers an observer (progress printer); observer failures can never break execution. |
| `MetricsCalculator.compute` | Folds a run's events into success rate, first-pass yield, retries, rollbacks, fallbacks, MTTR, latency and cost. |
| `MetricsCalculator.aggregate` | Run success rate, retry/rollback rates and mean MTTR/latency across runs. |
| `RunMetrics.machineMs` | End-to-end latency minus time spent waiting for humans. |

### Governance (`core/policy`, `core/gate`, `core/approval`)

| Method | What it does |
|---|---|
| `PolicyEngine.evaluate` | Runs every rule against a proposal before anything is written; a rule that throws becomes a BLOCK (fail closed). |
| `PolicyEngine.blocks` / `requiresApproval` | Whether any finding blocks the attempt / forces a human checkpoint. |
| `PolicyInput.effectiveChanges` | The proposal minus no-op writes, so rewriting a file unchanged is not reported as a change. |
| `WorkspaceScopeRule.evaluate` | Blocks writes outside the allow-list, to protected governance files (CI, Checkstyle, policy) and path traversal. |
| `SecretLeakRule.evaluate` | Blocks keys, tokens, private keys and hard-coded passwords in files and artifacts. |
| `SchemaMigrationRule.evaluate` | Blocks edits to applied migrations; routes new migrations to a human. |
| `SensitiveFileRule.evaluate` | Requires approval for build-file (dependencies), API-contract and security-config changes. |
| `ChangeSizeRule.evaluate` | Requires approval when a change touches more lines than the limit. |
| `CommandGate.evaluate` | Runs a tool (e.g. `mvn verify`) in the workspace with a timeout; exit code decides, the output tail is the evidence. |
| `RequiredArtifactsGate.evaluate` | Checks that required artifacts exist and are non-blank (stage contracts). |
| `GateContext.output` | An artifact from the current attempt, falling back to the accepted store. |
| `ScriptedApprovalGateway.decide` | Replays pre-recorded reviewer decisions per node for unattended runs, recording every request. |
| `ApprovalDecision.approveWithAmendments` | An approval that also replaces artifacts, which triggers re-planning of their consumers. |

### Workspace (`core/workspace`)

| Method | What it does |
|---|---|
| `GitWorkspace.createFrom` | Copies the baseline (without build output) into a private git repo, commits and tags `baseline`. |
| `GitWorkspace.open` | Reopens an existing run workspace for resume. |
| `GitWorkspace.apply` | Writes proposed files; refuses paths that escape the workspace or touch `.git`. |
| `GitWorkspace.checkpoint` | Commits the current state as an accepted checkpoint and returns its id. |
| `GitWorkspace.rollbackTo` | `reset --hard` + `clean -fd` back to a checkpoint, including untracked files. |
| `GitWorkspace.diff` / `changedFiles` | The patch / changed paths against a checkpoint (shown to reviewers). |

## orchestrator-agents

### Model access (`agents/llm`, `agents/AgentSupport`)

| Method | What it does |
|---|---|
| `AgentSupport.ask` | Builds the replay key, appends retry feedback to the prompt, calls the model, records telemetry, parses the reply into the contract. |
| `AgentSupport.parse` | Parses a reply into a contract record (tolerating Markdown fences); a mismatch fails the attempt with the error as feedback. |
| `AgentSupport.feedbackSection` | Formats earlier rejections (gate output, policy findings, reviewer comments) into a bounded "fix these" prompt section. |
| `LangChain4jLlmClient.complete` | Sends system and user messages in JSON mode via LangChain4j (provider schema optional, off by default) and maps token usage. |
| `JsonShape.of` | Renders a contract record as a JSON skeleton (fields, types, nested lists) for the prompt. |
| `CassetteLlmClient.complete` | Live, record or replay dispatch keyed by `node/e<execution>/attempt-<n>/<strategy>/<agent>`. |
| `CassetteLlmClient.replay` | Serves a stored reply; labels it `replay`, `replay-drift` or `fixture`; can raise a simulated outage. |
| `ModelSettings.fromEnvironment` | Reads key and model names from the environment, treating `<placeholders>` as unset. |
| `ModelSettings.requireLiveReady` | Fails fast, naming the missing variables, before any live call. |
| `ModelSettings.client` | Builds a Gemini `ChatModel` (temperature 0.2, timeout, transport retries) behind the `LlmClient` seam. |

### Analysis (`agents/analysis`)

| Method | What it does |
|---|---|
| `CodebaseIndexer.index` | Deterministically maps a Java project with JavaParser: types, roles, constructor dependencies, endpoints, migrations, tests. |
| `CodebaseIndexer.resolve` | Resolves mapping paths given as literals, same-type constants or concatenations (e.g. `@RequestMapping(BASE_PATH)`). |
| `CodebaseIndexer.roleOf` | Classifies a type as controller, service, repository, config, web, filter, component or domain. |
| `CodebaseIndex.contains` | Whether a path really exists: the basis of the hallucination guard. |
| `CodebaseIndex.render` | Compact codebase map for prompts. |
| `SourceContext.render` | File tree plus file contents under a character budget, most relevant files first. |
| `Git.changedSinceBaseline` / `diffSinceBaseline` | What the run has changed: drives tests, docs, security review and the release patch. |

### Agents (`agents/*`, each a `NodeTask.execute`)

| Method | What it does |
|---|---|
| `RequirementsAgent.execute` | Normalises the request into a spec: FRs with acceptance criteria, measurable NFRs, explicit questions with defaults, assumptions, risks. |
| `ClarificationTask.execute` | Deterministically renders the open questions and a pre-filled answer sheet for the stakeholder checkpoint. |
| `RefineRequirementsAgent.execute` | Folds the stakeholder's answers into a final spec with no open questions. |
| `CodebaseScanTask.execute` | Publishes the JavaParser codebase index (runs in parallel with requirements). |
| `ImpactAnalysisAgent.execute` | Identifies affected files, APIs, data and flows, grounded in the index and sources. |
| `ArchitectAgent.execute` | Produces the design, decisions and the full OpenAPI contract, and writes the contract to `api/openapi.yaml`. |
| `ArchitectAgent.render` | Renders the design spec as reviewable Markdown (components, data model, ADR-style decisions, risks). |
| `PlannerAgent.execute` | Decomposes the design into at most N tasks with files, requirement ids and dependencies. |
| `CoderAgent.execute` | Implements one planned task as complete files against the design, contract, conventions and current code. |
| `IntegrationTask.execute` | Join after all tasks: records the change set since the baseline. |
| `TestAuthorAgent.execute` | Writes unit and Testcontainers integration tests for the changed behaviour (only under `src/test`). |
| `DocsAgent.execute` | Updates README and changelog for the change. |
| `TemplateDocsWriter.execute` | Deterministic docs fallback: regenerates the README API reference from the contract (idempotent). |
| `SecurityReviewAgent.execute` | Combines authoritative scanner findings with an advisory model review of the diff. |
| `SecurityReviewAgent.scan` | Pattern scanner over changed main sources (SQL concatenation, process execution, wildcard CORS, weak randomness, deserialization). |
| `VerifyAndRepairAgent.execute` | First attempt proposes nothing so the full build runs; later attempts ask the model for the smallest gated fix. |
| `ReleaseAgent.execute` | Produces the patch, the traceability matrix and release notes with risks and a rollback plan. |
| `ReleaseAgent.traceability` | Requirement → task → file matrix from the plan; flags requirements no task implements. |

### Agent gates (`agents/gates`)

| Method | What it does |
|---|---|
| `ContractGate.evaluate` | Parses an artifact into its contract and runs semantic validators; problems become feedback. |
| `Validators.requirements` | FR ids unique with acceptance criteria, NFRs measurable, and (after clarification) no open questions. |
| `Validators.plan` | 1..N tasks, kebab ids, files in scope, acyclic resolvable dependencies (checked with the engine's own graph validation). |
| `Validators.security` | Only scanner HIGH findings block; model findings stay advisory. |
| `ImpactGroundingGate.evaluate` | Rejects MODIFY/DELETE of files not in the index and ADD of files that already exist. |
| `ApiCompatibilityGate.evaluate` | The contract must be valid OpenAPI 3 and keep every operation of the baseline contract. |
| `DocsCoverageGate.evaluate` | The README must document every path in the contract. |
| `OpenApiDocument.parse` | Minimal OpenAPI reader: version and operations. |
| `CodeProposal.toChanges` | Converts a model's proposed files into engine file changes. |

## orchestrator-app

| Method | What it does |
|---|---|
| `AgenticCli.main` | Dispatches `run`, `resume`, `stop`, `report`, `verify-log`, `metrics` and `list`. |
| `AgenticCli.run` | Wires a run: scenario, model mode, workspace, event log, policy, approvals; Ctrl-C triggers a safe stop; writes report and patch. |
| `AgenticCli.llm` | Builds the cassette-wrapped model client for live, record or replay mode. |
| `AgenticCli.approvals` | Interactive console reviewer, or a scripted reviewer built from the scenario file. |
| `AgenticCli.stop` | Engages a run's kill switch by creating its `STOP` file. |
| `AgenticCli.verifyLog` | Verifies a run's hash chain and reports the first broken event. |
| `AgenticCli.metrics` | Prints per-run reliability metrics and the cross-run aggregate. |
| `AgenticCli.dockerAvailable` | Detects Docker so `verify` can record a degraded profile instead of failing silently. |
| `WorkflowTemplates.build` | Builds the SDLC graph for a scenario: brownfield adds codebase analysis, ambiguous adds clarification. |
| `WorkflowTemplates.implementationTasks` | The plan's expansion: one gated coder node per planned task, with fallback model and mapped dependencies. |
| `BuildCommands.fullVerify` | Full `mvn verify`, or the unit-only profile when Docker is unavailable. |
| `GovernancePolicy.standard` | The change-control policy every run is governed by (scope, secrets, migrations, dependencies, contract, size). |
| `ConsoleApprovalGateway.decide` | Terminal checkpoint: shows reasons, evidence and diff; approve, reject with feedback, or edit artifacts. |
| `ConsoleApprovalGateway.edit` | Writes an artifact to a file for the reviewer to edit and stages it as an amendment. |
| `HtmlReport.render` | Self-contained run report: KPIs, coloured graph, checkpoints, lineage, gate/policy/recovery evidence, artifacts, audit log. |
| `HtmlReport.nodeClass` | Colours a node by final state, or "recovered" if it needed retries or a fallback. |
| `ProgressPrinter.accept` | Narrates each event as a live, human-readable log line. |
| `Scenario.load` | Reads a scenario: requirement, baseline, default approval mode, scripted reviewer decisions. |

## url-shortener

| Method | What it does |
|---|---|
| `LinkService.create` | Validates URL, expiry and alias, then inserts with a custom alias or a generated code. |
| `LinkService.insertWithGeneratedCode` | Generates a code and inserts with `ON CONFLICT DO NOTHING`; retries up to 5 attempts, then fails. |
| `LinkService.get` | Returns an active link's metadata (404 if missing or deleted). |
| `LinkService.delete` | Soft-deletes a link (its code is never reused) and evicts it from the redirect cache. |
| `LinkResolver.resolve` | Redirect hot path: cache lookup, loading from the database on a miss. |
| `LinkResolver.load` | Cache loader: 404 for unknown codes, 410 for expired links. |
| `LinkResolver.evict` | Removes a code from the redirect cache. |
| `LinkRepository.insertIfAbsent` | `INSERT … ON CONFLICT (code) DO NOTHING RETURNING`: atomic uniqueness without exception-driven retries. |
| `LinkRepository.findActiveByCode` | Looks up a link that is not soft-deleted. |
| `LinkRepository.softDelete` | Sets `deleted_at`; reports whether a row was affected. |
| `RandomBase62CodeGenerator.generate` | 7-character code from `SecureRandom` over `[0-9A-Za-z]` (non-enumerable). |
| `TargetUrlPolicy.validate` | Allows only http/https with a host, no userinfo, not our own host, at most 2048 characters. |
| `AliasPolicy.validate` | Alias format `[A-Za-z0-9_-]{4,32}` and not a reserved word. |
| `RedirectController.redirect` | `GET /{code}`: 302 with `Location` and `Cache-Control: no-store`. |
| `LinkApiController.create` / `get` / `delete` | The link API: 201 with `Location`, 200 metadata, 204 delete. |
| `RateLimiter.tryAcquire` | Per-client token bucket in a bounded Caffeine cache; returns allow or retry-after. |
| `TokenBucket.tryConsume` | Integer-arithmetic refill and consume, driven by the injected clock. |
| `RateLimitInterceptor.preHandle` | Applies the limiter to `POST /api/v1/links`; throws to produce 429 with `Retry-After`. |
| `ClientKeyResolver.resolve` | Client key from the remote address, or the first `X-Forwarded-For` hop only when trusted. |
| `ProblemDetailsAdvice.handle*` | Maps domain errors to RFC 9457 problem responses (400, 404, 409, 410, 429, 500); hides internals. |

## Tools

| Item | What it does |
|---|---|
| `tools/make-drill-cassettes.py` | Generates the governance drill scenario and its hand-authored model fixtures with planted faults. |
| `./agentic` | Loads `.env` as data, builds the CLI jar if needed, and runs it. |
