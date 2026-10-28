package io.github.marishasahay.agentic.core.engine;

import io.github.marishasahay.agentic.core.artifact.Artifact;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-attempt view of the run handed to a {@link NodeTask}. Reading an input through this class
 * records exactly which artifact version the node consumed: that record is the basis for
 * decision lineage and for re-planning when an upstream artifact changes.
 */
public final class NodeContext {

  private final String runId;
  private final NodeSpec node;
  private final int execution;
  private final int attempt;
  private final String strategy;
  private final List<String> feedback;
  private final ArtifactStore artifacts;
  private final Path workspaceRoot;
  private final EventLog events;
  private final AtomicInteger agentCalls;
  private final StopController stop;
  private final Map<String, Artifact> consumed = new LinkedHashMap<>();

  NodeContext(String runId, NodeSpec node, int execution, int attempt, String strategy,
      List<String> feedback,
      ArtifactStore artifacts, Path workspaceRoot, EventLog events, AtomicInteger agentCalls,
      StopController stop) {
    this.runId = runId;
    this.node = node;
    this.execution = execution;
    this.attempt = attempt;
    this.strategy = strategy;
    this.feedback = List.copyOf(feedback);
    this.artifacts = artifacts;
    this.workspaceRoot = workspaceRoot;
    this.events = events;
    this.agentCalls = agentCalls;
    this.stop = stop;
  }

  public String runId() {
    return runId;
  }

  public NodeSpec node() {
    return node;
  }

  /**
   * How many times this node has been executed in the run, including this one. Greater than 1
   * after re-planning invalidated an earlier execution; part of the stable replay key.
   *
   * @return 1-based execution number
   */
  public int execution() {
    return execution;
  }

  public int attempt() {
    return attempt;
  }

  /**
   * Strategy executing this attempt.
   *
   * @return {@code primary} or {@code fallback}
   */
  public String strategy() {
    return strategy;
  }

  /**
   * Evidence from earlier failed attempts: gate output, policy findings, reviewer comments.
   *
   * @return one entry per rejected attempt, oldest first
   */
  public List<String> feedback() {
    return feedback;
  }

  public boolean isRetry() {
    return !feedback.isEmpty();
  }

  /**
   * Workspace to read existing code from (read-only by contract).
   *
   * @return workspace root, or null when the run has no workspace
   */
  public Path workspaceRoot() {
    return workspaceRoot;
  }

  /**
   * Reads a required input and records the consumed version.
   *
   * @param key artifact key
   * @return content of the latest version
   */
  public String input(String key) {
    return optionalInput(key)
        .orElseThrow(() -> new IllegalStateException("Node " + node.id() + " needs " + key));
  }

  /**
   * Reads an optional input and records the consumed version if present.
   *
   * @param key artifact key
   * @return content of the latest version, if any
   */
  public Optional<String> optionalInput(String key) {
    Optional<Artifact> artifact = artifacts.latest(key);
    artifact.ifPresent(a -> {
      synchronized (consumed) {
        consumed.put(key, a);
      }
    });
    return artifact.map(Artifact::content);
  }

  /**
   * Records a model call for observability and budget enforcement.
   *
   * @param agent agent role
   * @param model model name
   * @param mode live, record or replay
   * @param latencyMs latency
   * @param inputTokens prompt tokens or -1
   * @param outputTokens completion tokens or -1
   */
  public void recordAgentCall(String agent, String model, String mode, long latencyMs,
      int inputTokens, int outputTokens) {
    agentCalls.incrementAndGet();
    events.append(new RunEvent.AgentCalled(node.id(), agent, model, mode, latencyMs, inputTokens,
        outputTokens));
  }

  /**
   * Cooperative cancellation: long-running tasks should check this between steps.
   *
   * @return true once a safe stop has been engaged
   */
  public boolean stopRequested() {
    return stop.isStopRequested();
  }

  Map<String, Artifact> consumed() {
    synchronized (consumed) {
      return Map.copyOf(consumed);
    }
  }
}
