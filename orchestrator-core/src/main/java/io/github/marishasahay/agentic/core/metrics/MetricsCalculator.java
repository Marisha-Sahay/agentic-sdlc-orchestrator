package io.github.marishasahay.agentic.core.metrics;

import io.github.marishasahay.agentic.core.event.EventEnvelope;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.policy.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Folds an event log into {@link RunMetrics}. Because metrics are a projection of the log rather
 * than counters kept alongside it, they can be recomputed for any historical run and can never
 * disagree with the audit trail.
 */
public final class MetricsCalculator {

  private MetricsCalculator() {}

  /**
   * Computes the metrics of one run.
   *
   * @param log the run's events in order
   * @return metrics
   */
  public static RunMetrics compute(List<EventEnvelope> log) {
    Accumulator acc = new Accumulator();
    for (EventEnvelope envelope : log) {
      acc.accept(envelope);
    }
    return acc.toMetrics(log, EventLog.verifyChain(log).isEmpty());
  }

  /**
   * Aggregates several runs (the {@code metrics} command).
   *
   * @param runs per-run metrics
   * @return aggregate figures keyed by name
   */
  public static Map<String, Object> aggregate(List<RunMetrics> runs) {
    Map<String, Object> result = new LinkedHashMap<>();
    long succeeded = runs.stream().filter(r -> r.status().equals("SUCCEEDED")).count();
    int attempts = runs.stream().mapToInt(RunMetrics::attempts).sum();
    int retries = runs.stream().mapToInt(RunMetrics::retries).sum();
    int rollbacks = runs.stream().mapToInt(RunMetrics::rollbacks).sum();
    double mttr = runs.stream().filter(r -> r.mttrMs() >= 0).mapToLong(RunMetrics::mttrMs)
        .average().orElse(-1);
    result.put("runs", runs.size());
    result.put("runSuccessRate", runs.isEmpty() ? 0.0 : (double) succeeded / runs.size());
    result.put("attempts", attempts);
    result.put("retryRate", attempts == 0 ? 0.0 : (double) retries / attempts);
    result.put("rollbackRate", attempts == 0 ? 0.0 : (double) rollbacks / attempts);
    result.put("fallbacks", runs.stream().mapToInt(RunMetrics::fallbacks).sum());
    result.put("meanMttrMs", Math.round(mttr));
    result.put("meanEndToEndMs", Math.round(runs.stream().mapToLong(RunMetrics::endToEndMs)
        .average().orElse(0)));
    result.put("meanMachineMs", Math.round(runs.stream().mapToLong(RunMetrics::machineMs)
        .average().orElse(0)));
    result.put("agentCalls", runs.stream().mapToInt(RunMetrics::agentCalls).sum());
    return result;
  }

  /** Mutable fold state; one instance per computation. */
  private static final class Accumulator {
    private String runId = "";
    private String scenario = "";
    private String status = "RUNNING";
    private final Map<String, String> finalState = new LinkedHashMap<>();
    private final Map<String, Instant> nodeStart = new HashMap<>();
    private final Map<String, Instant> failureEpisodeStart = new HashMap<>();
    private final Map<String, Instant> approvalStart = new HashMap<>();
    private final Map<String, Long> stageDurations = new TreeMap<>();
    private int firstPass;
    private int attempts;
    private int retries;
    private int fallbacks;
    private int rollbacks;
    private int gateFailures;
    private int policyBlocks;
    private int approvalsRequested;
    private int approvalsRejected;
    private int invalidations;
    private int expansions;
    private int recovered;
    private long recoveryMs;
    private long humanWaitMs;
    private int agentCalls;
    private long inputTokens;
    private long outputTokens;

    void accept(EventEnvelope envelope) {
      runId = envelope.runId();
      Instant at = envelope.at();
      switch (envelope.event()) {
        case RunEvent.RunStarted e -> scenario = e.scenario();
        case RunEvent.NodeStarted e -> {
          attempts++;
          nodeStart.putIfAbsent(e.nodeId(), at);
          if (e.attempt() > 1) {
            retries++;
          }
        }
        case RunEvent.AttemptFailed e -> failureEpisodeStart.putIfAbsent(e.nodeId(), at);
        case RunEvent.FallbackActivated e -> fallbacks++;
        case RunEvent.RollbackPerformed e -> rollbacks++;
        case RunEvent.GateEvaluated e -> gateFailures += e.passed() ? 0 : 1;
        case RunEvent.PolicyEvaluated e -> policyBlocks += e.findings().stream()
            .anyMatch(f -> f.severity() == Severity.BLOCK) ? 1 : 0;
        case RunEvent.ApprovalRequested e -> {
          approvalsRequested++;
          approvalStart.put(e.nodeId(), at);
        }
        case RunEvent.ApprovalResolved e -> {
          approvalsRejected += e.approved() ? 0 : 1;
          Instant start = approvalStart.remove(e.nodeId());
          if (start != null) {
            humanWaitMs += Duration.between(start, at).toMillis();
          }
        }
        case RunEvent.AgentCalled e -> {
          agentCalls++;
          inputTokens += Math.max(0, e.inputTokens());
          outputTokens += Math.max(0, e.outputTokens());
        }
        case RunEvent.NodeSucceeded e -> onSucceeded(e, at);
        case RunEvent.NodeFailed e -> {
          finalState.put(e.nodeId(), "FAILED");
          closeNode(e.nodeId(), e.stage(), at);
        }
        case RunEvent.NodeSkipped e -> finalState.put(e.nodeId(), "SKIPPED");
        case RunEvent.NodeInvalidated e -> {
          invalidations++;
          finalState.remove(e.nodeId());
        }
        case RunEvent.GraphExpanded e -> expansions++;
        case RunEvent.RunFinished e -> status = e.status();
        default -> {
          // remaining events do not affect metrics
        }
      }
    }

    private void onSucceeded(RunEvent.NodeSucceeded e, Instant at) {
      finalState.put(e.nodeId(), "SUCCEEDED");
      if (e.attempts() == 1) {
        firstPass++;
      }
      Instant failedAt = failureEpisodeStart.remove(e.nodeId());
      if (failedAt != null) {
        recovered++;
        recoveryMs += Duration.between(failedAt, at).toMillis();
      }
      closeNode(e.nodeId(), e.stage(), at);
    }

    private void closeNode(String nodeId, String stage, Instant at) {
      Instant start = nodeStart.remove(nodeId);
      if (start != null) {
        stageDurations.merge(stage, Duration.between(start, at).toMillis(), Long::sum);
      }
    }

    RunMetrics toMetrics(List<EventEnvelope> log, boolean chainValid) {
      long e2e = log.isEmpty() ? 0
          : Duration.between(log.getFirst().at(), log.getLast().at()).toMillis();
      int succeeded = count("SUCCEEDED");
      int failed = count("FAILED");
      double successRate = succeeded + failed == 0 ? 0 : (double) succeeded / (succeeded + failed);
      double yield = succeeded == 0 ? 0 : Math.min(1.0, (double) firstPass / succeeded);
      return new RunMetrics(runId, scenario, status, e2e, humanWaitMs, succeeded, failed,
          count("SKIPPED"), successRate, yield, attempts, retries, fallbacks, rollbacks,
          gateFailures, policyBlocks, approvalsRequested, approvalsRejected, invalidations,
          expansions, recovered, recovered == 0 ? -1 : recoveryMs / recovered, agentCalls,
          inputTokens, outputTokens, stageDurations, chainValid);
    }

    private int count(String state) {
      return (int) finalState.values().stream().filter(state::equals).count();
    }
  }
}
