package io.github.marishasahay.agentic.core.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import java.util.List;
import java.util.Map;

/**
 * Every state change of a run. The append-only log of these events is the single source of
 * truth: the audit trail, decision lineage, reliability metrics, run report and resume-after-stop
 * are all derived from it.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RunEvent.RunStarted.class, name = "RunStarted"),
    @JsonSubTypes.Type(value = RunEvent.RunResumed.class, name = "RunResumed"),
    @JsonSubTypes.Type(value = RunEvent.NodeStarted.class, name = "NodeStarted"),
    @JsonSubTypes.Type(value = RunEvent.GateEvaluated.class, name = "GateEvaluated"),
    @JsonSubTypes.Type(value = RunEvent.PolicyEvaluated.class, name = "PolicyEvaluated"),
    @JsonSubTypes.Type(value = RunEvent.AttemptFailed.class, name = "AttemptFailed"),
    @JsonSubTypes.Type(value = RunEvent.FallbackActivated.class, name = "FallbackActivated"),
    @JsonSubTypes.Type(value = RunEvent.ApprovalRequested.class, name = "ApprovalRequested"),
    @JsonSubTypes.Type(value = RunEvent.ApprovalResolved.class, name = "ApprovalResolved"),
    @JsonSubTypes.Type(value = RunEvent.ArtifactProduced.class, name = "ArtifactProduced"),
    @JsonSubTypes.Type(value = RunEvent.DecisionRecorded.class, name = "DecisionRecorded"),
    @JsonSubTypes.Type(value = RunEvent.AgentCalled.class, name = "AgentCalled"),
    @JsonSubTypes.Type(value = RunEvent.CheckpointCreated.class, name = "CheckpointCreated"),
    @JsonSubTypes.Type(value = RunEvent.RollbackPerformed.class, name = "RollbackPerformed"),
    @JsonSubTypes.Type(value = RunEvent.NodeSucceeded.class, name = "NodeSucceeded"),
    @JsonSubTypes.Type(value = RunEvent.NodeFailed.class, name = "NodeFailed"),
    @JsonSubTypes.Type(value = RunEvent.NodeSkipped.class, name = "NodeSkipped"),
    @JsonSubTypes.Type(value = RunEvent.NodeInvalidated.class, name = "NodeInvalidated"),
    @JsonSubTypes.Type(value = RunEvent.GraphExpanded.class, name = "GraphExpanded"),
    @JsonSubTypes.Type(value = RunEvent.AttemptDiscarded.class, name = "AttemptDiscarded"),
    @JsonSubTypes.Type(value = RunEvent.AmendmentRejected.class, name = "AmendmentRejected"),
    @JsonSubTypes.Type(value = RunEvent.RollbackFailed.class, name = "RollbackFailed"),
    @JsonSubTypes.Type(value = RunEvent.BudgetReset.class, name = "BudgetReset"),
    @JsonSubTypes.Type(value = RunEvent.StopRequested.class, name = "StopRequested"),
    @JsonSubTypes.Type(value = RunEvent.RunFinished.class, name = "RunFinished")
})
public sealed interface RunEvent {

  /**
   * Node the event concerns.
   *
   * @return node id, or null for run-level events
   */
  default String nodeId() {
    return null;
  }

  /**
   * A run began.
   *
   * @param scenario scenario name
   * @param requirementSha hash of the input requirement
   * @param nodes number of template nodes
   * @param settings effective engine settings
   */
  record RunStarted(String scenario, String requirementSha, int nodes, Map<String, String> settings)
      implements RunEvent {}

  /**
   * A stopped run was resumed from its log, after the log's hash chain, the persisted artifacts
   * and the run identity were verified.
   *
   * @param restoredNodes nodes recovered as already succeeded
   * @param checkpoint accepted checkpoint the workspace was restored to ({@code baseline} if no
   *     accepted change exists)
   * @param discardedArtifacts artifact versions persisted by attempts that were never accepted
   *     (interrupted between publication and success); they are not restored
   * @param attemptsUsed attempts already spent, carried into the attempt budget
   * @param agentCallsUsed model calls already spent, carried into the call budget
   */
  record RunResumed(int restoredNodes, String checkpoint, List<String> discardedArtifacts,
      int attemptsUsed, int agentCallsUsed) implements RunEvent {}

  /**
   * An attempt began.
   *
   * @param nodeId node
   * @param stage node stage
   * @param attempt 1-based attempt number across strategies
   * @param strategy {@code primary} or {@code fallback}
   */
  record NodeStarted(String nodeId, String stage, int attempt, String strategy)
      implements RunEvent {}

  /**
   * A gate was evaluated.
   *
   * @param nodeId node
   * @param attempt attempt number (0 for entry gates)
   * @param phase {@code entry} or {@code exit}
   * @param gate gate name
   * @param passed outcome
   * @param evidence tool output tail or explanation
   * @param treeSha git tree the gate evaluated (exit gates on a workspace), or null
   */
  record GateEvaluated(
      String nodeId, int attempt, String phase, String gate, boolean passed, String evidence,
      String treeSha) implements RunEvent {}

  /**
   * Policy rules produced findings for a proposal.
   *
   * @param nodeId node
   * @param attempt attempt number
   * @param findings all findings
   */
  record PolicyEvaluated(String nodeId, int attempt, List<PolicyFinding> findings)
      implements RunEvent {}

  /**
   * An attempt was rejected.
   *
   * @param nodeId node
   * @param attempt attempt number
   * @param reason short classification
   * @param feedback evidence handed to the next attempt
   */
  record AttemptFailed(String nodeId, int attempt, String reason, String feedback)
      implements RunEvent {}

  /**
   * The primary strategy was exhausted and the fallback took over.
   *
   * @param nodeId node
   * @param reason why
   */
  record FallbackActivated(String nodeId, String reason) implements RunEvent {}

  /**
   * A human checkpoint was reached.
   *
   * @param nodeId node
   * @param reasons why a decision is required
   * @param kind {@code AUTHORIZATION} (before execution) or {@code ACCEPTANCE} (after gates)
   * @param reasonCodes stable reason codes
   * @param proposalSha hash of the exact proposal under review
   * @param treeSha validated git tree (acceptance only), or null
   */
  record ApprovalRequested(String nodeId, List<String> reasons, String kind,
      List<String> reasonCodes, String proposalSha, String treeSha) implements RunEvent {}

  /**
   * A reviewer decided. The decision applies to exactly {@code proposalSha} (and
   * {@code treeSha}); the engine re-asks for anything else.
   *
   * @param nodeId node
   * @param approved decision
   * @param reviewer who, including how the decision was made (human or scripted demonstration)
   * @param comment rationale
   * @param amendedArtifacts keys the reviewer edited
   * @param kind {@code AUTHORIZATION} or {@code ACCEPTANCE}
   * @param proposalSha hash of the proposal decided on
   * @param treeSha validated git tree decided on (acceptance only), or null
   */
  record ApprovalResolved(
      String nodeId, boolean approved, String reviewer, String comment,
      List<String> amendedArtifacts, String kind, String proposalSha, String treeSha)
      implements RunEvent {}

  /**
   * A reviewer amendment failed validation and was not applied.
   *
   * @param nodeId node whose approval carried the amendment
   * @param keys amended artifact keys
   * @param problems why the amendment was refused
   */
  record AmendmentRejected(String nodeId, List<String> keys, List<String> problems)
      implements RunEvent {}

  /**
   * A new artifact version was stored.
   *
   * @param key artifact key
   * @param version version
   * @param sha256 content hash
   * @param producedBy node id or {@code human:<reviewer>}
   * @param path persisted location relative to the artifact directory
   */
  record ArtifactProduced(String key, int version, String sha256, String producedBy, String path)
      implements RunEvent {}

  /**
   * An agent recorded a design or implementation decision.
   *
   * @param nodeId node
   * @param title what was decided
   * @param choice the option chosen
   * @param rationale why
   * @param alternatives options considered
   * @param basedOn artifact versions the decision was derived from (lineage)
   */
  record DecisionRecorded(
      String nodeId, String title, String choice, String rationale, List<String> alternatives,
      List<String> basedOn) implements RunEvent {}

  /**
   * One model call.
   *
   * @param nodeId node
   * @param agent agent role
   * @param model model name
   * @param mode {@code live}, {@code record} or {@code replay}
   * @param latencyMs latency
   * @param inputTokens prompt tokens, -1 if unknown
   * @param outputTokens completion tokens, -1 if unknown
   */
  record AgentCalled(
      String nodeId, String agent, String model, String mode, long latencyMs, int inputTokens,
      int outputTokens) implements RunEvent {}

  /**
   * Accepted workspace state was committed.
   *
   * @param nodeId node
   * @param checkpoint checkpoint id
   */
  record CheckpointCreated(String nodeId, String checkpoint) implements RunEvent {}

  /**
   * The workspace was returned to a checkpoint.
   *
   * @param nodeId node whose attempt was undone
   * @param toCheckpoint checkpoint restored
   * @param reason why
   */
  record RollbackPerformed(String nodeId, String toCheckpoint, String reason)
      implements RunEvent {}

  /**
   * Restoring the accepted state failed: the workspace is quarantined and the run safe-stops.
   *
   * @param nodeId node whose attempt was being undone
   * @param toCheckpoint checkpoint that could not be restored
   * @param originalFailure what made the rollback necessary
   * @param rollbackError why the rollback failed
   */
  record RollbackFailed(String nodeId, String toCheckpoint, String originalFailure,
      String rollbackError) implements RunEvent {}

  /**
   * An attempt that passed its gates was not accepted because its inputs or its base revision
   * changed while it ran; its workspace changes were rolled back and nothing was published.
   *
   * @param nodeId node
   * @param attempt attempt number
   * @param reason what changed
   */
  record AttemptDiscarded(String nodeId, int attempt, String reason) implements RunEvent {}

  /**
   * A node's outputs were accepted.
   *
   * @param nodeId node
   * @param stage stage
   * @param attempts attempts used
   * @param inputs consumed artifact key to full SHA-256
   * @param outputs produced artifact key to version ref
   * @param checkpoint workspace checkpoint created for this acceptance, or null if the node
   *     changed no files; this event is the commit point that makes it the accepted state
   * @param treeSha git tree the node's gates validated and that was accepted, or null
   */
  record NodeSucceeded(
      String nodeId, String stage, int attempts, Map<String, String> inputs,
      Map<String, String> outputs, String checkpoint, String treeSha) implements RunEvent {}

  /**
   * A node gave up.
   *
   * @param nodeId node
   * @param stage stage
   * @param reason final reason
   */
  record NodeFailed(String nodeId, String stage, String reason) implements RunEvent {}

  /**
   * A node cannot run because an upstream node failed.
   *
   * @param nodeId node
   * @param reason which upstream failure
   */
  record NodeSkipped(String nodeId, String reason) implements RunEvent {}

  /**
   * A previously accepted node must run again because an input changed.
   *
   * @param nodeId node
   * @param cause which input changed
   */
  record NodeInvalidated(String nodeId, String cause) implements RunEvent {}

  /**
   * The graph changed shape at run time.
   *
   * @param byNode node whose expansion ran
   * @param added added node ids
   * @param removed removed node ids (from an earlier expansion)
   */
  record GraphExpanded(String byNode, List<String> added, List<String> removed)
      implements RunEvent {
    @Override
    public String nodeId() {
      return byNode;
    }
  }

  /**
   * An operator explicitly reset the budgets of a resumed run.
   *
   * @param authorizedBy who authorized the reset
   * @param attemptsDiscarded attempts that no longer count against the budget
   * @param agentCallsDiscarded model calls that no longer count against the budget
   */
  record BudgetReset(String authorizedBy, int attemptsDiscarded, int agentCallsDiscarded)
      implements RunEvent {}

  /**
   * Safe-stop engaged: no new work is scheduled, running attempts finish.
   *
   * @param reason kill switch, budget, failure threshold...
   */
  record StopRequested(String reason) implements RunEvent {}

  /**
   * Terminal event.
   *
   * @param status final status
   * @param reason explanation for non-success
   */
  record RunFinished(String status, String reason) implements RunEvent {}
}
