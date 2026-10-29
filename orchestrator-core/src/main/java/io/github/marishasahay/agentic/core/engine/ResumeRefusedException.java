package io.github.marishasahay.agentic.core.engine;

/**
 * A run cannot be resumed safely: its audit log, its persisted artifacts or its identity do not
 * check out. Resuming anyway would rebuild state from evidence that cannot be trusted.
 */
public final class ResumeRefusedException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public ResumeRefusedException(String message) {
    super("Resume refused: " + message);
  }
}
