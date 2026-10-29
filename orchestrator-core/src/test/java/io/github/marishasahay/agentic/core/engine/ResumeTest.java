package io.github.marishasahay.agentic.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marishasahay.agentic.core.approval.ScriptedApprovalGateway;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.event.EventEnvelope;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.RetryPolicy;
import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.graph.WorkflowGraph;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import io.github.marishasahay.agentic.core.workspace.GitWorkspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Resume must rebuild state only from evidence that checks out, and only from work that was
 * fully accepted. Crashes are simulated by truncating a real run's audit log at each
 * persistence boundary of an acceptance, which is exactly what an interrupted process leaves.
 */
class ResumeTest {

  private static final RetryPolicy FAST_RETRY =
      new RetryPolicy(3, Duration.ofMillis(1), 1.0, Duration.ofMillis(1));

  @TempDir
  Path tmp;

  /** A two-node run: {@code a} writes A.java, then {@code b} writes B.java. */
  private static WorkflowGraph graph() {
    return WorkflowGraph.of(List.of(
        NodeSpec.builder("a", Stage.IMPLEMENTATION).retry(FAST_RETRY)
            .task(ctx -> NodeResult.builder().output("a.out", "A")
                .change(FileChange.write("src/A.java", "class A {}")).build()).build(),
        NodeSpec.builder("b", Stage.IMPLEMENTATION).dependsOn("a").retry(FAST_RETRY)
            .task(ctx -> NodeResult.builder().output("b.out", "B")
                .change(FileChange.write("src/B.java", "class B {}")).build()).build()));
  }

  private static final class Run {
    final Path dir;
    final EventLog log;
    final GitWorkspace workspace;
    final ArtifactStore artifacts;

    Run(Path dir, boolean fresh) {
      this.dir = dir;
      this.log = new EventLog("resume-test", dir.resolve("events.jsonl"), Clock.systemUTC());
      this.workspace = fresh ? GitWorkspace.createFrom(null, dir.resolve("ws"))
          : GitWorkspace.open(dir.resolve("ws"));
      this.artifacts = new ArtifactStore(dir.resolve("artifacts"));
    }

    WorkflowEngine engine(EngineConfig config) {
      return WorkflowEngine.builder().config(config).events(log).artifacts(artifacts)
          .workspace(workspace).approvals(ScriptedApprovalGateway.approveAll()).build();
    }

    <T extends RunEvent> List<T> eventsOf(Class<T> type) {
      return log.events().stream().map(EventEnvelope::event).filter(type::isInstance)
          .map(type::cast).toList();
    }
  }

  private Run completedRun() {
    Run run = new Run(tmp.resolve("original"), true);
    RunOutcome outcome = run.engine(EngineConfig.defaults())
        .run(graph(), "resume", "req", Map.of("workflowSha", "w1"));
    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    return run;
  }

  @Test
  void refusesToResumeFromATamperedAuditLog() throws Exception {
    Run run = completedRun();
    Path events = run.dir.resolve("events.jsonl");
    String tampered = Files.readString(events).replaceFirst("\"attempts\":1", "\"attempts\":2");
    Files.writeString(events, tampered);

    Run reopened = new Run(run.dir, false);
    assertThatThrownBy(() -> reopened.engine(EngineConfig.defaults()).resume(graph()))
        .isInstanceOf(ResumeRefusedException.class)
        .hasMessageContaining("hash chain is broken");
  }

  @Test
  void refusesToResumeWhenAPersistedArtifactNoLongerMatchesItsRecordedHash() throws Exception {
    Run run = completedRun();
    Files.writeString(run.dir.resolve("artifacts/a.out/v1.txt"), "edited after the fact");

    Run reopened = new Run(run.dir, false);
    assertThatThrownBy(() -> reopened.engine(EngineConfig.defaults()).resume(graph()))
        .isInstanceOf(ResumeRefusedException.class)
        .hasMessageContaining("a.out@v1 does not match the hash");
  }

  @Test
  void refusesToResumeUnderADifferentWorkflowOrScenario() {
    Run run = completedRun();

    Run reopened = new Run(run.dir, false);
    assertThatThrownBy(() -> reopened.engine(EngineConfig.defaults()).resume(graph(),
        new WorkflowEngine.ResumeOptions(Map.of("scenario", "resume", "workflowSha", "w2"),
            null)))
        .isInstanceOf(ResumeRefusedException.class)
        .hasMessageContaining("workflowSha (log: w1, now: w2)");
  }

  /** Where the process died, relative to the acceptance of a node. */
  enum Cut {
    A_CHECKPOINT_CREATED("a", RunEvent.CheckpointCreated.class),
    B_CHECKPOINT_CREATED("b", RunEvent.CheckpointCreated.class),
    B_ARTIFACT_PUBLISHED("b", RunEvent.ArtifactProduced.class),
    B_SUCCESS_RECORDED("b", RunEvent.NodeSucceeded.class);

    final String nodeId;
    final Class<? extends RunEvent> type;

    Cut(String nodeId, Class<? extends RunEvent> type) {
      this.nodeId = nodeId;
      this.type = type;
    }

