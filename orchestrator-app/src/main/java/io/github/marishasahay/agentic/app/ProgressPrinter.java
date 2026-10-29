package io.github.marishasahay.agentic.app;

import io.github.marishasahay.agentic.core.event.EventEnvelope;
import io.github.marishasahay.agentic.core.event.RunEvent;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** Renders the event stream as a live, human-readable log. */
final class ProgressPrinter implements Consumer<EventEnvelope> {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
      .withZone(ZoneId.systemDefault());

  private final PrintStream out;

  ProgressPrinter(PrintStream out) {
    this.out = out;
  }

  @Override
  public void accept(EventEnvelope envelope) {
    String line = switch (envelope.event()) {
      case RunEvent.RunStarted e -> Ansi.bold("RUN " + envelope.runId() + " started: "
          + e.scenario());
      case RunEvent.RunResumed e -> Ansi.bold("RUN resumed with " + e.restoredNodes()
          + " node(s) restored from the log");
      case RunEvent.NodeStarted e -> Ansi.cyan("> " + pad(e.nodeId()) + " attempt " + e.attempt()
          + (e.strategy().equals("primary") ? "" : " [" + e.strategy() + "]"));
      case RunEvent.AgentCalled e -> Ansi.dim("    model " + e.agent() + " (" + e.model() + ", "
          + e.mode() + ", " + e.latencyMs() + " ms)");
      case RunEvent.GateEvaluated e -> e.passed()
          ? Ansi.green("    PASS " + e.phase() + " gate " + e.gate())
          : Ansi.red("    FAIL " + e.phase() + " gate " + e.gate() + ": " + first(e.evidence()));
      case RunEvent.PolicyEvaluated e -> Ansi.magenta("    policy: " + e.findings().stream()
          .map(f -> f.severity() + " " + f.rule()).toList());
      case RunEvent.AttemptFailed e -> Ansi.red("  x " + pad(e.nodeId()) + " attempt "
          + e.attempt() + " rejected (" + e.reason() + "): " + first(e.feedback()));
      case RunEvent.FallbackActivated e -> Ansi.yellow("  ~ " + pad(e.nodeId())
          + " fallback strategy activated");
      case RunEvent.RollbackPerformed e -> Ansi.yellow("    rollback to "
          + e.toCheckpoint().substring(0, 8) + " (" + e.reason() + ")");
      case RunEvent.ApprovalRequested e -> Ansi.yellow("  ? " + pad(e.nodeId()) + " waiting for "
          + e.kind().toLowerCase() + ": " + e.reasonCodes());
      case RunEvent.ApprovalResolved e -> (e.approved() ? Ansi.green("    approved")
          : Ansi.red("    rejected")) + " by " + e.reviewer()
          + (e.amendedArtifacts().isEmpty() ? "" : ", amended " + e.amendedArtifacts());
      case RunEvent.CheckpointCreated e -> Ansi.dim("    checkpoint " + e.checkpoint()
          .substring(0, 8));
      case RunEvent.NodeSucceeded e -> Ansi.green("  + " + pad(e.nodeId()) + " succeeded ("
          + e.attempts() + " attempt" + (e.attempts() == 1 ? "" : "s") + ")");
      case RunEvent.NodeFailed e -> Ansi.red("  ! " + pad(e.nodeId()) + " FAILED: "
          + first(e.reason()));
      case RunEvent.NodeSkipped e -> Ansi.dim("  - " + pad(e.nodeId()) + " skipped: "
          + e.reason());
      case RunEvent.NodeInvalidated e -> Ansi.magenta("  ^ " + pad(e.nodeId())
          + " invalidated: " + e.cause());
      case RunEvent.GraphExpanded e -> Ansi.blue("  * plan expanded into " + e.added()
          + (e.removed().isEmpty() ? "" : " (replacing " + e.removed() + ")"));
      case RunEvent.AttemptDiscarded e -> Ansi.magenta("  ^ " + pad(e.nodeId()) + " attempt "
          + e.attempt() + " discarded: " + e.reason());
      case RunEvent.AmendmentRejected e -> Ansi.red("    amendment refused: " + e.problems());
      case RunEvent.RollbackFailed e -> Ansi.bold(Ansi.red("    ROLLBACK FAILED: "
          + e.rollbackError()));
      case RunEvent.BudgetReset e -> Ansi.yellow("BUDGETS RESET by " + e.authorizedBy());
      case RunEvent.StopRequested e -> Ansi.bold(Ansi.red("SAFE STOP: " + e.reason()));
      case RunEvent.RunFinished e -> Ansi.bold("RUN finished: " + e.status()
          + (e.reason().isBlank() ? "" : " (" + e.reason() + ")"));
      default -> null;
    };
    if (line != null) {
      out.println(Ansi.dim(TIME.format(envelope.at())) + " " + line);
    }
  }

  private static String pad(String id) {
    return String.format("%-28s", id);
  }

  private static String first(String text) {
    String line = text == null ? "" : text.lines().filter(l -> !l.isBlank()).findFirst()
        .orElse("");
    return line.length() > 120 ? line.substring(0, 120) + "..." : line;
  }
}
