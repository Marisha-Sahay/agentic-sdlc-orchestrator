package io.github.marishasahay.agentic.core.graph;

/**
 * When a node must stop for a human decision before its outputs are accepted. There is
 * deliberately no "never" option: if the policy engine classifies a change as high impact, a
 * human approves it regardless of how the workflow author configured the node.
 */
public enum ApprovalMode {
  /** Ask only when the policy engine flags the proposal as high impact. */
  POLICY,
  /** Always ask (design sign-off, release sign-off). */
  ALWAYS
}
