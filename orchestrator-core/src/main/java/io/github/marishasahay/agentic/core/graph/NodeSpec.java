package io.github.marishasahay.agentic.core.graph;

import io.github.marishasahay.agentic.core.engine.NodeTask;
import io.github.marishasahay.agentic.core.gate.Gate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of one unit of work in the workflow graph.
 *
 * @param id unique node id
 * @param stage SDLC stage, for grouping and metrics
 * @param description what the node is responsible for
 * @param dependsOn ids of nodes that must succeed first
 * @param task primary strategy
 * @param fallback alternative strategy used once the primary exhausts its retries, or null
 * @param retry retry policy applied to each strategy
 * @param entryGates preconditions checked once before the first attempt
 * @param exitGates checks every attempt's output must pass
 * @param approval when a human must sign off
 * @param expansion optional subgraph generator run after success, or null
 * @param origin id of the node whose expansion created this node, or null for template nodes
 * @param timeout wall-clock limit for one attempt of the task
 */
public record NodeSpec(
    String id,
    Stage stage,
    String description,
    List<String> dependsOn,
    NodeTask task,
    NodeTask fallback,
    RetryPolicy retry,
    List<Gate> entryGates,
    List<Gate> exitGates,
    ApprovalMode approval,
    GraphExpansion expansion,
    String origin,
    Duration timeout) {

  public NodeSpec {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(task, "task for node " + id);
    if (!id.matches("[a-z0-9][a-z0-9._-]*")) {
      throw new IllegalArgumentException("Node id must be lower-kebab-case: " + id);
    }
    description = description == null ? "" : description;
    dependsOn = List.copyOf(new LinkedHashSet<>(dependsOn));
    retry = retry == null ? RetryPolicy.standard() : retry;
    entryGates = List.copyOf(entryGates);
    exitGates = List.copyOf(exitGates);
    approval = approval == null ? ApprovalMode.POLICY : approval;
    timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
  }

  public static Builder builder(String id, Stage stage) {
    return new Builder(id, stage);
  }

  /**
   * Copy of this node with a different dependency list (used when the graph is rewired).
   *
   * @param newDependsOn replacement dependency ids
   * @return the rewired node
   */
  public NodeSpec withDependsOn(List<String> newDependsOn) {
    return new NodeSpec(id, stage, description, newDependsOn, task, fallback, retry, entryGates,
        exitGates, approval, expansion, origin, timeout);
  }

  /**
   * Copy of this node marked as spawned by another node's expansion.
   *
   * @param parentId the expanding node
   * @return the marked node
   */
  public NodeSpec withOrigin(String parentId) {
    return new NodeSpec(id, stage, description, dependsOn, task, fallback, retry, entryGates,
        exitGates, approval, expansion, parentId, timeout);
  }

  /** Fluent builder; template definitions read much better than a 13-argument constructor. */
  public static final class Builder {
    private final String id;
    private final Stage stage;
    private String description = "";
    private final List<String> dependsOn = new ArrayList<>();
    private NodeTask task;
    private NodeTask fallback;
    private RetryPolicy retry = RetryPolicy.standard();
    private final List<Gate> entryGates = new ArrayList<>();
    private final List<Gate> exitGates = new ArrayList<>();
    private ApprovalMode approval = ApprovalMode.POLICY;
    private GraphExpansion expansion;
    private Duration timeout = Duration.ofMinutes(10);

    private Builder(String id, Stage stage) {
      this.id = id;
      this.stage = stage;
    }

    public Builder description(String value) {
      this.description = value;
      return this;
    }

    public Builder dependsOn(String... ids) {
      dependsOn.addAll(List.of(ids));
      return this;
    }

    public Builder dependsOn(List<String> ids) {
      dependsOn.addAll(ids);
      return this;
    }

    public Builder task(NodeTask value) {
      this.task = value;
      return this;
    }

    public Builder fallback(NodeTask value) {
      this.fallback = value;
      return this;
    }

    public Builder retry(RetryPolicy value) {
      this.retry = value;
      return this;
    }

    public Builder entryGate(Gate gate) {
      entryGates.add(gate);
      return this;
    }

    public Builder exitGate(Gate gate) {
      exitGates.add(gate);
      return this;
    }

    public Builder approval(ApprovalMode value) {
      this.approval = value;
      return this;
    }

    public Builder expansion(GraphExpansion value) {
      this.expansion = value;
      return this;
    }

    public Builder timeout(Duration value) {
      this.timeout = value;
      return this;
    }

    public NodeSpec build() {
      return new NodeSpec(id, stage, description, dependsOn, task, fallback, retry, entryGates,
          exitGates, approval, expansion, null, timeout);
    }
  }
}
