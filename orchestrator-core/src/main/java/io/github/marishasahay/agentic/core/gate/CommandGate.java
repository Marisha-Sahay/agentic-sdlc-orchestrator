package io.github.marishasahay.agentic.core.gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a build or analysis tool in the workspace, inside a {@link CommandSandbox}, and passes on
 * exit code 0. The tail of the tool output becomes the evidence that is shown to reviewers and
 * fed back to the agent on failure; the sandbox description is part of that evidence.
 *
 * @param name gate name, e.g. {@code maven-verify}
 * @param command command and arguments
 * @param timeout hard limit; the process is killed when exceeded
 * @param evidenceLines how many trailing output lines to keep
 * @param sandbox where the command runs
 */
public record CommandGate(String name, List<String> command, Duration timeout, int evidenceLines,
    CommandSandbox sandbox) implements Gate {

  public CommandGate {
    command = List.copyOf(command);
    sandbox = sandbox == null ? CommandSandbox.host() : sandbox;
  }

  public static CommandGate of(String name, Duration timeout, String... command) {
    return new CommandGate(name, List.of(command), timeout, 60, CommandSandbox.host());
  }

  /**
   * The same gate run in another sandbox.
   *
   * @param value sandbox
   * @return the gate
   */
  public CommandGate in(CommandSandbox value) {
    return new CommandGate(name, command, timeout, evidenceLines, value);
  }

  @Override
  public boolean usesWorkspace() {
    return true;
  }

  @Override
  public GateResult evaluate(GateContext context) {
    Path dir = context.workspaceRoot();
    if (dir == null) {
      return GateResult.fail(name, "no workspace to run " + command + " in");
    }
    String where = "\n[sandbox] " + sandbox.describe();
    try {
      Path log = Files.createTempFile("gate-" + name, ".log");
      try (CommandSandbox.Execution execution = sandbox.start(command, dir, log)) {
        Process process = execution.process();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
          return GateResult.fail(name, "timed out after " + timeout + ": " + command + where);
        }
        String tail = tail(Files.readAllLines(log, StandardCharsets.UTF_8));
        return process.exitValue() == 0
            ? GateResult.pass(name, "exit 0: " + String.join(" ", command) + where)
            : GateResult.fail(name, "exit " + process.exitValue() + ": "
                + String.join(" ", command) + where + "\n" + tail);
      } finally {
        Files.deleteIfExists(log);
      }
    } catch (IOException e) {
      return GateResult.fail(name, "could not run " + command + ": " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return GateResult.fail(name, "interrupted");
    }
  }

  private String tail(List<String> lines) {
    List<String> relevant = lines.stream()
        .filter(l -> !l.startsWith("[INFO] Download") && !l.isBlank())
        .toList();
    int from = Math.max(0, relevant.size() - evidenceLines);
    return String.join("\n", relevant.subList(from, relevant.size()));
  }
}
