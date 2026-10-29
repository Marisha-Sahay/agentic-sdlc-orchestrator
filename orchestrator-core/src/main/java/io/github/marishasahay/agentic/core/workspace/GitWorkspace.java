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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Git-backed workspace. A private repository inside the run directory gives cheap checkpoints,
 * exact rollback, reviewable diffs and per-attempt snapshots (git worktrees) without ever
 * touching the developer's own repository.
 */
public final class GitWorkspace implements Workspace {

  static final String BASELINE_TAG = "baseline";
  private static final Set<String> SKIPPED_DIRS = Set.of("target", ".git", ".idea", "node_modules");

  private final Path root;
  private final Path snapshots;
  private final Object gitLock = new Object();

  private GitWorkspace(Path root) {
    this.root = root.toAbsolutePath().normalize();
    this.snapshots = this.root.resolveSibling(this.root.getFileName() + "-snapshots");
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
      workspace.git("tag", BASELINE_TAG);
      return workspace;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Opens an existing workspace (resume path) and forgets snapshots a crash left behind.
   *
   * @param root workspace directory containing {@code .git}
   * @return the workspace
   */
  public static GitWorkspace open(Path root) {
    if (!Files.isDirectory(root.resolve(".git"))) {
      throw new IllegalArgumentException("Not a git workspace: " + root);
    }
    GitWorkspace workspace = new GitWorkspace(root);
    deleteRecursively(workspace.snapshots);
    workspace.git("worktree", "prune");
    return workspace;
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
  public String baselineRevision() {
    return git("rev-parse", BASELINE_TAG + "^{commit}").trim();
  }

  @Override
  public boolean hasRevision(String revision) {
    return gitResult("rev-parse", "--verify", "--quiet", revision + "^{commit}").ok();
  }

  @Override
  public void apply(ChangeSet changes) {
    write(root, changes);
  }

  @Override
  public String checkpoint(String message) {
    synchronized (gitLock) {
      git("add", "-A");
      git("commit", "-q", "--allow-empty", "-m", message);
      return head();
    }
  }

  @Override
  public void rollbackTo(String checkpointId) {
    synchronized (gitLock) {
      git("reset", "-q", "--hard", checkpointId);
      git("clean", "-q", "-fd");
    }
  }

  @Override
  public String diff(String checkpointId) {
    synchronized (gitLock) {
      git("add", "-A");
      return git("diff", "--cached", checkpointId);
    }
  }

  @Override
  public List<String> changedFiles(String checkpointId) {
    synchronized (gitLock) {
      git("add", "-A");
      return lines(git("diff", "--cached", "--name-only", checkpointId));
    }
  }

  @Override
  public String treeHash() {
    synchronized (gitLock) {
      git("add", "-A");
      return git("write-tree").trim();
    }
  }

  @Override
  public String treeOf(String revision) {
    return git("rev-parse", revision + "^{tree}").trim();
  }

  @Override
  public Optional<String> readAt(String revision, String path) {
    resolveInside(path);
    GitResult result = gitResult("show", revision + ":" + path);
    return result.ok() ? Optional.of(result.output()) : Optional.empty();
  }

  @Override
  public List<String> changedBetween(String from, String to) {
    return lines(git("diff", "--name-only", from, to));
  }

  @Override
  public Snapshot snapshot(String revision) {
    Path dir = snapshots.resolve(UUID.randomUUID().toString());
    try {
      Files.createDirectories(snapshots);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    git("worktree", "add", "-q", "--detach", dir.toString(), revision);
    return new GitSnapshot(dir, revision);
  }

  @Override
  public String previewDiff(String revision, ChangeSet changes) {
    try (Snapshot preview = snapshot(revision)) {
      write(preview.root(), changes);
      run(preview.root(), "add", "-A");
      return run(preview.root(), "diff", "--cached", revision);
    }
  }

  /**
   * Resolves a relative path and refuses anything that escapes the workspace (absolute paths,
   * {@code ..} segments, the {@code .git} directory).
   *
   * @param relative workspace-relative path
   * @return absolute path inside the workspace
   */
  Path resolveInside(String relative) {
    return resolveInside(root, relative);
  }

  private static Path resolveInside(Path base, String relative) {
    Path candidate = base.resolve(relative).normalize();
    if (Path.of(relative).isAbsolute() || !candidate.startsWith(base)
        || candidate.startsWith(base.resolve(".git"))) {
      throw new SecurityException("Path escapes workspace: " + relative);
    }
    return candidate;
  }

  private static void write(Path base, ChangeSet changes) {
    for (FileChange change : changes.changes()) {
      Path target = resolveInside(base, change.path());
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

  private String git(String... args) {
    return run(root, args);
  }

  private GitResult gitResult(String... args) {
    return execute(root, args);
  }

  private String run(Path dir, String... args) {
    GitResult result = execute(dir, args);
    if (!result.ok()) {
      throw new IllegalStateException("git failed (" + result.exit() + "): "
          + String.join(" ", args) + "\n" + result.output());
    }
    return result.output();
  }

  private GitResult execute(Path dir, String... args) {
    List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    synchronized (gitLock) {
      try {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          throw new IllegalStateException("git timed out: " + command);
        }
        return new GitResult(process.exitValue(), output);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while running git", e);
      }
    }
  }

  private static List<String> lines(String out) {
    return out.isBlank() ? List.of() : Arrays.asList(out.strip().split("\n"));
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

  private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private record GitResult(int exit, String output) {
    boolean ok() {
      return exit == 0;
    }
  }

  private final class GitSnapshot implements Snapshot {
    private final Path dir;
    private final String revision;

    private GitSnapshot(Path dir, String revision) {
      this.dir = dir;
      this.revision = revision;
    }

    @Override
    public Path root() {
      return dir;
    }

    @Override
    public String revision() {
      return revision;
    }

    @Override
    public void close() {
      if (!gitResult("worktree", "remove", "--force", dir.toString()).ok()) {
        deleteRecursively(dir);
        gitResult("worktree", "prune");
      }
    }
  }
}
