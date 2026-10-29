package io.github.marishasahay.agentic.core.engine;

import io.github.marishasahay.agentic.core.artifact.Artifact;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.event.EventEnvelope;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.workspace.Workspace;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-attempt view of the run handed to a {@link NodeTask}. Reading an input through this class
 * records exactly which artifact version the node consumed: that record is the basis for
 * decision lineage and for re-planning when an upstream artifact changes.
 *
 * <p>Code is read from a snapshot of the revision the attempt started on, never from the live
 * workspace, so an agent always sees one consistent tree even while parallel nodes apply and
 * build their own changes; the engine later checks that the files it proposes to change did
 * not move underneath it.
 */
public final class NodeContext {

  private final String runId;
  private final NodeSpec node;
  private final int execution;
  private final int attempt;
  private final String strategy;
  private final List<String> feedback;
  private final ArtifactStore artifacts;
  private final Workspace workspace;
  private final String baseRevision;
  private final EventLog events;
  private final AtomicInteger agentCalls;
  private final StopController stop;
  private final Map<String, Artifact> consumed = new LinkedHashMap<>();
  private Workspace.Snapshot snapshot;
  private boolean closed;

  NodeContext(String runId, NodeSpec node, int execution, int attempt, String strategy,
      List<String> feedback, ArtifactStore artifacts, Workspace workspace, String baseRevision,
      EventLog events, AtomicInteger agentCalls, StopController stop) {
    this.runId = runId;
    this.node = node;
    this.execution = execution;
    this.attempt = attempt;
    this.strategy = strategy;
    this.feedback = List.copyOf(feedback);
    this.artifacts = artifacts;
    this.workspace = workspace;
    this.baseRevision = baseRevision;
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
   * Read-only snapshot of the workspace at {@link #baseRevision()}, created on first use and
   * deleted when the attempt's task returns. Writes to it are ignored: proposals are the only
   * way to change the workspace.
   *
   * @return snapshot root, or null when the run has no workspace
   */
  public synchronized Path workspaceRoot() {
    if (workspace == null) {
      return null;
    }
    if (closed) {
      throw new IllegalStateException("Attempt " + attempt + " of " + node.id()
          + " has finished; its workspace snapshot is gone");
    }
    if (snapshot == null) {
      snapshot = workspace.snapshot(baseRevision);
    }
    return snapshot.root();
  }

  /**
   * The accepted revision this attempt started from.
   *
   * @return revision id, or null when the run has no workspace
   */
  public String baseRevision() {
    return baseRevision;
  }

  /**
   * The tree a node's currently accepted result was judged on, read from the audit log: the
   * validated tree for nodes that build or change the workspace, the snapshot for readers.
   * Lets a later stage check that evidence still describes the code being released.
   *
   * @param nodeId node whose accepted tree is wanted
   * @return the tree hash, empty if the node has no accepted result
   */
  public Optional<String> acceptedTree(String nodeId) {
    String tree = null;
    for (EventEnvelope envelope : events.events()) {
      if (envelope.event() instanceof RunEvent.NodeSucceeded s && s.nodeId().equals(nodeId)) {
        tree = s.treeSha();
      } else if (envelope.event() instanceof RunEvent.NodeInvalidated i
          && i.nodeId().equals(nodeId)) {
        tree = null;
      }
    }
    return Optional.ofNullable(tree);
  }

  /**
   * Exit-gate results of a node's accepted attempt, read from the audit log. Lets later stages
   * (release readiness) cite validation evidence without re-running it.
   *
   * @param nodeId node whose accepted evidence is wanted
   * @return the gate evaluations of its last accepted attempt, empty if it never succeeded
   */
  public List<RunEvent.GateEvaluated> acceptedGateResults(String nodeId) {
    List<RunEvent.GateEvaluated> current = new ArrayList<>();
    List<RunEvent.GateEvaluated> accepted = List.of();
    for (EventEnvelope envelope : events.events()) {
      switch (envelope.event()) {
        case RunEvent.NodeStarted s when s.nodeId().equals(nodeId) -> current = new ArrayList<>();
        case RunEvent.GateEvaluated g when g.nodeId().equals(nodeId) && "exit".equals(g.phase())
            -> current.add(g);
        case RunEvent.NodeSucceeded s when s.nodeId().equals(nodeId) -> accepted = current;
        default -> {
          // not evidence of this node
        }
      }
    }
    return List.copyOf(accepted);
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

  /** Deletes the snapshot; later reads of {@link #workspaceRoot()} fail. */
  synchronized void close() {
    closed = true;
    if (snapshot != null) {
      snapshot.close();
      snapshot = null;
    }
  }
}
