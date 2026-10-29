package io.github.marishasahay.agentic.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.agentic.core.approval.ApprovalDecision;
import io.github.marishasahay.agentic.core.approval.ApprovalKind;
import io.github.marishasahay.agentic.core.approval.ApprovalRequest;
import io.github.marishasahay.agentic.core.approval.ScriptedApprovalGateway;
import io.github.marishasahay.agentic.core.approval.ScriptedApprovalGateway.ScriptedDecision;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.event.EventEnvelope;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.gate.Gate;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;
import io.github.marishasahay.agentic.core.graph.ApprovalMode;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.RetryPolicy;
import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.graph.WorkflowGraph;
import io.github.marishasahay.agentic.core.policy.PolicyEngine;
import io.github.marishasahay.agentic.core.policy.rules.SensitiveFileRule;
import io.github.marishasahay.agentic.core.util.Hashing;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import io.github.marishasahay.agentic.core.workspace.GitWorkspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The governance invariants of the acceptance protocol, one test each: authorization before
 * execution, fail-closed reviewers, rollback on every failure path, revision-safe parallel
 * proposals, discarding invalidated in-flight work, tree-bound evidence and audit fail-stop.
 * Ordering is forced with latches on events, never with sleeps.
 */
class GovernanceTest {

  private static final RetryPolicy FAST_RETRY =
      new RetryPolicy(3, Duration.ofMillis(1), 1.0, Duration.ofMillis(1));

  @TempDir
  Path tmp;

  private final EventLog log = EventLog.inMemory("gov", Clock.systemUTC());
  private final ArtifactStore artifacts = ArtifactStore.inMemory();

  private WorkflowEngine.Builder engine() {
    return WorkflowEngine.builder().events(log).artifacts(artifacts)
        .approvals(ScriptedApprovalGateway.approveAll());
  }

  private static NodeSpec.Builder node(String id, String... deps) {
    return NodeSpec.builder(id, Stage.IMPLEMENTATION).dependsOn(deps).retry(FAST_RETRY);
  }

  private <T extends RunEvent> List<T> eventsOf(Class<T> type) {
    return log.events().stream().map(EventEnvelope::event).filter(type::isInstance)
        .map(type::cast).toList();
  }

  private GitWorkspace workspaceWith(Map<String, String> files) {
    Path source = tmp.resolve("source");
    files.forEach((path, content) -> write(source.resolve(path), content));
    return GitWorkspace.createFrom(source, tmp.resolve("ws"));
  }

  private static void write(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path file) {
    try {
      return Files.exists(file) ? Files.readString(file) : "<absent>";
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Records what was on disk each time it ran. */
  private static final class ProbeGate implements Gate {
    final List<String> seen = new CopyOnWriteArrayList<>();
    private final String path;

    ProbeGate(String path) {
      this.path = path;
    }

    @Override
    public String name() {
      return "probe";
    }

    @Override
    public boolean usesWorkspace() {
      return true;
    }

    @Override
    public GateResult evaluate(GateContext context) {
      seen.add(read(context.workspaceRoot().resolve(path)));
      return GateResult.pass(name(), "ran");
    }
  }

  @Test
  void buildFileChangesAreNeitherWrittenNorExecutedBeforeAuthorization() {
    GitWorkspace workspace = workspaceWith(Map.of("pom.xml", "<project>v1</project>\n"));
    ScriptedApprovalGateway reviewer = ScriptedApprovalGateway.failClosed().script("deps",
        ScriptedDecision.authorization(ApprovalDecision.reject("alice",
            "unknown plugin; licence review first"), "dependency-change"),
        ScriptedDecision.authorization(ApprovalDecision.approve("alice", "licence ok"),
            "dependency-change"));
    ProbeGate build = new ProbeGate("pom.xml");
    NodeTask coder = ctx -> NodeResult.builder().change(FileChange.write("pom.xml",
        ctx.isRetry() ? "<project>v2 with junit</project>\n"
            : "<project>v2 with evil-plugin</project>\n")).build();
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("deps").task(coder).exitGate(build)
        .build()));

    RunOutcome outcome = engine().workspace(workspace).approvals(reviewer)
        .policy(new PolicyEngine(List.of(SensitiveFileRule.dependencies()))).build()
        .run(graph, "authorize", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(build.seen).containsExactly("<project>v2 with junit</project>\n");
    assertThat(eventsOf(RunEvent.RollbackPerformed.class)).isEmpty();
    List<ApprovalRequest> asked = reviewer.received();
    assertThat(asked).extracting(ApprovalRequest::kind)
        .containsExactly(ApprovalKind.AUTHORIZATION, ApprovalKind.AUTHORIZATION);
    assertThat(asked.get(0).diff()).contains("+<project>v2 with evil-plugin</project>");
    assertThat(asked.get(0).gateResults()).isEmpty();
    assertThat(asked.get(0).proposalSha()).isNotEqualTo(asked.get(1).proposalSha());
    assertThat(eventsOf(RunEvent.ApprovalResolved.class))
        .extracting(RunEvent.ApprovalResolved::proposalSha)
        .containsExactly(asked.get(0).proposalSha(), asked.get(1).proposalSha());
  }

  @Test
  void aReviewerWhoCannotBeReachedPausesTheRunInsteadOfApproving() throws Exception {
    GitWorkspace workspace = workspaceWith(Map.of("README.md", "v1\n"));
    String baseline = workspace.head();
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("design").approval(ApprovalMode.ALWAYS)
        .task(ctx -> NodeResult.builder().change(FileChange.write("api/openapi.yaml", "x"))
            .build()).build()));