    boolean matches(RunEvent event) {
      return type.isInstance(event) && switch (event) {
        case RunEvent.ArtifactProduced p -> p.producedBy().equals(nodeId);
        default -> nodeId.equals(event.nodeId());
      };
    }
  }

  @ParameterizedTest
  @EnumSource(Cut.class)
  void recoversOnlyFullyAcceptedWorkWhenInterruptedAtAnAcceptanceBoundary(Cut cut)
      throws Exception {
    Run original = completedRun();
    String checkpointA = original.eventsOf(RunEvent.NodeSucceeded.class).get(0).checkpoint();
    String checkpointB = original.eventsOf(RunEvent.NodeSucceeded.class).get(1).checkpoint();
    Path crashed = tmp.resolve("crashed-" + cut);
    copyTree(original.dir, crashed);
    truncateAfter(crashed.resolve("events.jsonl"), original.log.events(),
        e -> cut.matches(e.event()));

    Run resumed = new Run(crashed, false);
    RunOutcome outcome = resumed.engine(EngineConfig.defaults()).resume(graph());

    RunEvent.RunResumed recovery = resumed.eventsOf(RunEvent.RunResumed.class).getFirst();
    switch (cut) {
      case A_CHECKPOINT_CREATED -> {
        assertThat(recovery.checkpoint()).isEqualTo("baseline");
        assertThat(recovery.restoredNodes()).isZero();
      }
      case B_CHECKPOINT_CREATED -> {
        assertThat(recovery.checkpoint()).isEqualTo(checkpointA);
        assertThat(recovery.discardedArtifacts()).isEmpty();
      }
      case B_ARTIFACT_PUBLISHED -> {
        assertThat(recovery.checkpoint()).isEqualTo(checkpointA);
        assertThat(recovery.discardedArtifacts()).singleElement().asString()
            .startsWith("b.out@v1");
        assertThat(resumed.artifacts.latest("b.out")).get()
            .satisfies(a -> assertThat(a.version()).isEqualTo(2));
      }
      case B_SUCCESS_RECORDED -> {
        assertThat(recovery.checkpoint()).isEqualTo(checkpointB);
        assertThat(recovery.restoredNodes()).isEqualTo(2);
        assertThat(resumed.eventsOf(RunEvent.NodeStarted.class)).hasSize(2);
      }
      default -> throw new IllegalStateException(cut.name());
    }
    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(EventLog.verifyChain(resumed.log.events())).isEmpty();
    assertThat(resumed.workspace.root().resolve("src/A.java")).exists();
    assertThat(resumed.workspace.root().resolve("src/B.java")).exists();
  }

  @Test
  void budgetsCarryOverAcrossResumeUnlessAResetIsAuthorizedAndRecorded() {
    AtomicInteger bCalls = new AtomicInteger();
    WorkflowGraph graph = WorkflowGraph.of(List.of(
        NodeSpec.builder("a", Stage.IMPLEMENTATION).retry(FAST_RETRY)
            .task(ctx -> NodeResult.builder().output("a.out", "A").build()).build(),
        NodeSpec.builder("b", Stage.IMPLEMENTATION).dependsOn("a").retry(FAST_RETRY)
            .task(ctx -> {
              if (bCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("flaky");
              }
              return NodeResult.builder().output("b.out", "B").build();
            }).build()));
    EngineConfig tight = EngineConfig.defaults().withMaxTotalAttempts(2);
    Run run = new Run(tmp.resolve("budget"), true);

    RunOutcome first = run.engine(tight).run(graph, "budget", "req");
    RunOutcome carried = new Run(run.dir, false).engine(tight).resume(graph);
    Run lastSession = new Run(run.dir, false);
    RunOutcome reset = lastSession.engine(tight).resume(graph,
        new WorkflowEngine.ResumeOptions(Map.of(), "ops-lead"));

    assertThat(first.status()).isEqualTo(RunStatus.PAUSED);
    assertThat(carried.status()).isEqualTo(RunStatus.PAUSED);
    assertThat(carried.reason()).contains("attempt budget of 2 exhausted");
    assertThat(bCalls).hasValue(2);
    assertThat(reset.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(lastSession.eventsOf(RunEvent.BudgetReset.class)).singleElement()
        .satisfies(r -> {
          assertThat(r.authorizedBy()).isEqualTo("ops-lead");
          assertThat(r.attemptsDiscarded()).isEqualTo(2);
        });
  }

  private static void truncateAfter(Path file, List<EventEnvelope> events,
      Predicate<EventEnvelope> lastKept) throws IOException {
    int keep = -1;
    for (int i = 0; i < events.size(); i++) {
      if (lastKept.test(events.get(i))) {
        keep = i + 1;
        break;
      }
    }
    assertThat(keep).as("cut point present in the original log").isPositive();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    Files.write(file, lines.subList(0, keep), StandardCharsets.UTF_8);
  }

  private static void copyTree(Path source, Path target) throws IOException {
    try (Stream<Path> walk = Files.walk(source)) {
      walk.forEach(path -> {
        Path destination = target.resolve(source.relativize(path).toString());
        try {
          if (Files.isDirectory(path)) {
            Files.createDirectories(destination);
          } else {
            Files.copy(path, destination);
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    }
  }
}
