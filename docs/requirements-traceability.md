# Requirements traceability

Every requirement from [requirements.md](requirements.md), the mechanism that satisfies it, where
it lives, and the evidence that it works. Test names are JUnit methods; "drill" refers to the
deterministic [governance drill](scenarios/drill.md) run.

## Orchestration (R4)

| ID | Mechanism | Code | Verified by |
|---|---|---|---|
| R4.1 Dependency graph with entry/exit gates | Validated DAG of `NodeSpec`s; entry gates once per node, exit gates every attempt | `core/graph/WorkflowGraph`, `core/engine/NodeRunner`, `core/gate/*`, `agents/gates/*` | `rejectsCyclesUnknownDependenciesAndDuplicates`, `failsANodeWhoseEntryGateIsNotSatisfiedWithoutAttemptingIt`, `rollsBackTheWorkspaceWhenAnExitGateRejectsAnAttempt`; drill: 20 gate evaluations (2 failed, both recovered) |
| R4.2 Sequential and parallel paths with synchronization | Ready nodes run concurrently on virtual threads (bounded); multi-dependency nodes are joins; workspace lock serialises apply-gate-commit | `core/engine/WorkflowEngine` | `runsIndependentNodesInParallelAndJoinsOnBoth`, `brownfieldAnalysesTheCodebaseInParallelWithRequirementsAndJoinsBeforeImpact`; drill: two implementation tasks and three verification branches in parallel |
| R4.3 Cross-stage context and decision lineage | Versioned, content-addressed artifacts; consumption recorded per node; decisions carry the artifact versions they were based on | `core/artifact/ArtifactStore`, `core/engine/NodeContext`, `RunEvent.DecisionRecorded` | `replansConsumersWhenAReviewerAmendsAnUpstreamArtifact` (asserts `basedOn = requirements@v2`), `requirementsAgentPublishesAGatedSpecAndRecordsAssumptionsAsDecisions`; report "Decisions and lineage" |
| R4.4 Human approval for high-impact actions | `ApprovalMode.ALWAYS` (clarify, design, release) plus policy `REQUIRE_APPROVAL` (migrations, dependencies, API contract, security config, size); approve / reject-with-feedback / amend | `core/approval/*`, `app/ConsoleApprovalGateway`, `app/GovernancePolicy` | `routesHighImpactChangesToAHumanAndFeedsRejectionsBack`, `humanCheckpointsAreMandatoryAtDesignAndRelease`, `consoleReviewerCanAmendAnArtifactBeforeApproving`; drill: 4 checkpoints incl. a schema-change approval |
| R4.5 Bounded retries, fallback, rollback, safe stop | `RetryPolicy` with feedback and backoff; fallback strategy; git checkpoint rollback; kill switch, budgets, failure threshold, Ctrl-C; per-attempt timeouts; resume | `core/graph/RetryPolicy`, `core/engine/NodeRunner`, `core/engine/StopController`, `core/workspace/GitWorkspace` | `retriesWithFailureEvidenceUntilTheTaskSucceeds`, `switchesToTheFallbackStrategyWhenThePrimaryIsExhausted`, `rollsBackTheWorkspaceWhenAnExitGateRejectsAnAttempt`, `killSwitchPausesTheRunAndResumeFinishesWithoutRepeatingWork`, `attemptBudgetStopsARunawayRetryLoop`, `skipsDependentsAndSafeStopsWhenANodeFails`, `timesOutAHungAttemptAndRetries`; drill: 5 retries, 1 rollback, 1 fallback |
| R4.6 Policy guardrails | Proposal-time rules: workspace scope with protected governance files, secret leak, immutable migrations, sensitive files, change size; fail-closed | `core/policy/*`, `app/GovernancePolicy` | `workspaceScopeAllowsOnlyListedPathsAndProtectsGovernanceFiles`, `secretLeakRuleScansFilesAndArtifacts`, `newMigrationsNeedApprovalAndAppliedMigrationsAreImmutable`, `aBrokenRuleFailsClosed`, `blocksGuardrailViolationsBeforeAnythingIsWritten`; drill: edit to applied V1 blocked |
| R4.7 Audit-grade observability and traceability | Append-only hash-chained event log (21 event types); `verify-log`; HTML report; live progress | `core/event/*`, `app/HtmlReport`, `app/ProgressPrinter` | `persistsTypedEventsAsAHashChainedJsonLinesFile`, `detectsTamperingWithAnyEarlierEvent`, `reportShowsMetricsCheckpointsLineageAndEvidenceFromTheLog`; drill: 129 events, chain verified |
| R4.8 Reliability metrics | Projection of the log: node success rate, first-pass yield, retry and rollback rates, fallbacks, MTTR, end-to-end / machine / human time, model calls and tokens; cross-run aggregation | `core/metrics/*`, `agentic metrics` | `derivesReliabilityMetricsFromTheEventLog`, `aggregatesAcrossRuns`; drill: retry rate 0.24, rollback rate 0.05, MTTR 3.1 s |
| R4.9 Dynamic re-planning | Run-time graph expansion from the plan; invalidation of nodes whose consumed input hashes changed, plus their downstream closure | `core/engine/WorkflowEngine` (`expand`, `replanStaleNodes`), `core/graph/WorkflowGraph.insertAfter` | `expandsThePlanIntoTasksAndTheJoinWaitsForAllOfThem`, `replansConsumersWhenAReviewerAmendsAnUpstreamArtifact`, `removesSpawnedNodesAndUnhooksThemWhenParentIsReplanned`; drill: reviewer amendment re-ran exactly `impact-analysis` and `design` |
| R4 SDLC coverage | Stages: requirements, clarification, codebase analysis, design, planning, implementation, testing, documentation, security review, verification, release readiness | `app/WorkflowTemplates` | `WorkflowTemplatesTest` (all three scenario shapes) |

