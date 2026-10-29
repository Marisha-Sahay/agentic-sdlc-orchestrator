package io.github.marishasahay.agentic.core.policy;

import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.workspace.ChangeSet;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * What a policy rule evaluates: the proposal, before anything touches the workspace, and the
 * revision it was made against.
 *
 * @param node the proposing node
 * @param changes proposed file changes
 * @param outputs proposed artifacts
 * @param base files as of the revision the proposal was made against
 */
public record PolicyInput(
    NodeSpec node, ChangeSet changes, Map<String, String> outputs, BaseFiles base) {

  /**
   * Convenience for a base that is a plain directory (tests, or no workspace when null).
   *
   * @param node the proposing node
   * @param changes proposed file changes
   * @param outputs proposed artifacts
   * @param workspaceRoot directory holding the base files, or null
   */
  public PolicyInput(NodeSpec node, ChangeSet changes, Map<String, String> outputs,
      Path workspaceRoot) {
    this(node, changes, outputs, BaseFiles.directory(workspaceRoot));
  }

  /** Read access to the base revision. */
  @FunctionalInterface
  public interface BaseFiles {

    /**
     * Reads a file of the base revision.
     *
     * @param path workspace-relative path
     * @return its content, or empty if it does not exist
     */
    Optional<String> read(String path);

    /**
     * Base files read from a directory.
     *
     * @param root directory, or null for "no files"
     * @return the reader
     */
    static BaseFiles directory(Path root) {
      return path -> {
        if (root == null || !Files.isRegularFile(root.resolve(path))) {
          return Optional.empty();
        }
        try {
          return Optional.of(Files.readString(root.resolve(path), StandardCharsets.UTF_8));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      };
    }
  }

  /**
   * Whether a path exists in the base revision (i.e. the change modifies, not adds).
   *
   * @param relativePath workspace-relative path
   * @return true if the file exists
   */
  public boolean existsInWorkspace(String relativePath) {
    return base.read(relativePath).isPresent();
  }

  /**
   * The proposal minus no-op writes (content identical to the base revision). Change-control
   * rules use this so rewriting a file unchanged is not reported as a change.
   *
   * @return changes that actually alter the workspace
   */
  public ChangeSet effectiveChanges() {
    return new ChangeSet(changes.changes().stream().filter(c -> !isNoOp(c)).toList());
  }

  private boolean isNoOp(FileChange change) {
    return change.operation() == FileChange.Operation.WRITE
        && base.read(change.path()).map(change.content()::equals).orElse(false);
  }
}
