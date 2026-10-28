package io.github.marishasahay.agentic.core.workspace;

import java.nio.file.Path;
import java.util.List;

/**
 * The isolated copy of the target codebase a run works on. Every accepted node is a checkpoint,
 * so rollback is "return to the last green checkpoint" rather than "undo what the agent did".
 */
public interface Workspace {

  Path root();

  /**
   * Current checkpoint id.
   *
   * @return opaque id (a commit hash for git)
   */
  String head();

  /**
   * Writes the proposed changes to disk. Paths escaping the workspace are rejected.
   *
   * @param changes changes to apply
   */
  void apply(ChangeSet changes);

  /**
   * Records the current state as a checkpoint.
   *
   * @param message description, e.g. the node and attempt
   * @return the new checkpoint id
   */
  String checkpoint(String message);

  /**
   * Discards everything after the given checkpoint, including untracked files.
   *
   * @param checkpointId checkpoint to return to
   */
  void rollbackTo(String checkpointId);

  /**
   * Unified diff of the working tree (tracked and untracked) against a checkpoint.
   *
   * @param checkpointId base checkpoint
   * @return patch text
   */
  String diff(String checkpointId);

  /**
   * Files that differ from a checkpoint.
   *
   * @param checkpointId base checkpoint
   * @return workspace-relative paths
   */
  List<String> changedFiles(String checkpointId);
}
