package io.github.marishasahay.agentic.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.agentic.core.approval.ApprovalDecision;
import io.github.marishasahay.agentic.core.approval.ApprovalRequest;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.gate.GateResult;
import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.metrics.MetricsCalculator;
import io.github.marishasahay.agentic.core.metrics.RunMetrics;
import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import io.github.marishasahay.agentic.core.policy.Severity;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportingTest {

  @TempDir
  Path tmp;

  /** One of every event type, as a governed run with a failure, recovery and approval. */
  private static EventLog fullLog() {
    EventLog log = EventLog.inMemory("run-1", Clock.systemUTC());
    log.append(new RunEvent.RunStarted("brownfield", "sha", 3, Map.of("llmMode", "REPLAY")));
    log.append(new RunEvent.RunResumed(1));
    log.append(new RunEvent.NodeStarted("impl-clicks", "IMPLEMENTATION", 1, "primary"));
    log.append(new RunEvent.AgentCalled("impl-clicks", "coder", "model", "replay", 1200, 10, 5));
    log.append(new RunEvent.PolicyEvaluated("impl-clicks", 1, List.of(new PolicyFinding(
        "schema-migration", Severity.REQUIRE_APPROVAL, "new migration", "V2__clicks.sql"))));
    log.append(new RunEvent.GateEvaluated("impl-clicks", 1, "exit", "maven-compile", false,
        "ClickStore.java:[3] cannot find symbol <T>"));
    log.append(new RunEvent.RollbackPerformed("impl-clicks", "abcdef1234567890", "gate failed"));
    log.append(new RunEvent.AttemptFailed("impl-clicks", 1, "gate-failed", "cannot find symbol"));
    log.append(new RunEvent.FallbackActivated("impl-clicks", "primary exhausted"));
    log.append(new RunEvent.NodeStarted("impl-clicks", "IMPLEMENTATION", 2, "fallback"));
    log.append(new RunEvent.GateEvaluated("impl-clicks", 2, "exit", "maven-compile", true, "ok"));
    log.append(new RunEvent.ApprovalRequested("impl-clicks", List.of("schema change")));
    log.append(new RunEvent.ApprovalResolved("impl-clicks", true, "alice", "ok", List.of(
        "requirements.spec")));
    log.append(new RunEvent.CheckpointCreated("impl-clicks", "1234567890abcdef"));
    log.append(new RunEvent.ArtifactProduced("impl.clicks", 1, "a".repeat(64), "impl-clicks",
        "impl.clicks/v1.txt"));
    log.append(new RunEvent.DecisionRecorded("impl-clicks", "Storage", "JdbcClient",
        "matches codebase", List.of("JPA"), List.of("plan.tasks@v1#abc")));
    log.append(new RunEvent.NodeSucceeded("impl-clicks", "IMPLEMENTATION", 2, Map.of(),
        Map.of()));
    log.append(new RunEvent.GraphExpanded("plan", List.of("impl-clicks"), List.of()));
    log.append(new RunEvent.NodeInvalidated("design", "input 'requirements.spec' changed"));
    log.append(new RunEvent.NodeFailed("docs", "DOCUMENTATION", "gave up"));
    log.append(new RunEvent.NodeSkipped("release", "upstream 'docs' did not succeed"));
    log.append(new RunEvent.StopRequested("failure threshold reached"));
    log.append(new RunEvent.RunFinished("FAILED", "failed nodes: [docs]"));
    return log;
  }

  @Test
  void reportShowsMetricsCheckpointsLineageAndEvidenceFromTheLog() {
    EventLog log = fullLog();
    RunMetrics metrics = MetricsCalculator.compute(log.events());

    String html = HtmlReport.render(log.events(), "flowchart LR\n  a --> b\n"
        + HtmlReport.mermaidClasses(), metrics);

    assertThat(html).contains("<title>Run Report</title>").contains("status <span class=\"badge"
        + " bad\">FAILED</span>").contains("audit chain <span class=ok>verified</span>")
        .contains("failed nodes: [docs]").contains("fallback activations")
        .contains("approved by alice").contains("requirements.spec")
        .contains("<b>Storage</b><br>JdbcClient").contains("plan.tasks@v1#abc")
        .contains("FAIL exit gate maven-compile").contains("cannot find symbol &lt;T&gt;")
        .contains("REQUIRE_APPROVAL [schema-migration]").contains("re-planned")
        .contains("safe stop").contains("href=\"artifacts/impl.clicks/v1.txt\"")
        .contains("classDef succeeded").contains("23 hash-chained events");
    assertThat(HtmlReport.nodeClass(log.events(), "impl-clicks", "SUCCEEDED"))
        .isEqualTo("recovered");
    assertThat(HtmlReport.nodeClass(log.events(), "plan", "SUCCEEDED")).isEqualTo("succeeded");
    assertThat(HtmlReport.nodeClass(log.events(), "docs", "FAILED")).isEqualTo("failed");
    assertThat(HtmlReport.dur(65_000)).isEqualTo("1m 5s");
    assertThat(HtmlReport.dur(1_500)).isEqualTo("1.5s");
    assertThat(HtmlReport.dur(40)).isEqualTo("40ms");
    assertThat(HtmlReport.esc("<a href=\"x\">&")).isEqualTo("&lt;a href=&quot;x&quot;&gt;&amp;");
  }

  @Test
  void progressPrinterNarratesEveryStateChange() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    EventLog log = fullLog();
    ProgressPrinter printer = new ProgressPrinter(new PrintStream(bytes, true,
        StandardCharsets.UTF_8));

    log.events().forEach(printer);
    String out = bytes.toString(StandardCharsets.UTF_8);

    assertThat(out).contains("RUN run-1 started: brownfield").contains("restored from the log")
        .contains("model coder").contains("FAIL exit gate maven-compile")
        .contains("rollback to abcdef12").contains("rejected (gate-failed): cannot find symbol")
        .contains("fallback strategy activated").contains("[fallback]")
        .contains("waiting for human approval").contains("amended [requirements.spec]")
        .contains("succeeded (2 attempts)").contains("plan expanded into [impl-clicks]")
        .contains("invalidated").contains("FAILED: gave up").contains("skipped")
        .contains("SAFE STOP").contains("RUN finished: FAILED");
  }

  private ApprovalRequest request() {
    return new ApprovalRequest("run-1", "design", Stage.DESIGN, "adds stats endpoint",
        List.of("mandatory design sign-off"), List.of(), Map.of("design.doc", "# Design v1"),
        "diff --git a/api/openapi.yaml b/api/openapi.yaml\n+  /stats:",
        List.of(GateResult.pass("api-compatibility", "backward compatible")));
  }

  private ConsoleApprovalGateway console(String typed, ByteArrayOutputStream out) {
    return new ConsoleApprovalGateway(new BufferedReader(new StringReader(typed)),
        new PrintStream(out, true, StandardCharsets.UTF_8), tmp.resolve("edits"), "marisha");
  }

  @Test
  void consoleReviewerCanInspectThenApproveOrRejectWithFeedback() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    ApprovalDecision approved = console("v\n1\nd\nx\na\nship it\n", out).decide(request());
    ApprovalDecision rejected = console("r\nadd retention policy\n", out).decide(request());

    assertThat(approved.approved()).isTrue();
    assertThat(approved.reviewer()).isEqualTo("marisha");
    assertThat(approved.comment()).isEqualTo("ship it");
    assertThat(rejected.approved()).isFalse();
    assertThat(rejected.comment()).isEqualTo("add retention policy");
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("HUMAN APPROVAL REQUIRED: design")
        .contains("PASS api-compatibility").contains("# Design v1").contains("+  /stats:")
        .contains("Unknown choice").contains("Workspace changes: 1 file(s)");
  }

  @Test
  void consoleReviewerCanAmendAnArtifactBeforeApproving() throws Exception {
    Path edit = tmp.resolve("edits").resolve("design.doc.txt");
    Thread editor = Thread.ofVirtual().start(() -> {
      try {
        while (!Files.exists(edit)) {
          Thread.sleep(10);
        }
        Files.writeString(edit, "# Design v2 (reviewer edit)");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    java.io.PipedWriter typing = new java.io.PipedWriter();
    java.io.PipedReader reader = new java.io.PipedReader(typing);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ConsoleApprovalGateway gateway = new ConsoleApprovalGateway(new BufferedReader(reader),
        new PrintStream(out, true, StandardCharsets.UTF_8), tmp.resolve("edits"), "marisha");
    Thread.ofVirtual().start(() -> {
      try {
        typing.write("e\n1\n");
        typing.flush();
        editor.join();
        typing.write("\na\n\n");
        typing.close();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });

    ApprovalDecision decision = gateway.decide(request());

    assertThat(decision.approved()).isTrue();
    assertThat(decision.amendments()).containsEntry("design.doc", "# Design v2 (reviewer edit)");
  }
}
