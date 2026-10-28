package io.github.marishasahay.agentic.core.approval;

import io.github.marishasahay.agentic.core.gate.GateResult;
import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import java.util.List;
import java.util.Map;

/**
 * Everything a reviewer needs to make a decision, gathered after the gates passed so the human
 * reviews verified work rather than raw agent output.
 *
 * @param runId run id
 * @param nodeId node awaiting approval
 * @param stage node stage
 * @param summary the agent's own summary of what it did
 * @param reasons why approval is required (policy findings or node configuration)
 * @param findings all policy findings for the proposal
 * @param proposedOutputs artifacts the node wants to publish
 * @param diff unified diff of workspace changes, empty if none
 * @param gateResults exit-gate evidence
 */
public record ApprovalRequest(
    String runId,
    String nodeId,
    Stage stage,
    String summary,
    List<String> reasons,
    List<PolicyFinding> findings,
    Map<String, String> proposedOutputs,
    String diff,
    List<GateResult> gateResults) {}
