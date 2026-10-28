package io.github.marishasahay.agentic.core.engine;

import java.util.Map;

/**
 * Result of {@link WorkflowEngine#run} or {@link WorkflowEngine#resume}.
 *
 * @param runId run id
 * @param status final status
 * @param reason explanation when not successful
 * @param nodeStates final state of every node
 */
public record RunOutcome(
    String runId, RunStatus status, String reason, Map<String, NodeState> nodeStates) {

  public RunOutcome {
    nodeStates = Map.copyOf(nodeStates);
  }
}
