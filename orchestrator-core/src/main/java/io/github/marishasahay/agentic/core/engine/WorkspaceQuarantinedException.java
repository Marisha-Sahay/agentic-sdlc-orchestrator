package io.github.marishasahay.agentic.core.engine;

/**
 * Restoring the accepted workspace state failed. Nothing may apply changes to the workspace
 * any more in this process; the run safe-stops and a human must inspect it before resuming.
 */
final class WorkspaceQuarantinedException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  WorkspaceQuarantinedException(String message) {
    super(message);
  }
}