## Other core requirements

| ID | Mechanism | Code | Verified by |
|---|---|---|---|
| R1 Requirement understanding | Requirements agent produces FRs with acceptance criteria, measurable NFRs, explicit ambiguities with defaults, assumptions, scope, risks; quality gate | `agents/RequirementsAgent`, `Validators.requirements` | `requirementGateDemandsTestableIdentifiedMeasurableRequirements`; ambiguous scenario |
| R1 Ambiguity handling | Clarification checkpoint: questions with recommended defaults; stakeholder answers by amendment; refined spec must have no open questions | `agents/ClarificationTask`, `RefineRequirementsAgent` | `clarificationAndRefinementTurnQuestionsIntoAResolvedSpec`, `ambiguousRequestsAreClarifiedByAHumanBeforeDesign` |
| R2 Task decomposition | Planner emits tasks with requirement ids, files, dependencies, acceptance criteria; plan-validity gate; graph expansion | `agents/PlannerAgent`, `Validators.plan`, `WorkflowTemplates.implementationTasks` | `planGateRejectsCyclesUnknownDependenciesAndOutOfScopeFiles`, `plannerAndCoderTurnTheDesignIntoCommittedCode` |
| R3 Codebase reasoning | Deterministic JavaParser index (types, roles, wiring, endpoints, migrations, tests) runs in parallel with requirements; impact analysis grounded against it | `agents/analysis/CodebaseIndexer`, `ImpactAnalysisAgent`, `ImpactGroundingGate` | `indexesTheRealBaselineServiceDeterministically`, `impactGroundingRejectsHallucinatedFiles`; drill: hallucinated `LinkCache.java` rejected |
| R5 Engineering output | Code, OpenAPI contract, Flyway migrations, unit + integration tests, README/changelog, release notes, patch | agents + `url-shortener` | `url-shortener`: 124 unit + 16 Testcontainers ITs, 98.9% line coverage; drill produces a patch that passes the full build |
| R6 Validation and risk control | Gates per stage, scanner-backed security review, requirement/design risk sections, release risks and rollback plan | `agents/gates/*`, `SecurityReviewAgent`, `ReleaseAgent` | `onlyScannerHighFindingsBlockSecurity`, `apiCompatibilityAllowsAdditionsAndRejectsRemovals`; [risks-and-limitations.md](risks-and-limitations.md) |
| R7 Controlled autonomy | Agents propose, engine applies; write allow-list; protected guardrail files; budgets; mandatory release sign-off | `core/engine/NodeRunner`, `WorkspaceScopeRule`, `EngineConfig` | `blocksGuardrailViolationsBeforeAnythingIsWritten`, `refusesPathsThatEscapeTheWorkspaceOrTouchGitInternals` |
| R8 Final summary | | [FINAL_SUMMARY.md](../FINAL_SUMMARY.md) | |

## Evaluation criteria

| Criterion | Where to look |
|---|---|
| Effectiveness of agentic orchestration | [architecture §3](architecture.md#3-orchestration-model), [drill](scenarios/drill.md) |
| Architecture and system design quality | [architecture.md](architecture.md), [ADRs](adr) |
| Depth of decomposition and execution | [scenarios](scenarios), run reports |
| Realism and quality of outputs | [`url-shortener`](../url-shortener), `release.patch` of each run |
| Validation and risk management rigor | gates table ([architecture §5.3](architecture.md#53-gates)), [risks](risks-and-limitations.md) |
| Clarity and defensibility of decisions | [ADRs](adr) |
| Modular, testable, reliable, secure, scalable code with safe change management | module boundaries, [testing.md](testing.md), policy rules, Checkstyle and coverage gates on every module |
| Engineering judgment | [ai-collaboration-log.md](ai-collaboration-log.md), [ADRs](adr) |
