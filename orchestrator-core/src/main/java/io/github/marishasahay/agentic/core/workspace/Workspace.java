package io.github.marishasahay.agentic.core.workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The isolated copy of the target codebase a run works on. Every accepted node is a checkpoint,
 * so rollback is "return to the last green checkpoint" rather than "undo what the agent did".
 *
 * <p>This is change isolation, not execution isolation: builds and tests run on these files
 * through the gates' own sandbox (see {@code CommandSandbox}).
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
   * The revision every run starts from, restored when a resumed run has no accepted change.
   *
   * @return baseline revision id
   */
  String baselineRevision();

  /**
   * Whether a revision exists (used to validate a checkpoint recorded in the audit log).
   *
   * @param revision revision id
   * @return true if it can be restored
   */
  boolean hasRevision(String revision);

  /**
   * Writes the proposed changes to disk. Paths escaping the workspace are rejected. May fail
   * part-way; callers restore the last checkpoint on failure.
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

  /**
   * Content hash of the working tree including untracked files: the identity that validation
   * evidence and acceptance decisions are bound to.
   *
   * @return tree hash
   */
  String treeHash();

  /**
   * Content hash of a committed revision's tree.
   *
   * @param revision revision id
   * @return tree hash
   */
  String treeOf(String revision);

  /**
   * Reads a file as of a revision, independent of what is currently on disk.
   *
   * @param revision revision id
   * @param path workspace-relative path
   * @return the content, or empty if the file does not exist in that revision
   */
  Optional<String> readAt(String revision, String path);

  /**
   * Files that differ between two revisions.
   *
   * @param from older revision
   * @param to newer revision
   * @return workspace-relative paths
   */
  List<String> changedBetween(String from, String to);

  /**
   * A consistent, read-only copy of a revision for an agent to read while other nodes write.
   *
   * @param revision revision to materialise
   * @return the snapshot; close it to delete it
   */
  Snapshot snapshot(String revision);

  /**
   * Diff a proposal would produce on a revision, computed in a throwaway snapshot: nothing is
   * written to the workspace and nothing is built or executed.
   *
   * @param revision base revision
   * @param changes proposed changes
   * @return patch text
   */
  String previewDiff(String revision, ChangeSet changes);

  /** A materialised revision; deleting it is the caller's job. */
  interface Snapshot extends AutoCloseable {

    Path root();

    String revision();

    @Override
    void close();
  }
}
