package io.github.marishasahay.agentic.core.engine;

import io.github.marishasahay.agentic.core.approval.AmendmentValidator;
import io.github.marishasahay.agentic.core.approval.ApprovalDecision;
import io.github.marishasahay.agentic.core.approval.ApprovalGateway;
import io.github.marishasahay.agentic.core.approval.ApprovalKind;
import io.github.marishasahay.agentic.core.approval.ApprovalRequest;
import io.github.marishasahay.agentic.core.approval.ReviewerUnavailableException;
import io.github.marishasahay.agentic.core.artifact.Artifact;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.event.AuditUnavailableException;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.Json;
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
import io.github.marishasahay.agentic.core.util.Hashing;
import io.github.marishasahay.agentic.core.workspace.ChangeSet;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import io.github.marishasahay.agentic.core.workspace.Workspace;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Executes one node through its full governed lifecycle:
 *
 * <pre>
 * entry gates -> [attempt: task (on a snapshot) -> policy -> authorization? -> lock
 *                 -> stale check -> apply -> exit gates -> acceptance? -> publish]*
 *             -> fallback strategy -> fail
 * </pre>
 *
 * <p>Invariants:
 *
 * <ul>
 *   <li>Nothing a proposal contains is written, built or executed before every
 *       execution-sensitive change in it has been authorized by a human for that exact
 *       proposal.</li>
 *   <li>Acceptance is decided on the exact tree the exit gates validated; the tree is checked
 *       again before the checkpoint is committed.</li>
 *   <li>The workspace only ever contains accepted work outside the lock: any failure between
 *       apply and the success event (gate, reviewer, exception, audit write) restores the
 *       accepted checkpoint, and a failed restore quarantines the workspace and stops the
 *       run.</li>
 *   <li>Work whose inputs or base files changed while it ran is discarded, not published.</li>
 * </ul>
 */
final class NodeRunner {

  static final int MAX_AMENDMENT_ROUNDS = 3;

  private final String runId;
  private final ArtifactStore artifacts;
  private final Workspace workspace;
  private final PolicyEngine policy;
  private final ApprovalGateway approvals;
  private final AmendmentValidator amendmentValidator;
  private final EventLog events;
  private final Clock clock;
  private final StopController stop;
  private final Consumer<String> requestStop;
  private final ReentrantLock workspaceLock;
  private final ReentrantLock publishLock;
  private final Map<String, AtomicInteger> generations;
  private final AtomicInteger attempts;
  private final AtomicInteger agentCalls;
  private final ExecutorService taskExecutor;
  private final Runnable budgetCheck;
  private final Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();
  private final AtomicBoolean quarantined = new AtomicBoolean();

  NodeRunner(Shared shared, ApprovalGateway approvals, AmendmentValidator amendmentValidator,
      PolicyEngine policy, ExecutorService taskExecutor, Runnable budgetCheck) {
    this.runId = shared.runId();
    this.artifacts = shared.artifacts();
    this.workspace = shared.workspace();
    this.events = shared.events();
    this.clock = shared.clock();
    this.stop = shared.stop();
    this.requestStop = shared.requestStop();
    this.workspaceLock = shared.workspaceLock();
    this.publishLock = shared.publishLock();
    this.generations = shared.generations();
    this.attempts = shared.attempts();
    this.agentCalls = shared.agentCalls();
    this.policy = policy;
    this.approvals = approvals;
    this.amendmentValidator = amendmentValidator;
    this.taskExecutor = taskExecutor;
    this.budgetCheck = budgetCheck;
  }

  /**
   * State the scheduler and the runners share.
   *
   * @param runId run id
   * @param artifacts artifact store
   * @param workspace workspace, or null
   * @param events audit log
   * @param clock time source
   * @param stop stop switch
   * @param requestStop engages a safe stop and records it
   * @param workspaceLock serialises apply, gates, acceptance and checkpoint
   * @param publishLock makes "still current? then publish" atomic against invalidation
   * @param generations per-node counter bumped when a running node is invalidated
   * @param attempts attempt budget counter
   * @param agentCalls model-call budget counter
   */
  record Shared(String runId, ArtifactStore artifacts, Workspace workspace, EventLog events,
      Clock clock, StopController stop, Consumer<String> requestStop,
      ReentrantLock workspaceLock, ReentrantLock publishLock,
      Map<String, AtomicInteger> generations, AtomicInteger attempts,
      AtomicInteger agentCalls) {}

