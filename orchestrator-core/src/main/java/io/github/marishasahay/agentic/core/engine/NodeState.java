package io.github.marishasahay.agentic.core.engine;

/** Lifecycle state of a node within a run. */
public enum NodeState {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  /** Not run because an upstream node failed. */
  SKIPPED;

  public boolean isTerminalFailure() {
    return this == FAILED || this == SKIPPED;
  }
}
