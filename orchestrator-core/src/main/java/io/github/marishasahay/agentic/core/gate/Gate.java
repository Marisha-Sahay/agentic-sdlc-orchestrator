package io.github.marishasahay.agentic.core.gate;

/**
 * A deterministic check at a node boundary. Entry gates guard preconditions; exit gates decide
 * whether an attempt's output is accepted. Gates are code and tools (compilers, tests, scanners),
 * never another model's opinion.
 */
public interface Gate {

  String name();

  /**
   * Whether the gate reads the workspace (e.g. runs a build). Such gates run under the workspace
   * lock so a parallel node can't change files underneath them.
   *
   * @return true if the gate needs a stable workspace
   */
  default boolean usesWorkspace() {
    return false;
  }

  /**
   * Evaluates the gate.
   *
   * @param context what the gate may inspect
   * @return pass/fail with evidence
   */
  GateResult evaluate(GateContext context);
}