  /**
   * Seeds execution counters from a resumed run's history so replay keys stay unique.
   *
   * @param counts node id to executions already performed
   */
  void restoreExecutions(Map<String, Integer> counts) {
    counts.forEach((id, n) -> executions.put(id, new AtomicInteger(n)));
  }

  NodeOutcome run(NodeSpec node) {
    int generation = generations.computeIfAbsent(node.id(), k -> new AtomicInteger()).get();
    try {
      return runGoverned(node,
          executions.computeIfAbsent(node.id(), k -> new AtomicInteger()).incrementAndGet(),
          generation);
    } catch (AuditUnavailableException e) {
      requestStop.accept("audit log unavailable: " + e.getMessage());
      return NodeOutcome.stopped(node.id(), "audit log unavailable");
    } catch (WorkspaceQuarantinedException e) {
      return NodeOutcome.stopped(node.id(), e.getMessage());
    } catch (RuntimeException e) {
      String reason = "engine error: " + e;
      events.append(new RunEvent.NodeFailed(node.id(), node.stage().name(), reason));
      return NodeOutcome.failed(node.id(), reason);
    }
  }

  private NodeOutcome runGoverned(NodeSpec node, int execution, int generation) {
    GateContext entryContext = new GateContext(node, artifacts, Map.of(), workspaceRoot());
    for (Gate gate : node.entryGates()) {
      GateResult result = evaluate(gate, entryContext);
      events.append(new RunEvent.GateEvaluated(node.id(), 0, "entry", result.gate(),
          result.passed(), result.evidence(), null));
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
        AttemptResult result = attempt(node, strategy, execution, attempt, feedback,
            generation);
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

  private AttemptResult attempt(NodeSpec node, Strategy strategy, int execution, int attempt,
      List<String> feedback, int generation) {
    String base = workspace == null ? null : workspace.head();
    NodeContext context = new NodeContext(runId, node, execution, attempt, strategy.name(),
        feedback, artifacts, workspace, base, events, agentCalls, stop);
    NodeResult result;
    try {
      result = executeWithTimeout(strategy.task(), context, node.timeout());
    } catch (TimeoutException e) {
      return AttemptResult.rejected("timeout", "task exceeded its " + node.timeout() + " limit");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof AuditUnavailableException audit) {
        throw audit;
      }
      return AttemptResult.rejected("task-error", cause.getClass().getSimpleName() + ": "
          + cause.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return AttemptResult.rejected("interrupted", "attempt interrupted");
    } finally {
      context.close();
    }

    List<PolicyFinding> findings = policy.evaluate(
        new PolicyInput(node, result.changes(), result.outputs(), baseFiles(base)));
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
    String proposalSha = proposalSha(result);

    List<PolicyFinding> executionSensitive = findings.stream()
        .filter(f -> f.severity() == Severity.REQUIRE_AUTHORIZATION).toList();
    if (!executionSensitive.isEmpty()) {
      ApprovalDecision authorization;
      try {
        authorization = requestDecision(new ApprovalRequest(runId, node.id(), node.stage(),
            ApprovalKind.AUTHORIZATION, result.summary(),
            executionSensitive.stream().map(NodeRunner::describe).toList(),
            executionSensitive.stream().map(PolicyFinding::rule)
                .collect(Collectors.toCollection(TreeSet::new)),
            findings, result.outputs(), workspace.previewDiff(base, result.changes()),
            List.of(), proposalSha, null, List.of()), node, result);
      } catch (ReviewerUnavailableException e) {
        return pause(node, e);
      }
      if (!authorization.approved()) {
        return AttemptResult.rejected("authorization-denied", "Reviewer "
            + authorization.reviewer() + " did not authorize executing the proposal: "
            + authorization.comment());
      }
    }

    boolean needsWorkspaceLock = mutates
        || workspace != null && node.exitGates().stream().anyMatch(Gate::usesWorkspace);
    if (needsWorkspaceLock) {
      workspaceLock.lock();
    }
    try {
      return validateApproveAndPublish(node, attempt, result, findings, context, base,
          proposalSha, generation, mutates, needsWorkspaceLock);
    } finally {
      if (needsWorkspaceLock) {
        workspaceLock.unlock();
      }
    }
  }

  private AttemptResult validateApproveAndPublish(NodeSpec node, int attempt, NodeResult result,
      List<PolicyFinding> findings, NodeContext context, String base, String proposalSha,
      int generation, boolean mutates, boolean holdsWorkspace) {
    if (quarantined.get()) {
      throw new WorkspaceQuarantinedException("workspace quarantined after a failed rollback; "
          + "no further changes are applied in this run");
    }
    String accepted = null;
    if (mutates) {
      accepted = workspace.head();
      if (!accepted.equals(base)) {
        Set<String> touched = new HashSet<>(result.changes().paths());
        List<String> moved = workspace.changedBetween(base, accepted).stream()
            .filter(touched::contains).toList();
        if (!moved.isEmpty()) {
          return AttemptResult.rejected("stale-proposal", "accepted work changed " + moved
              + " after this proposal's snapshot (" + base.substring(0, 12) + "); start from "
              + "the current files");
        }
      }
    }
    try {
      if (mutates) {
        workspace.apply(result.changes());
      }
      // The tree this attempt is judged on: the validated working tree when the node holds the
      // workspace, otherwise the snapshot it read.
      String tree = holdsWorkspace ? workspace.treeHash()
          : base == null ? null : workspace.treeOf(base);
      GateContext exitContext = new GateContext(node, artifacts, result.outputs(),
          workspaceRoot());
      List<GateResult> gateResults = new ArrayList<>();
      for (Gate gate : node.exitGates()) {
        GateResult gateResult = evaluate(gate, exitContext);
        gateResults.add(gateResult);
        events.append(new RunEvent.GateEvaluated(node.id(), attempt, "exit", gateResult.gate(),
            gateResult.passed(), gateResult.evidence(), tree));
        if (!gateResult.passed()) {
          restore(node, accepted, "exit gate '" + gate.name() + "' failed");
          return AttemptResult.rejected("gate-failed:" + gate.name(), gateResult.evidence());
        }
      }

      Map<String, String> reasons = acceptanceReasons(node, findings, result);
      ApprovalDecision decision = null;
      if (!reasons.isEmpty()) {
        decision = requestDecision(new ApprovalRequest(runId, node.id(), node.stage(),
            ApprovalKind.ACCEPTANCE, result.summary(), List.copyOf(reasons.values()),
            reasons.keySet(), findings, result.outputs(),
            mutates ? workspace.diff(accepted) : "", gateResults, proposalSha, tree,
            List.of()), node, result);
        if (!decision.approved()) {
          restore(node, accepted, "rejected by reviewer " + decision.reviewer());
          return AttemptResult.rejected("rejected-by-reviewer",
              "Reviewer " + decision.reviewer() + " rejected the proposal: "
                  + decision.comment());
        }
      }
      if (holdsWorkspace && !tree.equals(workspace.treeHash())) {
        restore(node, accepted, "workspace changed between validation and acceptance");
        return AttemptResult.rejected("tree-changed", "the workspace no longer matches the "
            + "validated tree " + tree.substring(0, 12) + " (a gate or a concurrent writer "
            + "modified files)");
      }

      publishLock.lock();
      try {
        String stale = staleness(node, context, generation);
        if (stale != null) {
          restore(node, accepted, "discarded: " + stale);
          events.append(new RunEvent.AttemptDiscarded(node.id(), attempt, stale));
          return AttemptResult.finished(NodeOutcome.discarded(node.id(), stale));
        }
        String checkpoint = null;
        if (mutates) {
          checkpoint = workspace.checkpoint(node.id() + " (attempt " + attempt + ")");
          events.append(new RunEvent.CheckpointCreated(node.id(), checkpoint));
        }
        return AttemptResult.finished(publish(node, attempt, result, decision, context,
            checkpoint, tree));
      } finally {
        publishLock.unlock();
      }
    } catch (ReviewerUnavailableException e) {
      restore(node, accepted, "reviewer unavailable: " + e.getMessage());
      return pause(node, e);
    } catch (AuditUnavailableException e) {
      restoreUnaudited(accepted);
      throw e;
    } catch (WorkspaceQuarantinedException e) {
      throw e;
    } catch (RuntimeException e) {
      restore(node, accepted, "attempt aborted: " + e);
      return AttemptResult.rejected("acceptance-error", e.toString());
    }
  }

  /**
   * Publishes the accepted outputs. Artifacts are persisted first, then their events, then the
   * success event, which is the commit point: resume treats nothing as accepted without it. If
   * anything fails before it, the stored versions are withdrawn again.
   */
  private NodeOutcome publish(NodeSpec node, int attempt, NodeResult result,
      ApprovalDecision decision, NodeContext context, String checkpoint, String tree) {
    Map<String, String> outputRefs = new LinkedHashMap<>();
    Set<String> updated = new LinkedHashSet<>();
    List<Artifact> stored = new ArrayList<>();
    try {
      result.outputs().forEach((key, content) ->
          outputRefs.put(key, store(key, content, node.id(), stored).ref()));
      if (decision != null) {
        decision.amendments().forEach((key, content) -> outputRefs.put(key,
            store(key, content, "reviewer:" + decision.reviewer(), stored).ref()));
      }
      for (Artifact artifact : stored) {
        updated.add(artifact.key());
        events.append(new RunEvent.ArtifactProduced(artifact.key(), artifact.version(),
            artifact.sha256(), artifact.producedBy(), ArtifactStore.relativePath(artifact)));
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
          outputRefs, checkpoint, tree));
      return new NodeOutcome(node.id(), NodeOutcome.Kind.SUCCEEDED, "", inputs, updated);
    } catch (RuntimeException e) {
      stored.forEach(artifacts::discard);
      throw e;
    }
  }

  private Artifact store(String key, String content, String producer, List<Artifact> stored) {
    int before = artifacts.latest(key).map(Artifact::version).orElse(0);
    Artifact artifact = artifacts.put(key, content, producer, clock.instant());
    if (artifact.version() != before) {
      stored.add(artifact);
    }
    return artifact;
  }

  /**
   * Asks the gateway, re-asking (bounded) when an approval carries amendments that fail
   * validation. A gateway that cannot decide, or fails, never counts as approval.
   */
  private ApprovalDecision requestDecision(ApprovalRequest request, NodeSpec node,
      NodeResult result) {
    List<String> problems = List.of();
    for (int round = 1; ; round++) {
      ApprovalRequest asked = problems.isEmpty() ? request : request.withProblems(problems);
      events.append(new RunEvent.ApprovalRequested(node.id(), asked.reasons(),
          asked.kind().name(), List.copyOf(new TreeSet<>(asked.reasonCodes())),
          asked.proposalSha(), asked.treeSha()));
      ApprovalDecision decision;
      try {
        decision = approvals.decide(asked);
      } catch (ReviewerUnavailableException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new ReviewerUnavailableException("approval gateway failed: " + e.getMessage());
      }
      events.append(new RunEvent.ApprovalResolved(node.id(), decision.approved(),
          decision.reviewer(), decision.comment(), List.copyOf(decision.amendments().keySet()),
          asked.kind().name(), asked.proposalSha(), asked.treeSha()));
      problems = decision.approved() ? amendmentProblems(asked, decision, node, result)
          : List.of();
      if (problems.isEmpty()) {
        return decision;
      }
      events.append(new RunEvent.AmendmentRejected(node.id(),
          List.copyOf(decision.amendments().keySet()), problems));
      if (round >= MAX_AMENDMENT_ROUNDS) {
        throw new ReviewerUnavailableException("reviewer amendments failed validation " + round
            + " times: " + problems);
      }
    }
  }

  private List<String> amendmentProblems(ApprovalRequest request, ApprovalDecision decision,
      NodeSpec node, NodeResult result) {
    if (decision.amendments().isEmpty()) {
      return List.of();
    }
    List<String> problems = new ArrayList<>();
    if (request.kind() == ApprovalKind.AUTHORIZATION) {
      problems.add("an authorization cannot amend artifacts; authorize or refuse the proposal "
          + "as it is");
    }
    new TreeMap<>(decision.amendments()).forEach((key, content) -> {
      if (artifacts.latest(key).isEmpty() && !result.outputs().containsKey(key)) {
        problems.add("unknown artifact '" + key + "' (amendments may only replace an existing "
            + "artifact or one this node proposes)");
      } else {
        amendmentValidator.validate(key, content).forEach(p -> problems.add(key + ": " + p));
      }
    });
    policy.evaluate(new PolicyInput(node, ChangeSet.empty(), decision.amendments(),
            PolicyInput.BaseFiles.directory(null))).stream()
        .filter(f -> f.severity() == Severity.BLOCK)
        .forEach(f -> problems.add("policy " + describe(f)));
    return problems;
  }

  /** Why accepting this result needs a human, as reason code to message. */
  private static Map<String, String> acceptanceReasons(NodeSpec node,
      List<PolicyFinding> findings, NodeResult result) {
    Map<String, String> reasons = new LinkedHashMap<>();
    if (node.approval() == ApprovalMode.ALWAYS) {
      reasons.put("sign-off", "mandatory " + node.stage().name().toLowerCase().replace('_', ' ')
          + " sign-off");
    }
    findings.stream()
        .filter(f -> f.severity() == Severity.REQUIRE_APPROVAL)
        .forEach(f -> reasons.merge(f.rule(), describe(f), (a, b) -> a + "; " + b));
    result.approvalRequirements().forEach((code, message) ->
        reasons.merge(code, code + ": " + message, (a, b) -> a + "; " + b));
    return reasons;
  }

  /** Why this attempt's result may no longer be published, or null if it is still current. */
  private String staleness(NodeSpec node, NodeContext context, int generation) {
    if (generations.get(node.id()).get() != generation) {
      return "upstream work this node depends on was invalidated while it ran";
    }
    for (Map.Entry<String, Artifact> input : context.consumed().entrySet()) {
      String current = artifacts.latest(input.getKey()).map(Artifact::sha256).orElse(null);
      if (!input.getValue().sha256().equals(current)) {
        return "input '" + input.getKey() + "' changed while the attempt ran ("
            + input.getValue().ref() + " is no longer the latest version)";
      }
    }
    return null;
  }

  private AttemptResult pause(NodeSpec node, ReviewerUnavailableException e) {
    requestStop.accept("reviewer unavailable for " + node.id() + ": " + e.getMessage());
    return AttemptResult.finished(NodeOutcome.stopped(node.id(), e.getMessage()));
  }

  /**
   * Restores the accepted checkpoint and records why. If the restore itself fails, the
   * workspace is quarantined and the run stops: continuing would build on unaccepted changes.
   */
  private void restore(NodeSpec node, String checkpoint, String cause) {
    if (checkpoint == null) {
      return;
    }
    try {
      workspace.rollbackTo(checkpoint);
    } catch (RuntimeException rollbackError) {
      quarantined.set(true);
      events.append(new RunEvent.RollbackFailed(node.id(), checkpoint, cause,
          rollbackError.toString()));
      requestStop.accept("rollback of " + node.id() + " failed; workspace quarantined");
      throw new WorkspaceQuarantinedException("rollback to " + checkpoint + " failed after: "
          + cause + " (" + rollbackError.getMessage() + ")");
    }
    events.append(new RunEvent.RollbackPerformed(node.id(), checkpoint, cause));
  }

  /** Restore when the audit log is down: best effort, nothing can be recorded. */
  private void restoreUnaudited(String checkpoint) {
    if (checkpoint == null) {
      return;
    }
    try {
      workspace.rollbackTo(checkpoint);
    } catch (RuntimeException e) {
      quarantined.set(true);
    }
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

  private PolicyInput.BaseFiles baseFiles(String base) {
    return workspace == null || base == null ? PolicyInput.BaseFiles.directory(null)
        : path -> workspace.readAt(base, path);
  }

  /** Identity of a proposal: every file change and every output, by content hash. */
  static String proposalSha(NodeResult result) {
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("changes", result.changes().changes().stream()
        .map(c -> c.operation() + " " + c.path() + " "
            + (c.operation() == FileChange.Operation.DELETE ? "" : Hashing.sha256(c.content())))
        .toList());
    Map<String, String> outputs = new TreeMap<>();
    result.outputs().forEach((key, value) -> outputs.put(key, Hashing.sha256(value)));
    canonical.put("outputs", outputs);
    return Hashing.sha256(Json.write(canonical));
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

  private static String describe(PolicyFinding f) {
    return f.rule() + ": " + f.message() + (f.path() == null ? "" : " (" + f.path() + ")");
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
    static AttemptResult finished(NodeOutcome outcome) {
      return new AttemptResult(outcome, null, null);
    }

    static AttemptResult rejected(String reason, String feedback) {
      return new AttemptResult(null, reason, feedback);
    }
  }
}
