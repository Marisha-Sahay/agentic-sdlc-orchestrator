package io.github.marishasahay.agentic.agents.contract;

import io.github.marishasahay.agentic.core.engine.Decision;
import java.util.List;

/**
 * Decision as agents emit it; converted to the engine's {@link Decision} for lineage.
 *
 * @param title what was decided
 * @param choice option chosen
 * @param rationale why
 * @param alternatives rejected options
 */
public record DesignDecision(
    String title, String choice, String rationale, List<String> alternatives) {

  public Decision toDecision() {
    return new Decision(title, choice, rationale, alternatives);
  }
}
