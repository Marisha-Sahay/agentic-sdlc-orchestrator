package io.github.marishasahay.agentic.core.policy;

/** How a policy finding affects the node. */
public enum Severity {
  /** Recorded for audit only. */
  INFO,
  /** High-impact change: a human must approve before it is accepted. */
  REQUIRE_APPROVAL,
  /** Guardrail violation: the attempt is rejected and the agent must try again. */
  BLOCK
}
