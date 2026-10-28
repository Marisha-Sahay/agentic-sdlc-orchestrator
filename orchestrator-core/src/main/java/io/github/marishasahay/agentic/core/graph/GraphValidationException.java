package io.github.marishasahay.agentic.core.graph;

/** Raised when a graph mutation would leave an invalid graph (cycle, unknown or duplicate id). */
public class GraphValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public GraphValidationException(String message) {
    super(message);
  }
}
