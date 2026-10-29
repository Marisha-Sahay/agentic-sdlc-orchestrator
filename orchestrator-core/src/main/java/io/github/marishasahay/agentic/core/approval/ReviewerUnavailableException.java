package io.github.marishasahay.agentic.core.approval;

/**
 * No reviewer can decide a request: no terminal is attached, stdin closed, or no scripted
 * decision covers it. The engine never treats this as approval. It rolls back the attempt and
 * safe-stops the run (status PAUSED), so it can be resumed once a reviewer is available.
 */
public class ReviewerUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ReviewerUnavailableException(String message) {
    super(message);
  }
}
