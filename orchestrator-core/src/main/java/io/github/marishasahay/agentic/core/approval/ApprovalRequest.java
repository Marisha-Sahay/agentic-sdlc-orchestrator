package io.github.marishasahay.agentic.core.approval;

import io.github.marishasahay.agentic.core.gate.GateResult;
import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything a reviewer needs to make a decision, bound to the exact proposal (and, for
 * acceptance, the exact validated tree) the decision applies to. A changed proposal is a new
 * request with a different {@code proposalSha}; a decision never carries over to it.
 *
 * @param runId run id
 * @param nodeId node awaiting a decision
 * @param stage node stage
 * @param kind authorization before execution, or acceptance after validation
 * @param summary the agent's own summary of what it did
 * @param reasons why a decision is required, human-readable
 * @param reasonCodes the same reasons as stable codes ({@code sign-off}, policy rule names,
 *     task-declared codes such as {@code degraded-verification}); scripted decisions must cover
 *     every one of them
 * @param findings all policy findings for the proposal
 * @param proposedOutputs artifacts the node wants to publish
 * @param diff unified diff of the proposed workspace changes, empty if none
 * @param gateResults exit-gate evidence (empty for authorization: nothing has run yet)
 * @param proposalSha SHA-256 of the proposal (file changes and outputs)
 * @param treeSha git tree the gates validated (acceptance only), or null
 * @param problems why the previous decision for this request could not be used, if any
 */
public record ApprovalRequest(
    String runId,
    String nodeId,
    Stage stage,
    ApprovalKind kind,
    String summary,
    List<String> reasons,
    Set<String> reasonCodes,
    List<PolicyFinding> findings,
    Map<String, String> proposedOutputs,
    String diff,
    List<GateResult> gateResults,
    String proposalSha,
    String treeSha,
    List<String> problems) {

  public ApprovalRequest {
    reasons = List.copyOf(reasons);
    reasonCodes = Set.copyOf(reasonCodes);
    findings = List.copyOf(findings);
    proposedOutputs = Map.copyOf(proposedOutputs);
    gateResults = List.copyOf(gateResults);
    problems = List.copyOf(problems);
  }

  /**
   * The same request, re-asked because the previous decision was unusable.
   *
   * @param why problems with the previous decision
   * @return the re-asked request
   */
  public ApprovalRequest withProblems(List<String> why) {
    return new ApprovalRequest(runId, nodeId, stage, kind, summary, reasons, reasonCodes,
        findings, proposedOutputs, diff, gateResults, proposalSha, treeSha, why);
  }
}
