package io.github.marishasahay.agentic.core.engine;

/** Final status of a run. */
public enum RunStatus {
  /** Every node succeeded. */
  SUCCEEDED,
  /** At least one node failed; dependents were skipped. Resumable after a fix. */
  FAILED,
  /** Safe-stopped (kill switch or budget) with work remaining. Resumable. */
  PAUSED
}
