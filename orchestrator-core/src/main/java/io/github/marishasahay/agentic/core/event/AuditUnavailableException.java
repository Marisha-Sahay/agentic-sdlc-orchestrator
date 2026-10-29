package io.github.marishasahay.agentic.core.event;

/**
 * The audit log could not record an event. The run cannot continue: work that is not in the
 * log did not happen as far as resume, metrics and reviewers are concerned, so the engine stops
 * and restores the last accepted workspace state instead of carrying on unaudited.
 */
public class AuditUnavailableException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public AuditUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
