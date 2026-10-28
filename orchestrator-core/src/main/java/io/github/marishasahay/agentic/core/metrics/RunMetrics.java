package io.github.marishasahay.agentic.core.metrics;

import java.util.Map;

/**
 * Reliability and cost metrics of one run, derived purely from its event log.
 *
 * @param runId run id
 * @param scenario scenario name
 * @param status final status, or {@code RUNNING} if the log has no terminal event
 * @param endToEndMs first to last event
 * @param humanWaitMs time spent waiting for approvals
 * @param nodesSucceeded accepted nodes (final state)
 * @param nodesFailed failed nodes (final state)
 * @param nodesSkipped skipped nodes (final state)
 * @param nodeSuccessRate succeeded / (succeeded + failed)
 * @param firstPassYield share of accepted nodes that passed on their first attempt
 * @param attempts total attempts
 * @param retries attempts beyond the first per node execution
 * @param fallbacks fallback activations
 * @param rollbacks workspace rollbacks
 * @param gateFailures failed gate evaluations
 * @param policyBlocks attempts rejected by policy
 * @param approvalsRequested human checkpoints reached
 * @param approvalsRejected human rejections
 * @param invalidations nodes invalidated by re-planning
 * @param expansions run-time graph expansions
 * @param recoveredFailures failure episodes that ended in success
 * @param mttrMs mean time from first failed attempt to recovery, -1 if none
 * @param agentCalls model calls
 * @param inputTokens prompt tokens (known ones)
 * @param outputTokens completion tokens (known ones)
 * @param stageDurationsMs total node time per stage
 * @param auditChainValid whether the hash chain verifies
 */
public record RunMetrics(
    String runId,
    String scenario,
    String status,
    long endToEndMs,
    long humanWaitMs,
    int nodesSucceeded,
    int nodesFailed,
    int nodesSkipped,
    double nodeSuccessRate,
    double firstPassYield,
    int attempts,
    int retries,
    int fallbacks,
    int rollbacks,
    int gateFailures,
    int policyBlocks,
    int approvalsRequested,
    int approvalsRejected,
    int invalidations,
    int expansions,
    int recoveredFailures,
    long mttrMs,
    int agentCalls,
    long inputTokens,
    long outputTokens,
    Map<String, Long> stageDurationsMs,
    boolean auditChainValid) {

  public RunMetrics {
    stageDurationsMs = Map.copyOf(stageDurationsMs);
  }

  /** Machine time: end-to-end latency minus time spent waiting for humans. */
  public long machineMs() {
    return Math.max(0, endToEndMs - humanWaitMs);
  }

  /** Retries per attempt. */
  public double retryRate() {
    return attempts == 0 ? 0 : (double) retries / attempts;
  }

  /** Rollbacks per attempt. */
  public double rollbackRate() {
    return attempts == 0 ? 0 : (double) rollbacks / attempts;
  }
}
