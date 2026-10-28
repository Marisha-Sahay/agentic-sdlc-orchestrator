package io.github.marishasahay.agentic.core.engine;

import java.util.List;

/**
 * A decision an agent made, recorded with its rationale so reviewers can audit why, not just
 * what. The engine attaches the consumed artifact versions as lineage.
 *
 * @param title what was decided
 * @param choice the option chosen
 * @param rationale why it was chosen
 * @param alternatives options considered and rejected
 */
public record Decision(String title, String choice, String rationale, List<String> alternatives) {

  public Decision {
    alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
  }
}
