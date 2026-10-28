package io.github.marishasahay.agentic.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.agentic.core.approval.ApprovalDecision;
import io.github.marishasahay.agentic.core.approval.ScriptedApprovalGateway;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.event.EventEnvelope;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.gate.GateResult;
import io.github.marishasahay.agentic.core.gate.RequiredArtifactsGate;
import io.github.marishasahay.agentic.core.graph.ApprovalMode;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.RetryPolicy;
import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.graph.WorkflowGraph;
import io.github.marishasahay.agentic.core.metrics.MetricsCalculator;
import io.github.marishasahay.agentic.core.metrics.RunMetrics;
import io.github.marishasahay.agentic.core.policy.PolicyEngine;
import io.github.marishasahay.agentic.core.policy.rules.SchemaMigrationRule;
import io.github.marishasahay.agentic.core.policy.rules.WorkspaceScopeRule;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import io.github.marishasahay.agentic.core.workspace.GitWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowEngineTest {

  private static final RetryPolicy FAST_RETRY =
      new RetryPolicy(3, Duration.ofMillis(1), 1.0, Duration.ofMillis(1));

  @TempDir
  Path tmp;

  private EventLog log = EventLog.inMemory("run-test", Clock.systemUTC());
  private ArtifactStore artifacts = ArtifactStore.inMemory();

  private WorkflowEngine.Builder engine() {
    return WorkflowEngine.builder().events(log).artifacts(artifacts)
        .approvals(ScriptedApprovalGateway.approveAll());
  }

  private static NodeSpec.Builder node(String id, String... deps) {
    return NodeSpec.builder(id, Stage.IMPLEMENTATION).dependsOn(deps).retry(FAST_RETRY);
  }

  private static NodeTask output(String key, String value) {
    return ctx -> NodeResult.builder().output(key, value).build();
  }

  private <T extends RunEvent> List<T> eventsOf(Class<T> type) {
    return log.events().stream().map(EventEnvelope::event).filter(type::isInstance)
        .map(type::cast).toList();
  }

  @Test
  void runsIndependentNodesInParallelAndJoinsOnBoth() {
    CountDownLatch bothRunning = new CountDownLatch(2);
    NodeTask awaitPeer = ctx -> {
      bothRunning.countDown();
      boolean peerRan = bothRunning.await(5, TimeUnit.SECONDS);
      return NodeResult.builder().output(ctx.node().id() + ".out", String.valueOf(peerRan))
          .build();
    };
    WorkflowGraph graph = WorkflowGraph.of(List.of(
        node("left").task(awaitPeer).build(),
        node("right").task(awaitPeer).build(),
        node("join", "left", "right")
            .entryGate(RequiredArtifactsGate.of("left.out", "right.out"))
            .task(ctx -> NodeResult.builder()
                .output("joined", ctx.input("left.out") + "+" + ctx.input("right.out")).build())
            .build()));

    RunOutcome outcome = engine().build().run(graph, "parallel", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(artifacts.content("joined")).isEqualTo("true+true");
  }

  @Test
  void retriesWithFailureEvidenceUntilTheTaskSucceeds() {
    List<List<String>> feedbackSeen = new CopyOnWriteArrayList<>();
    NodeTask flaky = ctx -> {
      feedbackSeen.add(ctx.feedback());
      if (ctx.attempt() < 3) {
        throw new IllegalStateException("compile error #" + ctx.attempt());
      }
      return NodeResult.builder().output("code", "fixed").build();
    };
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("impl").task(flaky).build()));

    RunOutcome outcome = engine().build().run(graph, "retry", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(feedbackSeen.get(0)).isEmpty();
    assertThat(feedbackSeen.get(2)).hasSize(2);
    assertThat(feedbackSeen.get(2).get(1)).contains("compile error #2");
    RunMetrics metrics = MetricsCalculator.compute(log.events());
    assertThat(metrics.retries()).isEqualTo(2);
    assertThat(metrics.recoveredFailures()).isEqualTo(1);
    assertThat(metrics.mttrMs()).isGreaterThanOrEqualTo(0);
    assertThat(metrics.firstPassYield()).isZero();
  }

  @Test
  void switchesToTheFallbackStrategyWhenThePrimaryIsExhausted() {
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("docs")
        .task(ctx -> {
          throw new IllegalStateException("model unavailable");
        })
        .fallback(ctx -> NodeResult.builder().output("readme", "template").build())
        .build()));

    RunOutcome outcome = engine().build().run(graph, "fallback", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(eventsOf(RunEvent.FallbackActivated.class)).hasSize(1);
    assertThat(eventsOf(RunEvent.NodeStarted.class)).extracting(RunEvent.NodeStarted::strategy)
        .containsExactly("primary", "primary", "primary", "fallback");
  }

  @Test
  void rollsBackTheWorkspaceWhenAnExitGateRejectsAnAttempt() throws Exception {
    GitWorkspace workspace = GitWorkspace.createFrom(null, tmp.resolve("ws"));
    String baseline = workspace.head();
    NodeTask coder = ctx -> NodeResult.builder()
        .change(FileChange.write("src/App.java", ctx.isRetry() ? "class App {}" : "class App {"))
        .change(FileChange.write(ctx.isRetry() ? "src/Ok.java" : "src/Junk.java", "x"))
        .build();
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("impl").task(coder)
        .exitGate(new CompilesGate())
        .build()));

    RunOutcome outcome = engine().workspace(workspace).build().run(graph, "rollback", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(eventsOf(RunEvent.RollbackPerformed.class)).singleElement()
        .extracting(RunEvent.RollbackPerformed::toCheckpoint).isEqualTo(baseline);
    assertThat(workspace.root().resolve("src/Junk.java")).doesNotExist();
    assertThat(Files.readString(workspace.root().resolve("src/App.java")))
        .isEqualTo("class App {}");
    assertThat(workspace.head()).isNotEqualTo(baseline);
    assertThat(eventsOf(RunEvent.CheckpointCreated.class)).hasSize(1);
  }

  @Test
  void blocksGuardrailViolationsBeforeAnythingIsWritten() throws Exception {
    GitWorkspace workspace = GitWorkspace.createFrom(null, tmp.resolve("ws"));
    PolicyEngine policy = new PolicyEngine(List.of(
        new WorkspaceScopeRule(List.of("src/**"), List.of(".github/**"))));
    NodeTask agent = ctx -> NodeResult.builder()
        .change(ctx.isRetry() ? FileChange.write("src/Feature.java", "class Feature {}")
            : FileChange.write(".github/workflows/ci.yml", "on: never"))
        .build();
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("impl").task(agent).build()));

    RunOutcome outcome = engine().workspace(workspace).policy(policy).build()
        .run(graph, "guardrail", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(workspace.root().resolve(".github")).doesNotExist();
    assertThat(eventsOf(RunEvent.AttemptFailed.class)).singleElement()
        .satisfies(f -> assertThat(f.reason()).isEqualTo("policy-violation"))
        .satisfies(f -> assertThat(f.feedback()).contains("governance file is protected"));
    assertThat(eventsOf(RunEvent.RollbackPerformed.class)).isEmpty();
  }

  @Test
  void routesHighImpactChangesToAHumanAndFeedsRejectionsBack() throws Exception {
    GitWorkspace workspace = GitWorkspace.createFrom(null, tmp.resolve("ws"));
    ScriptedApprovalGateway reviewer = new ScriptedApprovalGateway(
        ApprovalDecision.approve("alice", "looks good"))
        .script("migrate", ApprovalDecision.reject("alice", "add an index on code"));
    List<List<String>> feedbackSeen = new CopyOnWriteArrayList<>();
    NodeTask migration = ctx -> {
      feedbackSeen.add(ctx.feedback());
      return NodeResult.builder()
          .change(FileChange.write("src/main/resources/db/migration/V2__clicks.sql",
              ctx.isRetry() ? "create table clicks(); create index ..." : "create table clicks();"))
          .build();
    };
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("migrate").task(migration).build()));

    RunOutcome outcome = engine().workspace(workspace).approvals(reviewer)
        .policy(new PolicyEngine(List.of(SchemaMigrationRule.flyway()))).build()
        .run(graph, "approval", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(reviewer.received()).hasSize(2);
    assertThat(reviewer.received().getFirst().diff()).contains("create table clicks");
    assertThat(reviewer.received().getFirst().reasons())
        .anySatisfy(r -> assertThat(r).contains("schema change"));
    assertThat(feedbackSeen.get(1)).anySatisfy(f -> assertThat(f).contains("add an index"));
    assertThat(eventsOf(RunEvent.RollbackPerformed.class)).hasSize(1);
  }

  @Test
  void replansConsumersWhenAReviewerAmendsAnUpstreamArtifact() {
    AtomicInteger designRuns = new AtomicInteger();
    AtomicInteger implRuns = new AtomicInteger();
    List<Integer> designExecutions = new CopyOnWriteArrayList<>();
    ScriptedApprovalGateway reviewer = new ScriptedApprovalGateway(
        ApprovalDecision.approve("bob", "ok"))
        .script("design", ApprovalDecision.approveWithAmendments("bob", "links must expire",
            Map.of("requirements", "shorten urls; links expire after 30 days")));
    WorkflowGraph graph = WorkflowGraph.of(List.of(
        node("analyse").task(output("requirements", "shorten urls")).build(),
        node("design", "analyse").approval(ApprovalMode.ALWAYS).task(ctx -> {
          designRuns.incrementAndGet();
          designExecutions.add(ctx.execution());
          return NodeResult.builder().output("design", "design for: " + ctx.input("requirements"))
              .decision(new Decision("storage", "postgres", "durable", List.of("redis")))
              .build();
        }).build(),
        node("impl", "design").task(ctx -> {
          implRuns.incrementAndGet();
          return NodeResult.builder().output("code", "code for " + ctx.input("design")).build();
        }).build()));

    RunOutcome outcome = engine().approvals(reviewer).build().run(graph, "replan", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(designRuns).hasValue(2);
    assertThat(designExecutions).containsExactly(1, 2);
    assertThat(implRuns).hasValue(1);
    assertThat(artifacts.content("code")).contains("expire after 30 days");
    assertThat(artifacts.history("requirements")).extracting(a -> a.producedBy())
        .containsExactly("analyse", "human:bob");
    assertThat(eventsOf(RunEvent.NodeInvalidated.class)).singleElement()
        .satisfies(e -> assertThat(e.nodeId()).isEqualTo("design"))
        .satisfies(e -> assertThat(e.cause()).contains("requirements"));
    assertThat(eventsOf(RunEvent.DecisionRecorded.class).getLast().basedOn())
        .singleElement().asString().startsWith("requirements@v2");
  }

  @Test
  void expandsThePlanIntoTasksAndTheJoinWaitsForAllOfThem() {
    List<String> executionOrder = new CopyOnWriteArrayList<>();
    NodeTask record = ctx -> {
      executionOrder.add(ctx.node().id());
      return NodeResult.builder().output(ctx.node().id(), "done").build();
    };
    WorkflowGraph graph = WorkflowGraph.of(List.of(
        node("plan").task(output("plan.tasks", "alpha,beta")).expansion(store ->
            Arrays.stream(store.content("plan.tasks").split(","))
                .map(t -> node("task-" + t).task(record).build()).toList()).build(),
        node("integrate", "plan").task(record).build()));

    RunOutcome outcome = engine().build().run(graph, "expansion", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(outcome.nodeStates()).containsKeys("task-alpha", "task-beta");
    assertThat(executionOrder.getLast()).isEqualTo("integrate");
    assertThat(eventsOf(RunEvent.GraphExpanded.class)).singleElement()
        .extracting(RunEvent.GraphExpanded::added).asList()
        .containsExactly("task-alpha", "task-beta");
  }

  @Test
  void skipsDependentsAndSafeStopsWhenANodeFails() {
    WorkflowGraph graph = WorkflowGraph.of(List.of(
        node("design").retry(RetryPolicy.none()).task(ctx -> {
          throw new IllegalStateException("cannot design");
        }).build(),
        node("impl", "design").task(output("code", "x")).build()));

    RunOutcome outcome = engine().build().run(graph, "failure", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
    assertThat(outcome.nodeStates()).containsEntry("design", NodeState.FAILED)
        .containsEntry("impl", NodeState.SKIPPED);
    assertThat(eventsOf(RunEvent.StopRequested.class)).singleElement().asString()
        .contains("failure threshold");
  }

  @Test
  void killSwitchPausesTheRunAndResumeFinishesWithoutRepeatingWork() throws Exception {
    Path killSwitch = tmp.resolve("STOP");
    Path logFile = tmp.resolve("events.jsonl");
    Path artifactDir = tmp.resolve("artifacts");
    AtomicInteger firstRuns = new AtomicInteger();
    List<NodeSpec> template = List.of(
        node("first").task(ctx -> {
          firstRuns.incrementAndGet();
          Files.writeString(killSwitch, "operator pressed stop");
          return NodeResult.builder().output("a", "from first").build();
        }).build(),
        node("second", "first").task(ctx ->
            NodeResult.builder().output("b", ctx.input("a") + " then second").build()).build());
    EngineConfig config = EngineConfig.defaults().withKillSwitch(killSwitch);

    log = new EventLog("run-resume", logFile, Clock.systemUTC());
    artifacts = new ArtifactStore(artifactDir);
    RunOutcome paused = engine().config(config).build()
        .run(WorkflowGraph.of(template), "kill-switch", "req");

    assertThat(paused.status()).isEqualTo(RunStatus.PAUSED);
    assertThat(paused.nodeStates()).containsEntry("second", NodeState.PENDING);

    Files.delete(killSwitch);
    log = new EventLog("run-resume", logFile, Clock.systemUTC());
    artifacts = new ArtifactStore(artifactDir);
    RunOutcome resumed = engine().config(config).build().resume(WorkflowGraph.of(template));

    assertThat(resumed.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(firstRuns).hasValue(1);
    assertThat(artifacts.content("b")).isEqualTo("from first then second");
    assertThat(eventsOf(RunEvent.RunResumed.class)).singleElement()
        .extracting(RunEvent.RunResumed::restoredNodes).isEqualTo(1);
    assertThat(EventLog.verifyChain(EventLog.readFile(logFile))).isEmpty();
  }

  @Test
  void attemptBudgetStopsARunawayRetryLoop() {
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("impl")
        .retry(new RetryPolicy(50, Duration.ofMillis(1), 1.0, Duration.ofMillis(1)))
        .task(ctx -> {
          throw new IllegalStateException("never works");
        }).build()));

    RunOutcome outcome = engine().config(EngineConfig.defaults().withMaxTotalAttempts(4)).build()
        .run(graph, "budget", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.PAUSED);
    assertThat(outcome.reason()).contains("attempt budget");
    assertThat(eventsOf(RunEvent.NodeStarted.class)).hasSize(4);
  }

  @Test
  void timesOutAHungAttemptAndRetries() {
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("impl").timeout(Duration.ofMillis(100))
        .task(ctx -> {
          if (!ctx.isRetry()) {
            Thread.sleep(5_000);
          }
          return NodeResult.builder().output("code", "ok").build();
        }).build()));

    RunOutcome outcome = engine().build().run(graph, "timeout", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(eventsOf(RunEvent.AttemptFailed.class)).singleElement()
        .extracting(RunEvent.AttemptFailed::reason).isEqualTo("timeout");
  }

  @Test
  void failsANodeWhoseEntryGateIsNotSatisfiedWithoutAttemptingIt() {
    AtomicInteger runs = new AtomicInteger();
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("design")
        .entryGate(RequiredArtifactsGate.of("requirements"))
        .task(ctx -> {
          runs.incrementAndGet();
          return NodeResult.builder().build();
        }).build()));

    RunOutcome outcome = engine().build().run(graph, "entry", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
    assertThat(runs).hasValue(0);
  }

  /** Stand-in for a compiler: passes when braces balance in every proposed file. */
  private static final class CompilesGate implements io.github.marishasahay.agentic.core.gate.Gate {
    @Override
    public String name() {
      return "compiles";
    }

    @Override
    public boolean usesWorkspace() {
      return true;
    }

    @Override
    public GateResult evaluate(io.github.marishasahay.agentic.core.gate.GateContext context) {
      try {
        String code = Files.readString(context.workspaceRoot().resolve("src/App.java"));
        long open = code.chars().filter(c -> c == '{').count();
        long close = code.chars().filter(c -> c == '}').count();
        return open == close ? GateResult.pass(name(), "ok")
            : GateResult.fail(name(), "App.java:1: error: reached end of file while parsing");
      } catch (java.io.IOException e) {
        return GateResult.fail(name(), e.getMessage());
      }
    }
  }
}
