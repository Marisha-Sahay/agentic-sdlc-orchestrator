package io.github.marishasahay.agentic.core.gate;

/**
 * Outcome of a gate.
 *
 * @param gate gate name
 * @param passed whether the gate passed
 * @param evidence short explanation or tool output tail; fed back to the agent on failure
 */
public record GateResult(String gate, boolean passed, String evidence) {

  public static GateResult pass(String gate, String evidence) {
    return new GateResult(gate, true, evidence);
  }

  public static GateResult fail(String gate, String evidence) {
    return new GateResult(gate, false, evidence);
  }
}
