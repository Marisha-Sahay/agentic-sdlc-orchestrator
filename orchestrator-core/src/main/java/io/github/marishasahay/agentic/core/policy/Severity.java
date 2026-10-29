package io.github.marishasahay.agentic.core.policy;

/** How a policy finding affects the node. */
public enum Severity {
  /** Recorded for audit only. */
  INFO,
  /** High-impact change: a human must accept the validated result before it is accepted. */
  REQUIRE_APPROVAL,
  /**
   * Execution-sensitive change (e.g. the build file, which runs arbitrary plugins): a human must
   * authorize this exact proposal before it is written or any build or test runs on it.
   */
  REQUIRE_AUTHORIZATION,
  /** Guardrail violation: the attempt is rejected and the agent must try again. */
  BLOCK
}
