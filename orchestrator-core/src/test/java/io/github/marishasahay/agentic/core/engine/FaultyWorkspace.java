package io.github.marishasahay.agentic.core.engine;

import io.github.marishasahay.agentic.core.workspace.ChangeSet;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import io.github.marishasahay.agentic.core.workspace.Workspace;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Delegating workspace that injects failures at precise points of the acceptance protocol. */
final class FaultyWorkspace implements Workspace {

  private final Workspace delegate;
  volatile int failApplyAfterFiles = -1;
  volatile boolean failCheckpoint;
  volatile boolean failRollback;

  FaultyWorkspace(Workspace delegate) {
    this.delegate = delegate;
  }

  @Override
  public void apply(ChangeSet changes) {
    if (failApplyAfterFiles < 0) {
      delegate.apply(changes);
      return;
    }
    List<FileChange> all = changes.changes();
    delegate.apply(new ChangeSet(all.subList(0, Math.min(failApplyAfterFiles, all.size()))));
    failApplyAfterFiles = -1;
    throw new IllegalStateException("disk full while writing " + all.getLast().path());
  }

  @Override
  public String checkpoint(String message) {
    if (failCheckpoint) {
      failCheckpoint = false;
      throw new IllegalStateException("git commit failed: index.lock exists");
    }
    return delegate.checkpoint(message);
  }

  @Override
  public void rollbackTo(String checkpointId) {
    if (failRollback) {
      throw new IllegalStateException("git reset failed: permission denied");
    }
    delegate.rollbackTo(checkpointId);
  }

  @Override
  public Path root() {
    return delegate.root();
  }

  @Override
  public String head() {
    return delegate.head();
  }

  @Override
  public String baselineRevision() {
    return delegate.baselineRevision();
  }

  @Override
  public boolean hasRevision(String revision) {
    return delegate.hasRevision(revision);
  }

  @Override
  public String diff(String checkpointId) {
    return delegate.diff(checkpointId);
  }

  @Override
  public List<String> changedFiles(String checkpointId) {
    return delegate.changedFiles(checkpointId);
  }

  @Override
  public String treeHash() {
    return delegate.treeHash();
  }

  @Override
  public String treeOf(String revision) {
    return delegate.treeOf(revision);
  }

  @Override
  public Optional<String> readAt(String revision, String path) {
    return delegate.readAt(revision, path);
  }

  @Override
  public List<String> changedBetween(String from, String to) {
    return delegate.changedBetween(from, to);
  }

  @Override
  public Snapshot snapshot(String revision) {
    return delegate.snapshot(revision);
  }

  @Override
  public String previewDiff(String revision, ChangeSet changes) {
    return delegate.previewDiff(revision, changes);
  }
}
