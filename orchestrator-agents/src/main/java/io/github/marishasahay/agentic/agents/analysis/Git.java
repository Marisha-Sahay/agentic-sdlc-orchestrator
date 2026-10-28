package io.github.marishasahay.agentic.agents.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Read-only git queries agents use to see what the run has changed since the baseline. */
public final class Git {

  /** Tag the workspace's baseline commit carries. */
  public static final String BASELINE = "baseline";

  private Git() {}

  /**
   * Files changed since the baseline.
   *
   * @param root workspace root
   * @return workspace-relative paths
   */
  public static List<String> changedSinceBaseline(Path root) {
    String out = run(root, "diff", "--name-only", BASELINE, "HEAD");
    return out.isBlank() ? List.of() : Arrays.asList(out.strip().split("\n"));
  }

  /**
   * Unified diff since the baseline.
   *
   * @param root workspace root
   * @return patch
   */
  public static String diffSinceBaseline(Path root) {
    return run(root, "diff", BASELINE, "HEAD");
  }

  private static String run(Path root, String... args) {
    List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
    command.addAll(List.of(args));
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      process.waitFor(60, TimeUnit.SECONDS);
      if (process.exitValue() != 0) {
        throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
      }
      return output;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
