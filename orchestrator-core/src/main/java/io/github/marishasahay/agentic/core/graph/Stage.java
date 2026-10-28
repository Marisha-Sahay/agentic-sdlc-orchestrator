package io.github.marishasahay.agentic.core.graph;

/** SDLC stage a node belongs to. Used for grouping, metrics and reporting, not for ordering. */
public enum Stage {
  REQUIREMENTS,
  CLARIFICATION,
  CODEBASE_ANALYSIS,
  DESIGN,
  PLANNING,
  IMPLEMENTATION,
  TESTING,
  DOCUMENTATION,
  SECURITY_REVIEW,
  VERIFICATION,
  RELEASE_READINESS
}
