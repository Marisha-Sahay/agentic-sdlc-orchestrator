package io.github.marishasahay.agentic.core.engine;

import io.github.marishasahay.agentic.core.approval.ApprovalDecision;
import io.github.marishasahay.agentic.core.approval.ApprovalGateway;
import io.github.marishasahay.agentic.core.approval.ApprovalRequest;
import io.github.marishasahay.agentic.core.artifact.Artifact;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.gate.Gate;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;
import io.github.marishasahay.agentic.core.graph.ApprovalMode;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.policy.PolicyEngine;
import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import io.github.marishasahay.agentic.core.policy.PolicyInput;
import io.github.marishasahay.agentic.core.policy.Severity;
import io.github.marishasahay.agentic.core.workspace.Workspace;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Executes one node through its full governed lifecycle:
 *
 * <pre>
 * entry gates -> [attempt: task -> policy -> apply -> exit gates -> approval -> checkpoint]*
 *             -> fallback strategy -> fail
 * </pre>
 *
 * <p>Every attempt starts from the last accepted checkpoint, and every rejected attempt is rolled
 * back, so the workspace only ever contains accepted work.
 */
final class NodeRunner {

  private final String runId;
  private final ArtifactStore artifacts;
  private final Workspace workspace;
  private final PolicyEngine policy;
  private final ApprovalGateway approvals;
  private final EventLog events;
  private final Clock clock;
  private final StopController stop;
  private final ReentrantLock workspaceLock;
  private final AtomicInteger attempts;
  private final AtomicInteger agentCalls;
  private final ExecutorService taskExecutor;
  private final Runnable budgetCheck;

  NodeRunner(String runId, ArtifactStore artifacts, Workspace workspace, PolicyEngine policy,
      ApprovalGateway approvals, EventLog events, Clock clock, StopController stop,
      ReentrantLock workspaceLock, AtomicInteger attempts, AtomicInteger agentCalls,
      ExecutorService taskExecutor, Runnable budgetCheck) {
    this.runId = runId;
    this.artifacts = artifacts;
    this.workspace = workspace;
    this.policy = policy;
    this.approvals = approvals;
    this.events = events;
    this.clock = clock;
    this.stop = stop;
    this.workspaceLock = workspaceLock;
    this.attempts = attempts;
    this.agentCalls = agentCalls;
    this.taskExecutor = taskExecutor;
    this.budgetCheck = budgetCheck;
  }

  NodeOutcome run(NodeSpec node) {
    try {
      return runGoverned(node);
    } catch (RuntimeException e) {
      String reason = "engine error: " + e;
      events.append(new RunEvent.NodeFailed(node.id(), node.stage().name(), reason));
      return NodeOutcome.failed(node.id(), reason);
    }
  }

  private NodeOutcome runGoverned(NodeSpec node) {
    GateContext entryContext = new GateContext(node, artifacts, Map.of(), workspaceRoot());
    for (Gate gate : node.entryGates()) {
      GateResult result = evaluate(gate, entryContext);
      events.append(new RunEvent.GateEvaluated(node.id(), 0, "entry", result.gate(),
          result.passed(), result.evidence()));
      if (!result.passed()) {
        String reason = "entry gate '" + gate.name() + "' failed: " + result.evidence();
        events.append(new RunEvent.NodeFailed(node.id(), node.stage().name(), reason));
        return NodeOutcome.failed(node.id(), reason);
      }
    }

    List<String> feedback = new ArrayList<>();
    List<Strategy> strategies = new ArrayList<>(List.of(new Strategy("primary", node.task())));
    if (node.fallback() != null) {
      strategies.add(new Strategy("fallback", node.fallback()));
    }
    int attempt = 0;
    String lastReason = "";
    for (Strategy strategy : strategies) {
      if (strategy.name().equals("fallback")) {
        events.append(new RunEvent.FallbackActivated(node.id(),
            "primary strategy exhausted after " + node.retry().maxAttempts() + " attempts"));
      }
      for (int i = 1; i <= node.retry().maxAttempts(); i++) {
        budgetCheck.run();
        if (stop.isStopRequested()) {
          return NodeOutcome.stopped(node.id(), stop.reason());
        }
        attempt++;
        attempts.incrementAndGet();
        events.append(new RunEvent.NodeStarted(node.id(), node.stage().name(), attempt,
            strategy.name()));
        AttemptResult result = attempt(node, strategy, attempt, feedback);
        if (result.outcome() != null) {
          return result.outcome();
        }
        lastReason = result.reason();
        events.append(new RunEvent.AttemptFailed(node.id(), attempt, result.reason(),
            result.feedback()));
        feedback.add("Attempt " + attempt + " was rejected (" + result.reason() + "):\n"
            + result.feedback());
        if (i < node.retry().maxAttempts()) {
          sleep(node.retry().backoffAfter(i));
        }
      }
    }
    String reason = "gave up after " + attempt + " attempts; last: " + lastReason;
    events.append(new RunEvent.NodeFailed(node.id(), node.stage().name(), reason));
    return NodeOutcome.failed(node.id(), reason);
  }

