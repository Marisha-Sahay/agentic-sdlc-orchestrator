# Requirement-to-evidence checklist

Each requirement from [requirements.md](requirements.md) (the brief, restated), how it is met,
where, and the evidence. Status: **complete** (implemented and verified as stated), **partial**
(works with a stated gap), **not verified**. Test names are JUnit methods; evidence sets are in
[docs/evidence](evidence) and are checked by `./agentic check-evidence` in CI.

## Deliverables

| ID | Deliverable | Status | Evidence |
|---|---|---|---|
| D1 | Runnable prototype | complete | `mvn verify`; `./agentic run <scenario>`; CI replays all four scenarios |
| D2 | Architecture overview | complete | [architecture.md](architecture.md), [c4-model.md](c4-model.md), [ADRs](adr) |
| D3 | Greenfield, brownfield, ambiguous scenarios with decomposition, orchestration, validation | **partial**: all three run end to end with real gates and builds, but on hand-authored model replies; live-model runs are not part of the evidence (see [scenarios](scenarios/README.md)) | [greenfield](evidence/greenfield), [brownfield](evidence/brownfield), [ambiguous](evidence/ambiguous) |
| D4 | Setup instructions | complete | [README](../README.md#quickstart) |
| D5 | Testing approach, limitations, trade-offs | complete | [testing.md](testing.md), [risks-and-limitations.md](risks-and-limitations.md) |

## Core requirements

| ID | Mechanism | Status | Code | Verified by |
|---|---|---|---|---|
| R1 Requirement understanding, ambiguity | Requirements agent: FRs with acceptance criteria, measurable NFRs, explicit questions with defaults; clarification checkpoint; refined spec must have no open questions | complete (fixture replies) | `RequirementsAgent`, `ClarificationTask`, `RefineRequirementsAgent`, `Validators.requirements` | `clarificationAndRefinementTurnQuestionsIntoAResolvedSpec`; ambiguous evidence; brownfield raises the "every redirect" vs latency conflict as Q-1 |
| R2 Task decomposition | Planner tasks with requirement ids, files, dependencies; plan gate; run-time expansion; overlapping files serialized | complete | `PlannerAgent`, `Validators.plan`, `WorkflowTemplates.serializeOverlaps` | `planGateRejectsCyclesUnknownDependenciesAndOutOfScopeFiles`, `independentTasksThatWriteTheSameFileAreSerialisedAndOthersStayParallel`; greenfield plan (6 tasks, 2 parallel pairs) |
| R3 Codebase reasoning | JavaParser index in parallel with requirements; impact analysis grounded against it | complete | `CodebaseIndexer`, `ImpactGroundingGate` | `indexesTheRealBaselineServiceDeterministically`, `impactGroundingRejectsHallucinatedFiles`; drill fault 1 |
| R4.1 Graph with entry/exit gates | Validated DAG; entry gates once, exit gates every attempt | complete | `WorkflowGraph`, `NodeRunner`, gates | `rejectsCyclesUnknownDependenciesAndDuplicates`, `failsANodeWhoseEntryGateIsNotSatisfiedWithoutAttemptingIt` |
| R4.2 Parallel paths with synchronization | Ready nodes run concurrently; joins; agents read per-attempt snapshots; stale proposals rejected | complete | `WorkflowEngine`, `NodeContext`, `GitWorkspace.snapshot` | `runsIndependentNodesInParallelAndJoinsOnBoth`, `conflictingParallelProposalsCannotOverwriteAcceptedWork` |
| R4.3 Context and decision lineage | Versioned artifacts; consumed hashes recorded; decisions carry lineage | complete | `ArtifactStore`, `NodeContext`, `DecisionRecorded` | `replansConsumersWhenAReviewerAmendsAnUpstreamArtifact` |
| R4.4 Human approval for high-impact actions | Authorization before execution (build file) and acceptance after validation (sign-offs, migrations, contract, size, degraded verification); bound to proposal and tree hashes; fail closed | complete | `NodeRunner`, `ScriptedApprovalGateway`, `ApprovalModes`, `BuildIntegrityRule` | `buildFileChangesAreNeitherWrittenNorExecutedBeforeAuthorization`, `buildFileValidationControlsCannotBeWeakened`, `liveRunsWithoutATerminalNeverFallBackToAutomaticApproval`, `unknownApprovalModesAreRejected`, `decidesOnlyWhatItsNextDecisionCoversAndFailsClosedOnAnythingElse`, `invalidReviewerAmendmentsAreRefusedAndTheReviewerIsAskedAgain`; greenfield authorization of `pom.xml` |
| R4.5 Retries, fallback, rollback, safe stop | Bounded retries with feedback; fallback; rollback on every failure path incl. exceptions; quarantine if rollback fails; kill switch, budgets carried across resume | complete | `NodeRunner`, `WorkflowEngine` | `aPartialApplyFailureRestoresTheAcceptedWorkspace`, `aCheckpointFailureRestoresTheWorkspaceAndTheAttemptIsRetried`, `anApprovalGatewayFailureRestoresTheWorkspaceAndPauses`, `aFailedRollbackQuarantinesTheWorkspaceAndStopsWithBothFailuresRecorded`, `budgetsCarryOverAcrossResumeUnlessAResetIsAuthorizedAndRecorded`; drill |
| R4.6 Policy guardrails | Workspace and stage write scopes, build integrity, secrets, immutable migrations, sensitive files, size; fail closed | complete | `core/policy/*`, `GovernancePolicy` | `PolicyRulesTest` (21 cases); drill fault 3 |
| R4.7 Audit-grade observability | Hash-chained log; fail-stop on write failure; resume verifies chain, artifact hashes and identity | complete | `EventLog`, `WorkflowEngine.resume` | `anAuditLogFailureStopsTheRunAndRestoresTheWorkspaceWithoutClaimingSuccess`, `refusesToResumeFromATamperedAuditLog`, `refusesToResumeWhenAPersistedArtifactNoLongerMatchesItsRecordedHash`, `recoversOnlyFullyAcceptedWorkWhenInterruptedAtAnAcceptanceBoundary` (4 crash points) |
| R4.8 Reliability metrics | Projection of the log: success rate, first-pass yield, retry/rollback rates, MTTR, latency | complete | `MetricsCalculator` | `derivesReliabilityMetricsFromTheEventLog`; `metrics.json` in each evidence set |
| R4.9 Dynamic re-planning | Expansion; content-hash invalidation; in-flight work invalidated by upstream changes is discarded, not published | complete | `WorkflowEngine` | `inFlightWorkWhoseInputWasReplacedIsDiscardedNotPublished`, `inFlightWorkDownstreamOfAnInvalidatedNodeCannotBecomeAccepted`; drill fault 2 |
| R5 Engineering output | Code, OpenAPI contract, migrations, unit + integration tests, docs, release notes, patch | complete | brownfield patch → final service | final service: 153 unit + 24 PostgreSQL ITs, 99.3% line coverage; `ContractConformanceIT` |
| R6 Validation and risk control | Gates per stage; final validation re-runs every deterministic check on the released tree; security review must still describe it; degraded verification needs a waiver | complete | `FinalValidationTask`, `ValidatedTreeGate`, `ReleaseAgent` | `evidenceAndAcceptanceAreBoundToTheExactValidatedTree`, `aGateThatModifiesTheTreeInvalidatesItsOwnValidation`, `aCodeRepairAfterTheSecurityReviewMakesThatEvidenceStale`, `degradedVerificationCannotBeReleasedWithoutAnExplicitWaiver` |
| R7 Controlled autonomy | Agents propose, engine applies; write scopes; budgets; humans authorize and accept | complete for change control; **partial** for execution isolation: the default host sandbox only scrubs the environment; the opt-in Docker sandbox isolates builds and tests | `CommandSandbox`, `HostSandbox`, `DockerSandbox` | `theHostSandboxPassesOnlyAllowListedEnvironmentVariables`, `theDockerSandboxRunsMavenOfflineAndDescribesItsBoundary`; manual probe in [testing.md](testing.md#execution-sandbox) |
| R8 Final summary | | complete | [FINAL_SUMMARY.md](../FINAL_SUMMARY.md) | |

## URL shortener requirements (final service)

| Requirement | Status | Verified by |
|---|---|---|
| Create, redirect, read, delete | complete | `LinkLifecycleIT`, `LinkWebLayerTest` |
| Expiry on cache hits and misses (issue #1) | complete | `CachedLinkExpiryIT`, `LinkResolverTest` |
| Click recording and `GET /api/v1/links/{code}/stats` | complete (best effort, [ADR-0017](adr/0017-best-effort-click-analytics.md)) | `ClickAnalyticsIT`, `ClickRecorderTest`, `LinkStatsServiceTest` |
| Privacy: no IP or user-agent string | complete | `storesOnlyTheReferrerHostAndAnAgentFamilyNeverAnIpOrUserAgent` |
| Consistent validation and errors | complete | `LinkWebLayerTest`, `failedRedirectsAreNotCountedAndTheStatisticsWindowIsValidated` |
| Migrations, contract, docs | complete | Flyway V1/V2 in ITs; `ContractConformanceIT`; final-validation docs and contract gates |
| 2,000 redirects/s with p99 < 50 ms (ambiguous scenario's demonstration answer) | **not verified as a production claim**: one local measurement, see [testing.md](testing.md#load-test) | `tools/loadtest/redirect-load.sh` |
