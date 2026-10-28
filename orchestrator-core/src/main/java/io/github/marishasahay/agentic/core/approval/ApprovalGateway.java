package io.github.marishasahay.agentic.core.approval;

/**
 * Where approval requests go: a console prompt, a web UI, a ticketing system. Blocking is fine:
 * nodes run on virtual threads, so a node waiting for a human costs almost nothing.
 */
@FunctionalInterface
public interface ApprovalGateway {

  /**
   * Blocks until a reviewer decides.
   *
   * @param request what needs approval and why
   * @return the decision
   */
  ApprovalDecision decide(ApprovalRequest request);
}