    RunOutcome outcome = engine().workspace(workspace)
        .approvals(ScriptedApprovalGateway.failClosed()).build().run(graph, "nobody", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.PAUSED);
    assertThat(outcome.reason()).contains("reviewer unavailable for design")
        .contains("no scripted decision");
    assertThat(eventsOf(RunEvent.NodeSucceeded.class)).isEmpty();
    assertThat(workspace.head()).isEqualTo(baseline);
    assertThat(workspace.root().resolve("api/openapi.yaml")).doesNotExist();
    assertThat(eventsOf(RunEvent.RollbackPerformed.class)).singleElement()
        .satisfies(r -> assertThat(r.reason()).contains("reviewer unavailable"));
  }

  @Test
  void anApprovalGatewayFailureRestoresTheWorkspaceAndPauses() throws Exception {
    GitWorkspace workspace = workspaceWith(Map.of("README.md", "v1\n"));
    String baseline = workspace.head();
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("release")
        .approval(ApprovalMode.ALWAYS)
        .task(ctx -> NodeResult.builder().change(FileChange.write("CHANGELOG.md", "x")).build())
        .build()));

    RunOutcome outcome = engine().workspace(workspace).approvals(request -> {
      throw new IllegalStateException("stdin closed while waiting for a reviewer");
    }).build().run(graph, "gateway-down", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.PAUSED);
    assertThat(outcome.reason()).contains("stdin closed");
    assertThat(workspace.head()).isEqualTo(baseline);
    assertThat(workspace.root().resolve("CHANGELOG.md")).doesNotExist();
  }

  @Test
  void aPartialApplyFailureRestoresTheAcceptedWorkspace() {
    FaultyWorkspace workspace = new FaultyWorkspace(workspaceWith(Map.of("README.md", "v1\n")));
    workspace.failApplyAfterFiles = 1;
    NodeTask coder = ctx -> ctx.isRetry()
        ? NodeResult.builder().change(FileChange.write("src/B.java", "class B {}")).build()
        : NodeResult.builder().change(FileChange.write("src/A.java", "class A {}"))
            .change(FileChange.write("src/B.java", "class B {}")).build();
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("impl").task(coder).build()));

    RunOutcome outcome = engine().workspace(workspace).build().run(graph, "partial", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(eventsOf(RunEvent.RollbackPerformed.class)).singleElement()
        .satisfies(r -> assertThat(r.reason()).contains("disk full"));
    assertThat(eventsOf(RunEvent.AttemptFailed.class)).extracting(RunEvent.AttemptFailed::reason)
        .containsExactly("acceptance-error");
    assertThat(workspace.root().resolve("src/A.java")).doesNotExist();
    assertThat(workspace.root().resolve("src/B.java")).exists();
  }

  @Test
  void aCheckpointFailureRestoresTheWorkspaceAndTheAttemptIsRetried() {
    FaultyWorkspace workspace = new FaultyWorkspace(workspaceWith(Map.of("README.md", "v1\n")));
    workspace.failCheckpoint = true;
    NodeTask coder = ctx -> NodeResult.builder().change(FileChange.write(
        ctx.isRetry() ? "src/Second.java" : "src/First.java", "class X {}")).build();
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("impl").task(coder).build()));

    RunOutcome outcome = engine().workspace(workspace).build().run(graph, "commit", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(eventsOf(RunEvent.RollbackPerformed.class)).singleElement()
        .satisfies(r -> assertThat(r.reason()).contains("index.lock"));
    assertThat(workspace.root().resolve("src/First.java")).doesNotExist();
    assertThat(eventsOf(RunEvent.NodeSucceeded.class)).singleElement()
        .satisfies(s -> assertThat(s.checkpoint()).isEqualTo(workspace.head()));
  }

  @Test
  void aFailedRollbackQuarantinesTheWorkspaceAndStopsWithBothFailuresRecorded() {
    FaultyWorkspace workspace = new FaultyWorkspace(workspaceWith(Map.of("README.md", "v1\n")));
    workspace.failApplyAfterFiles = 1;
    workspace.failRollback = true;
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("impl")
        .task(ctx -> NodeResult.builder().change(FileChange.write("src/A.java", "a"))
            .change(FileChange.write("src/B.java", "b")).build())
        .build()));

    RunOutcome outcome = engine().workspace(workspace).build().run(graph, "quarantine", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.PAUSED);
    assertThat(outcome.reason()).contains("workspace quarantined");
    assertThat(eventsOf(RunEvent.RollbackFailed.class)).singleElement().satisfies(f -> {
      assertThat(f.originalFailure()).contains("disk full");
      assertThat(f.rollbackError()).contains("permission denied");
    });
    assertThat(eventsOf(RunEvent.NodeSucceeded.class)).isEmpty();
    assertThat(eventsOf(RunEvent.NodeStarted.class)).hasSize(1);
  }

  @Test
  void conflictingParallelProposalsCannotOverwriteAcceptedWork() throws Exception {
    GitWorkspace workspace = workspaceWith(Map.of("src/App.java", "class App {}\n"));
    CountDownLatch aStarted = new CountDownLatch(1);
    CountDownLatch bAccepted = new CountDownLatch(1);
    log.subscribe(e -> {
      if (e.event() instanceof RunEvent.NodeSucceeded s && s.nodeId().equals("b")) {
        bAccepted.countDown();
      }
    });
    NodeTask a = ctx -> {
      aStarted.countDown();
      bAccepted.await(5, TimeUnit.SECONDS);
      String current = read(ctx.workspaceRoot().resolve("src/App.java"));
      return NodeResult.builder().change(FileChange.write("src/App.java", current + "// a\n"))
          .build();
    };
    NodeTask b = ctx -> {
      aStarted.await(5, TimeUnit.SECONDS);
      String current = read(ctx.workspaceRoot().resolve("src/App.java"));
      return NodeResult.builder().change(FileChange.write("src/App.java", current + "// b\n"))
          .build();
    };
    NodeTask c = ctx -> {
      bAccepted.await(5, TimeUnit.SECONDS);
      return NodeResult.builder().change(FileChange.write("src/Other.java", "class O {}"))
          .build();
    };
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("a").task(a).build(),
        node("b").task(b).build(), node("c").task(c).build()));

    RunOutcome outcome = engine().workspace(workspace).build().run(graph, "conflict", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(read(workspace.root().resolve("src/App.java")))
        .isEqualTo("class App {}\n// b\n// a\n");
    assertThat(eventsOf(RunEvent.AttemptFailed.class)).singleElement().satisfies(f -> {
      assertThat(f.nodeId()).isEqualTo("a");
      assertThat(f.reason()).isEqualTo("stale-proposal");
      assertThat(f.feedback()).contains("src/App.java");
    });
    assertThat(eventsOf(RunEvent.NodeStarted.class).stream()
        .filter(s -> s.nodeId().equals("c"))).hasSize(1);
  }

  @Test
  void inFlightWorkWhoseInputWasReplacedIsDiscardedNotPublished() {
    CountDownLatch amended = new CountDownLatch(1);
    log.subscribe(e -> {
      if (e.event() instanceof RunEvent.NodeSucceeded s && s.nodeId().equals("amender")) {
        amended.countDown();
      }
    });
    ScriptedApprovalGateway reviewer = ScriptedApprovalGateway.failClosed().script("amender",
        ApprovalDecision.approveWithAmendments("bob", "spec changed", Map.of("x", "v2")));
    WorkflowGraph graph = WorkflowGraph.of(List.of(
        node("seed").task(ctx -> NodeResult.builder().output("x", "v1").build()).build(),
        node("consumer", "seed").task(ctx -> {
          String x = ctx.input("x");
          amended.await(5, TimeUnit.SECONDS);
          return NodeResult.builder().output("y", "y from " + x).build();
        }).build(),
        node("amender", "seed").approval(ApprovalMode.ALWAYS)
            .task(ctx -> NodeResult.builder().output("review", "done").build()).build()));

    RunOutcome outcome = engine().approvals(reviewer).build().run(graph, "discard", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(artifacts.history("y")).singleElement()
        .satisfies(a -> assertThat(a.content()).isEqualTo("y from v2"));
    assertThat(eventsOf(RunEvent.AttemptDiscarded.class)).singleElement().satisfies(d -> {
      assertThat(d.nodeId()).isEqualTo("consumer");
      assertThat(d.reason()).contains("input 'x' changed");
    });
    assertThat(eventsOf(RunEvent.NodeSucceeded.class).stream()
        .filter(s -> s.nodeId().equals("consumer")))
        .allSatisfy(s -> assertThat(s.inputs().get("x")).isEqualTo(Hashing.sha256("v2")));
  }

  @Test
  void inFlightWorkDownstreamOfAnInvalidatedNodeCannotBecomeAccepted() {
    CountDownLatch midInvalidated = new CountDownLatch(1);
    log.subscribe(e -> {
      if (e.event() instanceof RunEvent.NodeInvalidated i && i.nodeId().equals("mid")) {
        midInvalidated.countDown();
      }
    });
    ScriptedApprovalGateway reviewer = ScriptedApprovalGateway.failClosed().script("amender",
        ApprovalDecision.approveWithAmendments("bob", "new spec", Map.of("x", "v2")),
        ApprovalDecision.approve("bob", "still fine"));
    WorkflowGraph graph = WorkflowGraph.of(List.of(
        node("seed").task(ctx -> NodeResult.builder().output("x", "v1").build()).build(),
        node("mid", "seed").task(ctx -> NodeResult.builder()
            .output("m", "m(" + ctx.input("x") + ")").build()).build(),
        node("leaf", "mid").task(ctx -> {
          String m = ctx.input("m");
          midInvalidated.await(5, TimeUnit.SECONDS);
          return NodeResult.builder().output("z", "z(" + m + ")").build();
        }).build(),
        node("amender", "mid").approval(ApprovalMode.ALWAYS)
            .task(ctx -> NodeResult.builder().output("review", "done").build()).build()));

    RunOutcome outcome = engine().approvals(reviewer).build().run(graph, "generation", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(artifacts.history("z")).singleElement()
        .satisfies(a -> assertThat(a.content()).isEqualTo("z(m(v2))"));
    assertThat(eventsOf(RunEvent.AttemptDiscarded.class)).singleElement().satisfies(d -> {
      assertThat(d.nodeId()).isEqualTo("leaf");
      assertThat(d.reason()).contains("invalidated while it ran");
    });
  }

  @Test
  void evidenceAndAcceptanceAreBoundToTheExactValidatedTree() {
    GitWorkspace workspace = workspaceWith(Map.of("README.md", "v1\n"));
    ProbeGate gate = new ProbeGate("src/A.java");
    ScriptedApprovalGateway reviewer = ScriptedApprovalGateway.failClosed().script("impl",
        ScriptedDecision.acceptance(ApprovalDecision.approve("carol", "ok"), "sign-off"));
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("impl").approval(ApprovalMode.ALWAYS)
        .task(ctx -> NodeResult.builder().change(FileChange.write("src/A.java", "class A {}"))
            .build()).exitGate(gate).build()));

    RunOutcome outcome = engine().workspace(workspace).approvals(reviewer).build()
        .run(graph, "bound", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    String validated = eventsOf(RunEvent.GateEvaluated.class).getFirst().treeSha();
    assertThat(validated).isEqualTo(workspace.treeOf(workspace.head()));
    assertThat(reviewer.received()).singleElement()
        .satisfies(r -> assertThat(r.treeSha()).isEqualTo(validated));
    assertThat(eventsOf(RunEvent.NodeSucceeded.class)).singleElement()
        .satisfies(s -> assertThat(s.treeSha()).isEqualTo(validated));
  }

  @Test
  void aGateThatModifiesTheTreeInvalidatesItsOwnValidation() {
    GitWorkspace workspace = workspaceWith(Map.of("README.md", "v1\n"));
    Gate formatter = new Gate() {
      private int runs;

      @Override
      public String name() {
        return "rewriting-build";
      }

      @Override
      public boolean usesWorkspace() {
        return true;
      }

      @Override
      public GateResult evaluate(GateContext context) {
        if (runs++ == 0) {
          write(context.workspaceRoot().resolve("src/A.java"), "class A { /* rewritten */ }");
        }
        return GateResult.pass(name(), "ok");
      }
    };
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("impl")
        .task(ctx -> NodeResult.builder().change(FileChange.write("src/A.java", "class A {}"))
            .build()).exitGate(formatter).build()));

    RunOutcome outcome = engine().workspace(workspace).build().run(graph, "rewrite", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(eventsOf(RunEvent.AttemptFailed.class)).singleElement()
        .satisfies(f -> assertThat(f.reason()).isEqualTo("tree-changed"));
    assertThat(read(workspace.root().resolve("src/A.java"))).isEqualTo("class A {}");
  }

  @Test
  void invalidReviewerAmendmentsAreRefusedAndTheReviewerIsAskedAgain() {
    ScriptedApprovalGateway reviewer = ScriptedApprovalGateway.failClosed().script("design",
        ApprovalDecision.approveWithAmendments("dan", "typo", Map.of("spec", "{not json")),
        ApprovalDecision.approveWithAmendments("dan", "wrong key", Map.of("spek", "{}")),
        ApprovalDecision.approveWithAmendments("dan", "fixed", Map.of("spec", "{\"ok\":true}")),
        ApprovalDecision.approve("dan", "re-planned design is fine"));
    WorkflowGraph graph = WorkflowGraph.of(List.of(
        node("analyse").task(ctx -> NodeResult.builder().output("spec", "{}").build()).build(),
        node("design", "analyse").approval(ApprovalMode.ALWAYS)
            .task(ctx -> NodeResult.builder().output("design", ctx.input("spec")).build())
            .build()));

    RunOutcome outcome = engine().approvals(reviewer)
        .amendmentValidator((key, content) -> content.startsWith("{") && content.endsWith("}")
            ? List.of() : List.of("not a JSON object"))
        .build().run(graph, "amend", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(eventsOf(RunEvent.AmendmentRejected.class))
        .extracting(r -> String.join(" ", r.problems()))
        .satisfiesExactly(
            p -> assertThat(p).contains("not a JSON object"),
            p -> assertThat(p).contains("unknown artifact 'spek'"));
    assertThat(reviewer.received()).extracting(r -> r.problems().isEmpty())
        .containsExactly(true, false, false, true);
    assertThat(artifacts.content("spec")).isEqualTo("{\"ok\":true}");
  }

  @Test
  void anAuditLogFailureStopsTheRunAndRestoresTheWorkspaceWithoutClaimingSuccess()
      throws Exception {
    Path file = tmp.resolve("events.jsonl");
    EventLog persisted = new EventLog("audit", file, Clock.systemUTC());
    persisted.subscribe(e -> {
      if (e.event() instanceof RunEvent.NodeStarted s && s.nodeId().equals("impl")) {
        try {
          Files.delete(file);
          Files.createDirectory(file);
        } catch (IOException ex) {
          throw new UncheckedIOException(ex);
        }
      }
    });
    GitWorkspace workspace = workspaceWith(Map.of("README.md", "v1\n"));
    String baseline = workspace.head();
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("impl")
        .task(ctx -> NodeResult.builder().change(FileChange.write("src/A.java", "a")).build())
        .exitGate(new ProbeGate("src/A.java")).build()));

    RunOutcome outcome = WorkflowEngine.builder().events(persisted).artifacts(artifacts)
        .workspace(workspace).approvals(ScriptedApprovalGateway.approveAll()).build()
        .run(graph, "audit", "req");

    assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
    assertThat(outcome.reason()).contains("audit log unavailable");
    assertThat(persisted.unavailable()).isTrue();
    assertThat(persisted.events()).extracting(e -> e.event().getClass().getSimpleName())
        .doesNotContain("NodeSucceeded", "RunFinished");
    assertThat(workspace.head()).isEqualTo(baseline);
    assertThat(workspace.root().resolve("src/A.java")).doesNotExist();
  }
}
