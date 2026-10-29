package io.github.marishasahay.agentic.core.approval;

/**
 * The two different questions a reviewer can be asked about a proposal. They are kept apart
 * because they protect different things: authorization protects the machine (nothing the
 * proposal contains is executed until a human allows it), acceptance protects the codebase
 * (nothing is accepted until a human has seen the validated result).
 */
public enum ApprovalKind {
  /**
   * Before execution: may the engine apply this exact proposal and run builds or tests on it?
   * Asked for execution-sensitive changes such as build files, before anything is written.
   */
  AUTHORIZATION,
  /**
   * After validation: may the engine accept this exact, gate-validated tree? Asked for
   * mandatory sign-offs and high-impact changes, after every exit gate passed.
   */
  ACCEPTANCE
}
