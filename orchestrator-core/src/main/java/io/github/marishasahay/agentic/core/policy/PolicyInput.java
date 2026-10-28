package io.github.marishasahay.agentic.core.policy;

import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.workspace.ChangeSet;
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
}
