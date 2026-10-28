package io.github.marishasahay.agentic.core.engine;

import io.github.marishasahay.agentic.core.approval.ApprovalGateway;
import io.github.marishasahay.agentic.core.artifact.Artifact;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.event.EventEnvelope;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.WorkflowGraph;
import io.github.marishasahay.agentic.core.policy.PolicyEngine;
import io.github.marishasahay.agentic.core.util.Hashing;
import io.github.marishasahay.agentic.core.workspace.Workspace;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Stateful, non-linear scheduler for a {@link WorkflowGraph}.
 *
 * <ul>
 *   <li>Nodes whose dependencies have all succeeded run in parallel on virtual threads (bounded
 *       by {@link EngineConfig#maxParallelNodes()}); a node with several dependencies is the
 *       synchronization point (join).</li>
 *   <li>A failed node skips its dependents; independent branches finish what is running.</li>
 *   <li>After a node succeeds its expansion can insert new nodes (dynamic task decomposition),
 *       and any accepted node that consumed an artifact which has since changed is invalidated
 *       together with everything downstream of it (re-planning).</li>
 *   <li>Kill switch, time/attempt/model-call budgets and a failure threshold engage a safe stop.
 *       </li>
 * </ul>
 *
 * <p>All scheduling state is owned by the calling thread; node runners only report outcomes.
 */
public final class WorkflowEngine {

  private final String runId;
  private final EngineConfig config;
  private final EventLog events;
  private final ArtifactStore artifacts;
  private final Workspace workspace;
  private final Clock clock;
  private final StopController stop = new StopController();
  private final AtomicInteger attempts = new AtomicInteger();
  private final AtomicInteger agentCalls = new AtomicInteger();
  private final Map<String, NodeState> states = new LinkedHashMap<>();
  private final Map<String, Map<String, String>> consumedInputs = new HashMap<>();
  private final Set<String> staleWhileRunning = new LinkedHashSet<>();
  private final NodeRunner runner;
  private final ExecutorService taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private int failedNodes;
  private volatile Instant started;

  private WorkflowEngine(Builder builder) {
    this.runId = Objects.requireNonNull(builder.events, "events").runId();
    this.config = builder.config;
    this.events = builder.events;
    this.artifacts = Objects.requireNonNull(builder.artifacts, "artifacts");
    this.workspace = builder.workspace;
    this.clock = builder.clock;
    this.runner = new NodeRunner(runId, artifacts, workspace, builder.policy, builder.approvals,
        events, clock, stop, new ReentrantLock(), attempts, agentCalls, taskExecutor,
        this::enforceStopConditions);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Exposed so a CLI signal handler or UI can engage a safe stop.
   *
   * @return this engine's stop switch
   */
  public StopController stopController() {
    return stop;
  }

  /**
   * Executes a fresh run.
   *
   * @param graph workflow graph (mutated in place by expansions)
   * @param scenario scenario name for the audit log
   * @param requirement raw requirement text (hashed into the log)
   * @return final outcome
   */
  public RunOutcome run(WorkflowGraph graph, String scenario, String requirement) {
    return run(graph, scenario, requirement, Map.of());
  }

  /**
   * Executes a fresh run, recording extra settings (model, mode, build profile) for the audit.
   *
   * @param graph workflow graph (mutated in place by expansions)
   * @param scenario scenario name for the audit log
   * @param requirement raw requirement text (hashed into the log)
   * @param settings additional settings to record alongside the engine limits
   * @return final outcome
   */
  public RunOutcome run(WorkflowGraph graph, String scenario, String requirement,
      Map<String, String> settings) {
    Map<String, String> recorded = new LinkedHashMap<>(config.describe());
    recorded.putAll(settings);
    events.append(new RunEvent.RunStarted(scenario, Hashing.sha256(requirement), graph.size(),
        recorded));
    return schedule(graph);
  }

  /**
   * Resumes a stopped or failed run from its own event log: accepted nodes and their artifacts
   * are restored, expansions are replayed from stored artifacts (no model calls), the workspace
   * is reset to the last checkpoint, and failed/skipped/unfinished nodes run again.
   *
   * @param graph the same template graph the run started with
   * @return final outcome
   */
  public RunOutcome resume(WorkflowGraph graph) {
    List<EventEnvelope> history = events.events();
    Map<String, RunEvent.NodeSucceeded> accepted = new LinkedHashMap<>();
    // Only executions that finished (succeeded or failed) count: an execution interrupted by a
    // safe stop produced no outcome, so its resumed run keeps the same number and replay key.
    Map<String, Integer> executions = new HashMap<>();
    String lastCheckpoint = null;
    for (EventEnvelope envelope : history) {
      switch (envelope.event()) {
        case RunEvent.ArtifactProduced a -> artifacts.restore(new Artifact(a.key(), a.version(),
            a.sha256(), a.producedBy(), envelope.at(), artifacts.readPersisted(a.path())));
        case RunEvent.NodeSucceeded s -> {
          accepted.remove(s.nodeId());
          accepted.put(s.nodeId(), s);
          executions.merge(s.nodeId(), 1, Integer::sum);
        }
        case RunEvent.NodeFailed f -> executions.merge(f.nodeId(), 1, Integer::sum);
        case RunEvent.NodeInvalidated i -> accepted.remove(i.nodeId());
        case RunEvent.CheckpointCreated c -> lastCheckpoint = c.checkpoint();
        default -> {
          // other events carry no resumable state
        }
      }
    }
    for (RunEvent.NodeSucceeded s : accepted.values()) {
      if (!graph.contains(s.nodeId())) {
        continue;
      }
      states.put(s.nodeId(), NodeState.SUCCEEDED);
      consumedInputs.put(s.nodeId(), s.inputs());
      NodeSpec spec = graph.node(s.nodeId());
      if (spec.expansion() != null) {
        graph.insertAfter(spec.id(), spec.expansion().expand(artifacts));
      }
    }
    if (workspace != null && lastCheckpoint != null) {
      workspace.rollbackTo(lastCheckpoint);
    }
    runner.restoreExecutions(executions);
    events.append(new RunEvent.RunResumed(states.size()));
    return schedule(graph);
  }

  private RunOutcome schedule(WorkflowGraph graph) {
    started = clock.instant();
    Map<Future<NodeOutcome>, String> running = new HashMap<>();
    try (ExecutorService nodeExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletionService<NodeOutcome> completion = new ExecutorCompletionService<>(nodeExecutor);
      while (true) {
        enforceStopConditions();
        skipBlocked(graph);
        if (!stop.isStopRequested()) {
          for (NodeSpec node : readyNodes(graph)) {
            if (running.size() >= config.maxParallelNodes()) {
              break;
            }
            states.put(node.id(), NodeState.RUNNING);
            running.put(completion.submit(() -> runner.run(node)), node.id());
          }
        }
        if (running.isEmpty()) {
          break;
        }
        Future<NodeOutcome> done = completion.poll(config.pollInterval().toMillis(),
            TimeUnit.MILLISECONDS);
        if (done != null) {
          String nodeId = running.remove(done);
          onOutcome(graph, outcomeOf(done, nodeId));
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      requestStop("scheduler interrupted");
    } finally {
      taskExecutor.shutdownNow();
    }
    return finish(graph);
  }

  private void onOutcome(WorkflowGraph graph, NodeOutcome outcome) {
    String id = outcome.nodeId();
    if (!graph.contains(id)) {
      return;
    }
    if (staleWhileRunning.remove(id)) {
      states.put(id, NodeState.PENDING);
      return;
    }
    switch (outcome.kind()) {
      case SUCCEEDED -> {
        states.put(id, NodeState.SUCCEEDED);
        consumedInputs.put(id, outcome.inputs());
        expand(graph, graph.node(id));
        replanStaleNodes(graph, outcome.updatedKeys());
      }
      case FAILED -> {
        states.put(id, NodeState.FAILED);
        failedNodes++;
        if (failedNodes >= config.maxFailedNodes()) {
          requestStop("failure threshold reached (" + failedNodes + " failed node(s))");
        }
      }
      case STOPPED -> states.put(id, NodeState.PENDING);
      default -> throw new IllegalStateException("Unknown outcome " + outcome.kind());
    }
  }

  private void expand(WorkflowGraph graph, NodeSpec node) {
    if (node.expansion() == null) {
      return;
    }
    Set<String> removed = graph.removeSpawnedBy(node.id());
    removed.forEach(states::remove);
    List<NodeSpec> added = node.expansion().expand(artifacts);
    graph.insertAfter(node.id(), added);
    events.append(new RunEvent.GraphExpanded(node.id(),
        added.stream().map(NodeSpec::id).toList(), List.copyOf(removed)));
  }

  /**
   * Re-planning: any accepted node that consumed an older version of a changed artifact is
   * invalidated together with its downstream closure. Nodes still running are marked stale and
   * re-queued when they report back.
   */
  private void replanStaleNodes(WorkflowGraph graph, Set<String> changedKeys) {
    if (changedKeys.isEmpty()) {
      return;
    }
    for (String id : graph.topologicalOrder()) {
      Map<String, String> inputs = consumedInputs.get(id);
      if (inputs == null || states.get(id) != NodeState.SUCCEEDED) {
        continue;
      }
      for (String key : changedKeys) {
        String consumedSha = inputs.get(key);
        String currentSha = artifacts.latest(key).map(Artifact::sha256).orElse(null);
        if (consumedSha != null && !consumedSha.equals(currentSha)) {
          invalidate(graph, id, "input '" + key + "' changed (" + consumedSha.substring(0, 12)
              + " -> " + (currentSha == null ? "none" : currentSha.substring(0, 12)) + ")");
          break;
        }
      }
    }
  }

  private void invalidate(WorkflowGraph graph, String id, String cause) {
    List<String> closure = new ArrayList<>(List.of(id));
    closure.addAll(graph.transitiveDependentsOf(id));
    for (String target : closure) {
      if (!graph.contains(target)) {
        continue;
      }
      NodeState state = states.getOrDefault(target, NodeState.PENDING);
      if (state == NodeState.RUNNING) {
        staleWhileRunning.add(target);
      } else if (state != NodeState.PENDING) {
        states.put(target, NodeState.PENDING);
        consumedInputs.remove(target);
        events.append(new RunEvent.NodeInvalidated(target,
            target.equals(id) ? cause : "upstream '" + id + "' invalidated"));
      }
    }
  }

  private void skipBlocked(WorkflowGraph graph) {
    for (String id : graph.topologicalOrder()) {
      if (states.getOrDefault(id, NodeState.PENDING) != NodeState.PENDING) {
        continue;
      }
      graph.node(id).dependsOn().stream()
          .filter(dep -> states.getOrDefault(dep, NodeState.PENDING).isTerminalFailure())
          .findFirst()
          .ifPresent(dep -> {
            states.put(id, NodeState.SKIPPED);
            events.append(new RunEvent.NodeSkipped(id, "upstream '" + dep + "' did not succeed"));
          });
    }
  }

  private List<NodeSpec> readyNodes(WorkflowGraph graph) {
    List<NodeSpec> ready = new ArrayList<>();
    for (String id : graph.topologicalOrder()) {
      NodeSpec node = graph.node(id);
      if (states.getOrDefault(id, NodeState.PENDING) == NodeState.PENDING
          && node.dependsOn().stream().allMatch(d -> states.get(d) == NodeState.SUCCEEDED)) {
        ready.add(node);
      }
    }
    return ready;
  }

  /** Called by the scheduler between rounds and by runners before every attempt. */
  private void enforceStopConditions() {
    if (config.killSwitch() != null && Files.exists(config.killSwitch())) {
      requestStop("kill switch engaged (" + config.killSwitch().getFileName() + ")");
    } else if (Duration.between(started, clock.instant()).compareTo(config.maxRunDuration()) > 0) {
      requestStop("run-time budget of " + config.maxRunDuration() + " exhausted");
    } else if (attempts.get() >= config.maxTotalAttempts()) {
      requestStop("attempt budget of " + config.maxTotalAttempts() + " exhausted");
    } else if (agentCalls.get() >= config.maxAgentCalls()) {
      requestStop("model-call budget of " + config.maxAgentCalls() + " exhausted");
    }
  }

  private void requestStop(String reason) {
    if (stop.requestStop(reason)) {
      events.append(new RunEvent.StopRequested(reason));
    }
  }

  private NodeOutcome outcomeOf(Future<NodeOutcome> future, String nodeId) {
    try {
      return future.get();
    } catch (ExecutionException e) {
      return NodeOutcome.failed(nodeId, "runner crashed: " + e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return NodeOutcome.stopped(nodeId, "interrupted");
    }
  }

  private RunOutcome finish(WorkflowGraph graph) {
    skipBlocked(graph);
    Map<String, NodeState> finalStates = new LinkedHashMap<>();
    for (String id : graph.topologicalOrder()) {
      finalStates.put(id, states.getOrDefault(id, NodeState.PENDING));
    }
    boolean anyFailed = finalStates.containsValue(NodeState.FAILED);
    boolean allSucceeded = finalStates.values().stream().allMatch(s -> s == NodeState.SUCCEEDED);
    RunStatus status = anyFailed ? RunStatus.FAILED
        : allSucceeded ? RunStatus.SUCCEEDED : RunStatus.PAUSED;
    String reason = switch (status) {
      case SUCCEEDED -> "";
      case FAILED -> "failed nodes: " + finalStates.entrySet().stream()
          .filter(e -> e.getValue() == NodeState.FAILED).map(Map.Entry::getKey).toList();
      case PAUSED -> stop.isStopRequested() ? stop.reason() : "unfinished nodes remain";
    };
    events.append(new RunEvent.RunFinished(status.name(), reason));
    return new RunOutcome(runId, status, reason, finalStates);
  }

  /** Assembles an engine; only the event log and artifact store are mandatory. */
  public static final class Builder {
    private EngineConfig config = EngineConfig.defaults();
    private EventLog events;
    private ArtifactStore artifacts;
    private Workspace workspace;
    private PolicyEngine policy = PolicyEngine.permissive();
    private ApprovalGateway approvals = request -> {
      throw new IllegalStateException("No approval gateway configured for " + request.nodeId());
    };
    private Clock clock = Clock.systemUTC();

    private Builder() {}

    public Builder config(EngineConfig value) {
      this.config = value;
      return this;
    }

    public Builder events(EventLog value) {
      this.events = value;
      return this;
    }

    public Builder artifacts(ArtifactStore value) {
      this.artifacts = value;
      return this;
    }

    public Builder workspace(Workspace value) {
      this.workspace = value;
      return this;
    }

    public Builder policy(PolicyEngine value) {
      this.policy = value;
      return this;
    }

    public Builder approvals(ApprovalGateway value) {
      this.approvals = value;
      return this;
    }

    public Builder clock(Clock value) {
      this.clock = value;
      return this;
    }

    public WorkflowEngine build() {
      return new WorkflowEngine(this);
    }
  }
}
