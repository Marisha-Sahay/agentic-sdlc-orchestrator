package io.github.marishasahay.agentic.app;

import io.github.marishasahay.agentic.core.approval.ApprovalDecision;
import io.github.marishasahay.agentic.core.approval.ApprovalGateway;
import io.github.marishasahay.agentic.core.approval.ApprovalKind;
import io.github.marishasahay.agentic.core.approval.ApprovalRequest;
import io.github.marishasahay.agentic.core.approval.ReviewerUnavailableException;
import io.github.marishasahay.agentic.core.gate.GateResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Interactive human checkpoint in the terminal. The reviewer sees why approval is needed, the
 * gate evidence, the proposed artifacts and the diff, and can approve, reject with feedback (the
 * agent retries with it) or edit any artifact before approving (the engine re-plans every node
 * that consumed the old version). Requests from parallel nodes are queued, one prompt at a time.
 */
public final class ConsoleApprovalGateway implements ApprovalGateway {

  private final BufferedReader in;
  private final PrintStream out;
  private final Path editDirectory;
  private final String reviewer;
  private final ReentrantLock oneAtATime = new ReentrantLock();

  /**
   * Creates the gateway.
   *
   * @param editDirectory where artifacts are written for the reviewer to edit
   * @param reviewer reviewer identity recorded in the audit log
   */
  public ConsoleApprovalGateway(Path editDirectory, String reviewer) {
    this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
        System.out, editDirectory, reviewer);
  }

  ConsoleApprovalGateway(BufferedReader in, PrintStream out, Path editDirectory,
      String reviewer) {
    this.in = in;
    this.out = out;
    this.editDirectory = editDirectory;
    this.reviewer = reviewer;
  }

  @Override
  public ApprovalDecision decide(ApprovalRequest request) {
    oneAtATime.lock();
    try {
      printRequest(request);
      Map<String, String> amendments = new LinkedHashMap<>();
      while (true) {
        out.print(Ansi.bold("\n[a]pprove  [r]eject  [e]dit artifact  [v]iew artifact  "
            + "[d]iff > "));
        out.flush();
        String choice = readLine().trim().toLowerCase();
        switch (choice) {
          case "a" -> {
            String comment = prompt("Approval comment (optional): ");
            return new ApprovalDecision(true, reviewer, comment, amendments);
          }
          case "r" -> {
            String comment = prompt("What should change? (fed back to the agent): ");
            return ApprovalDecision.reject(reviewer, comment);
          }
          case "e" -> edit(request, amendments);
          case "v" -> view(request);
          case "d" -> out.println(request.diff().isBlank() ? "(no workspace changes)"
              : request.diff());
          default -> out.println("Unknown choice.");
        }
      }
    } finally {
      oneAtATime.unlock();
    }
  }

  private void printRequest(ApprovalRequest request) {
    out.println();
    out.println(Ansi.yellow("=".repeat(78)));
    out.println(Ansi.bold(Ansi.yellow((request.kind() == ApprovalKind.AUTHORIZATION
        ? "AUTHORIZATION REQUIRED BEFORE EXECUTION: " : "HUMAN APPROVAL REQUIRED: ")
        + request.nodeId() + " (" + request.stage().name().toLowerCase().replace('_', ' ')
        + ")")));
    out.println(Ansi.yellow("=".repeat(78)));
    out.println(Ansi.bold("Decision applies to: ") + "proposal "
        + request.proposalSha().substring(0, 12)
        + (request.treeSha() == null ? " (nothing has been written or executed yet)"
            : ", validated tree " + request.treeSha().substring(0, 12)));
    request.problems().forEach(p -> out.println(Ansi.red("Previous decision refused: " + p)));
    out.println(Ansi.bold("Why:"));
    request.reasons().forEach(r -> out.println("  - " + r));
    out.println(Ansi.bold("Agent summary:"));
    out.println("  " + request.summary());
    out.println(Ansi.bold("Gate evidence:"));
    for (GateResult gate : request.gateResults()) {
      out.println("  " + (gate.passed() ? Ansi.green("PASS") : Ansi.red("FAIL")) + " "
          + gate.gate() + ": " + firstLine(gate.evidence()));
    }
    out.println(Ansi.bold("Proposed artifacts: ") + String.join(", ",
        request.proposedOutputs().keySet()));
    long files = request.diff().lines().filter(l -> l.startsWith("diff --git")).count();
    out.println(Ansi.bold("Workspace changes: ") + files + " file(s)");
  }

  private void view(ApprovalRequest request) {
    String key = chooseKey(request.proposedOutputs().keySet().stream().toList());
    if (key != null) {
      out.println(request.proposedOutputs().get(key));
    }
  }

  private void edit(ApprovalRequest request, Map<String, String> amendments) {
    List<String> keys = new ArrayList<>(request.proposedOutputs().keySet());
    out.println("You can also amend an upstream artifact by typing its key "
        + "(e.g. requirements.spec, clarification.answers).");
    String key = chooseKey(keys);
    if (key == null) {
      return;
    }
    try {
      Files.createDirectories(editDirectory);
      Path file = editDirectory.resolve(key + ".txt");
      String current = amendments.getOrDefault(key, request.proposedOutputs().getOrDefault(key,
          ""));
      Files.writeString(file, current, StandardCharsets.UTF_8);
      out.println("Edit " + Ansi.bold(file.toString()) + " in your editor, save, then press "
          + "Enter here.");
      readLine();
      amendments.put(key, Files.readString(file, StandardCharsets.UTF_8));
      out.println(Ansi.green("Amendment to " + key + " staged; approve to apply it. Every node "
          + "that consumed the previous version will be re-planned."));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String chooseKey(List<String> keys) {
    for (int i = 0; i < keys.size(); i++) {
      out.println("  " + (i + 1) + ") " + keys.get(i));
    }
    String answer = prompt("Artifact number or key: ").trim();
    if (answer.matches("\\d+")) {
      int index = Integer.parseInt(answer) - 1;
      return index >= 0 && index < keys.size() ? keys.get(index) : null;
    }
    return answer.isBlank() ? null : answer;
  }

  private String prompt(String label) {
    out.print(label);
    out.flush();
    return readLine();
  }

  private String readLine() {
    try {
      String line = in.readLine();
      if (line == null) {
        throw new ReviewerUnavailableException("stdin closed while waiting for a reviewer; the "
            + "run pauses and can be resumed");
      }
      return line;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String firstLine(String text) {
    String line = text == null ? "" : text.lines().findFirst().orElse("");
    return line.length() > 110 ? line.substring(0, 110) + "..." : line;
  }
}
