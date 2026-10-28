package io.github.marishasahay.agentic.core.gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a build or analysis tool in the workspace and passes on exit code 0. The tail of the tool
 * output becomes the evidence that is shown to reviewers and fed back to the agent on failure.
 *
 * @param name gate name, e.g. {@code maven-verify}
 * @param command command and arguments
 * @param timeout hard limit; the process is killed when exceeded
 * @param evidenceLines how many trailing output lines to keep
 */
public record CommandGate(String name, List<String> command, Duration timeout, int evidenceLines)
    implements Gate {

  public CommandGate {
    command = List.copyOf(command);
  }

  public static CommandGate of(String name, Duration timeout, String... command) {
    return new CommandGate(name, List.of(command), timeout, 60);
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
    try {
      Path log = Files.createTempFile("gate-" + name, ".log");
      try {
        Process process = new ProcessBuilder(command).directory(dir.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
          return GateResult.fail(name, "timed out after " + timeout + ": " + command);
        }
        String tail = tail(Files.readAllLines(log, StandardCharsets.UTF_8));
        return process.exitValue() == 0
            ? GateResult.pass(name, "exit 0: " + String.join(" ", command))
            : GateResult.fail(name, "exit " + process.exitValue() + ": "
                + String.join(" ", command) + "\n" + tail);
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
