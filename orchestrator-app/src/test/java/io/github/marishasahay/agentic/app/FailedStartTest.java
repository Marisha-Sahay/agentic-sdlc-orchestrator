package io.github.marishasahay.agentic.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the real CLI in a child JVM, because it ends with {@code System.exit}. */
class FailedStartTest {

  @TempDir
  Path repo;

  private record Outcome(int exitCode, String stderr) {}

  private Outcome agentic(String... args) throws Exception {
    List<String> command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), AgenticCli.class.getName()));
    command.addAll(List.of(args));
    Path stderr = repo.resolve("stderr.txt");
    ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(stderr.toFile());
    builder.environment().keySet()
        .removeIf(name -> name.startsWith("GEMINI") || name.startsWith("AGENTIC"));
    Process process = builder.start();
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
    return new Outcome(process.exitValue(), Files.readString(stderr));
  }

  @Test
  void aRunThatCannotStartLeavesItsRunIdFree() throws Exception {
    Files.writeString(repo.resolve("pom.xml"), "");
    Files.createDirectories(repo.resolve("scenarios"));
    Files.copy(Path.of("..", "scenarios", "drill.yaml"), repo.resolve("scenarios/drill.yaml"));

    Outcome noKey = agentic("run", "drill", "--llm", "live", "--approvals", "scripted",
        "--run-id", "no-key");
    Outcome badSandbox = agentic("run", "drill", "--llm", "replay", "--approvals", "scripted",
        "--sandbox", "nope", "--run-id", "bad-sandbox");

    assertThat(noKey.exitCode()).isEqualTo(1);
    assertThat(noKey.stderr()).contains("Live model calls need");
    assertThat(badSandbox.exitCode()).isEqualTo(1);
    assertThat(badSandbox.stderr()).contains("Unknown sandbox 'nope'");
    assertThat(repo.resolve(".runs/no-key")).doesNotExist();
    assertThat(repo.resolve(".runs/bad-sandbox")).doesNotExist();
  }
}
