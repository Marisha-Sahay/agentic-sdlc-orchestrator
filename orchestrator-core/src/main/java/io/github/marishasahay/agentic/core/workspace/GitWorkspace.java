package io.github.marishasahay.agentic.core.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Git-backed workspace. A private repository inside the run directory gives cheap checkpoints,
 * exact rollback and reviewable diffs without ever touching the developer's own repository.
 */
public final class GitWorkspace implements Workspace {

  private static final Set<String> SKIPPED_DIRS = Set.of("target", ".git", ".idea", "node_modules");

  private final Path root;

  private GitWorkspace(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  /**
   * Copies {@code source} (without build output) into {@code root} and makes it a repository
   * with a single baseline commit.
   *
   * @param source directory to copy, or null for an empty workspace
   * @param root target directory, must not exist or be empty
   * @return the initialised workspace
   */
  public static GitWorkspace createFrom(Path source, Path root) {
    try {
      Files.createDirectories(root);
      if (source != null) {
        copyTree(source, root);
      }
      GitWorkspace workspace = new GitWorkspace(root);
      workspace.git("init", "-q", "-b", "main");
      workspace.git("config", "user.name", "agentic-orchestrator");
      workspace.git("config", "user.email", "orchestrator@agentic.local");
      workspace.git("config", "commit.gpgsign", "false");
      Files.writeString(root.resolve(".gitignore"), "target/\n", StandardCharsets.UTF_8);
      workspace.checkpoint("baseline");
      workspace.git("tag", "baseline");
      return workspace;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Opens an existing workspace (resume path).
   *
   * @param root workspace directory containing {@code .git}
   * @return the workspace
   */
  public static GitWorkspace open(Path root) {
    if (!Files.isDirectory(root.resolve(".git"))) {
      throw new IllegalArgumentException("Not a git workspace: " + root);
    }
    return new GitWorkspace(root);
  }

  @Override
  public Path root() {
    return root;
  }

  @Override
  public String head() {
    return git("rev-parse", "HEAD").trim();
  }

  @Override
  public void apply(ChangeSet changes) {
    for (FileChange change : changes.changes()) {
      Path target = resolveInside(change.path());
      try {
        if (change.operation() == FileChange.Operation.DELETE) {
          Files.deleteIfExists(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.writeString(target, change.content(), StandardCharsets.UTF_8);
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot apply change to " + change.path(), e);
      }
    }
  }

  @Override
  public String checkpoint(String message) {
    git("add", "-A");
    git("commit", "-q", "--allow-empty", "-m", message);
    return head();
  }

  @Override
  public void rollbackTo(String checkpointId) {
    git("reset", "-q", "--hard", checkpointId);
    git("clean", "-q", "-fd");
  }

  @Override
  public String diff(String checkpointId) {
    git("add", "-A");
    return git("diff", "--cached", checkpointId);
  }

  @Override
  public List<String> changedFiles(String checkpointId) {
    git("add", "-A");
    String out = git("diff", "--cached", "--name-only", checkpointId);
    return out.isBlank() ? List.of() : Arrays.asList(out.strip().split("\n"));
  }

  /**
   * Resolves a relative path and refuses anything that escapes the workspace (absolute paths,
   * {@code ..} segments, the {@code .git} directory).
   *
   * @param relative workspace-relative path
   * @return absolute path inside the workspace
   */
  Path resolveInside(String relative) {
    Path candidate = root.resolve(relative).normalize();
    if (Path.of(relative).isAbsolute() || !candidate.startsWith(root)
        || candidate.startsWith(root.resolve(".git"))) {
      throw new SecurityException("Path escapes workspace: " + relative);
    }
    return candidate;
  }

  private String git(String... args) {
    List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
    command.addAll(List.of(args));
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("git timed out: " + command);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("git failed (" + process.exitValue() + "): "
            + String.join(" ", args) + "\n" + output);
      }
      return output;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while running git", e);
    }
  }

  private static void copyTree(Path source, Path target) throws IOException {
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        if (!dir.equals(source) && SKIPPED_DIRS.contains(dir.getFileName().toString())) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        Files.createDirectories(target.resolve(source.relativize(dir).toString()));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.copy(file, target.resolve(source.relativize(file).toString()));
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
