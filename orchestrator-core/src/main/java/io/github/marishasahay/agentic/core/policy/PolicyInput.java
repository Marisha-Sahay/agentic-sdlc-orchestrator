package io.github.marishasahay.agentic.core.policy;

import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.workspace.ChangeSet;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * What a policy rule evaluates: the proposal, before anything touches the workspace.
 *
 * @param node the proposing node
 * @param changes proposed file changes
 * @param outputs proposed artifacts
 * @param workspaceRoot workspace as of the last checkpoint, or null
 */
public record PolicyInput(
    NodeSpec node, ChangeSet changes, Map<String, String> outputs, Path workspaceRoot) {

  /**
   * Whether a path already exists in the last checkpoint (i.e. the change modifies, not adds).
   *
   * @param relativePath workspace-relative path
   * @return true if the file exists
   */
  public boolean existsInWorkspace(String relativePath) {
    return workspaceRoot != null && Files.exists(workspaceRoot.resolve(relativePath));
  }

  /**
   * The proposal minus no-op writes (content identical to the last checkpoint). Change-control
   * rules use this so rewriting a file unchanged is not reported as a change.
   *
   * @return changes that actually alter the workspace
   */
  public ChangeSet effectiveChanges() {
    return new ChangeSet(changes.changes().stream().filter(c -> !isNoOp(c)).toList());
  }

  private boolean isNoOp(FileChange change) {
    if (change.operation() != FileChange.Operation.WRITE || !existsInWorkspace(change.path())) {
      return false;
    }
    try {
      return Files.readString(workspaceRoot.resolve(change.path()), StandardCharsets.UTF_8)
          .equals(change.content());
    } catch (IOException e) {
      return false;
    }
  }
}