  private AttemptResult attempt(NodeSpec node, Strategy strategy, int attempt,
      List<String> feedback) {
    NodeContext context = new NodeContext(runId, node, attempt, strategy.name(), feedback,
        artifacts, workspaceRoot(), events, agentCalls, stop);
    NodeResult result;
    try {
      result = executeWithTimeout(strategy.task(), context, node.timeout());
    } catch (TimeoutException e) {
      return AttemptResult.rejected("timeout", "task exceeded its " + node.timeout() + " limit");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      return AttemptResult.rejected("task-error", cause.getClass().getSimpleName() + ": "
          + cause.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return AttemptResult.rejected("interrupted", "attempt interrupted");
    }

    List<PolicyFinding> findings = policy.evaluate(
        new PolicyInput(node, result.changes(), result.outputs(), workspaceRoot()));
    if (!findings.isEmpty()) {
      events.append(new RunEvent.PolicyEvaluated(node.id(), attempt, findings));
    }
    if (PolicyEngine.blocks(findings)) {
      return AttemptResult.rejected("policy-violation", describe(findings, Severity.BLOCK));
    }
    boolean mutates = !result.changes().isEmpty();
    if (mutates && workspace == null) {
      return AttemptResult.rejected("no-workspace", "node proposed file changes but the run "
          + "has no workspace");
    }

    boolean needsLock = mutates || node.exitGates().stream().anyMatch(Gate::usesWorkspace);
    if (needsLock) {
      workspaceLock.lock();
    }
    try {
      return gateApproveAndAccept(node, attempt, result, findings, context, mutates);
    } finally {
      if (needsLock) {
        workspaceLock.unlock();
      }
    }
  }

  private AttemptResult gateApproveAndAccept(NodeSpec node, int attempt, NodeResult result,
      List<PolicyFinding> findings, NodeContext context, boolean mutates) {
    String base = mutates ? workspace.head() : null;
    if (mutates) {
      workspace.apply(result.changes());
    }

    GateContext exitContext = new GateContext(node, artifacts, result.outputs(), workspaceRoot());
    List<GateResult> gateResults = new ArrayList<>();
    for (Gate gate : node.exitGates()) {
      GateResult gateResult = evaluate(gate, exitContext);
      gateResults.add(gateResult);
      events.append(new RunEvent.GateEvaluated(node.id(), attempt, "exit", gateResult.gate(),
          gateResult.passed(), gateResult.evidence()));
      if (!gateResult.passed()) {
        rollback(node, base, "exit gate '" + gate.name() + "' failed");
        return AttemptResult.rejected("gate-failed:" + gate.name(), gateResult.evidence());
      }
    }

    ApprovalDecision decision = null;
    List<String> reasons = approvalReasons(node, findings);
    if (!reasons.isEmpty()) {
      events.append(new RunEvent.ApprovalRequested(node.id(), reasons));
      decision = approvals.decide(new ApprovalRequest(runId, node.id(), node.stage(),
          result.summary(), reasons, findings, result.outputs(),
          mutates ? workspace.diff(base) : "", gateResults));
      events.append(new RunEvent.ApprovalResolved(node.id(), decision.approved(),
          decision.reviewer(), decision.comment(), List.copyOf(decision.amendments().keySet())));
      if (!decision.approved()) {
        rollback(node, base, "rejected by reviewer " + decision.reviewer());
        return AttemptResult.rejected("rejected-by-reviewer",
            "Reviewer " + decision.reviewer() + " rejected the proposal: " + decision.comment());
      }
    }

    if (mutates) {
      String checkpoint = workspace.checkpoint(node.id() + " (attempt " + attempt + ")");
      events.append(new RunEvent.CheckpointCreated(node.id(), checkpoint));
    }
    return AttemptResult.accepted(accept(node, attempt, result, decision, context));
  }

  private NodeOutcome accept(NodeSpec node, int attempt, NodeResult result,
      ApprovalDecision decision, NodeContext context) {
    Set<String> updated = new LinkedHashSet<>();
    Map<String, String> outputRefs = new LinkedHashMap<>();
    result.outputs().forEach((key, content) ->
        outputRefs.put(key, store(key, content, node.id(), updated).ref()));
    if (decision != null) {
      decision.amendments().forEach((key, content) ->
          outputRefs.put(key, store(key, content, "human:" + decision.reviewer(), updated).ref()));
    }
    Map<String, Artifact> consumed = context.consumed();
    List<String> lineage = consumed.values().stream().map(Artifact::ref).toList();
    for (Decision d : result.decisions()) {
      events.append(new RunEvent.DecisionRecorded(node.id(), d.title(), d.choice(),
          d.rationale(), d.alternatives(), lineage));
    }
    Map<String, String> inputs = consumed.entrySet().stream().collect(Collectors.toMap(
        Map.Entry::getKey, e -> e.getValue().sha256(), (a, b) -> a, LinkedHashMap::new));
    events.append(new RunEvent.NodeSucceeded(node.id(), node.stage().name(), attempt, inputs,
        outputRefs));
    return new NodeOutcome(node.id(), NodeOutcome.Kind.SUCCEEDED, "", inputs, updated);
  }

  private Artifact store(String key, String content, String producer, Set<String> updated) {
    int before = artifacts.latest(key).map(Artifact::version).orElse(0);
    Artifact artifact = artifacts.put(key, content, producer, clock.instant());
    if (artifact.version() != before) {
      updated.add(key);
      events.append(new RunEvent.ArtifactProduced(key, artifact.version(), artifact.sha256(),
          producer, ArtifactStore.relativePath(artifact)));
    }
    return artifact;
  }

  private List<String> approvalReasons(NodeSpec node, List<PolicyFinding> findings) {
    List<String> reasons = new ArrayList<>();
    if (node.approval() == ApprovalMode.ALWAYS) {
      reasons.add("mandatory " + node.stage().name().toLowerCase() + " sign-off");
    }
    findings.stream()
        .filter(f -> f.severity() == Severity.REQUIRE_APPROVAL)
        .map(f -> f.rule() + ": " + f.message() + (f.path() == null ? "" : " (" + f.path() + ")"))
        .forEach(reasons::add);
    return reasons;
  }

  private void rollback(NodeSpec node, String base, String reason) {
    if (base == null) {
      return;
    }
    workspace.rollbackTo(base);
    events.append(new RunEvent.RollbackPerformed(node.id(), base, reason));
  }

  private NodeResult executeWithTimeout(NodeTask task, NodeContext context, Duration timeout)
      throws TimeoutException, ExecutionException, InterruptedException {
    Future<NodeResult> future = taskExecutor.submit(() -> task.execute(context));
    try {
      NodeResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (result == null) {
        throw new ExecutionException(new IllegalStateException("task returned no result"));
      }
      return result;
    } catch (TimeoutException e) {
      future.cancel(true);
      throw e;
    }
  }

  private static GateResult evaluate(Gate gate, GateContext context) {
    try {
      return gate.evaluate(context);
    } catch (RuntimeException e) {
      return GateResult.fail(gate.name(), "gate error (fail-closed): " + e);
    }
  }

  private static String describe(List<PolicyFinding> findings, Severity severity) {
    return findings.stream()
        .filter(f -> f.severity() == severity)
        .map(f -> "- [" + f.rule() + "] " + f.message() + (f.path() == null ? "" : ": " + f.path()))
        .collect(Collectors.joining("\n"));
  }

  private Path workspaceRoot() {
    return workspace == null ? null : workspace.root();
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private record Strategy(String name, NodeTask task) {}

  private record AttemptResult(NodeOutcome outcome, String reason, String feedback) {
    static AttemptResult accepted(NodeOutcome outcome) {
      return new AttemptResult(outcome, null, null);
    }

    static AttemptResult rejected(String reason, String feedback) {
      return new AttemptResult(null, reason, feedback);
    }
  }
}
