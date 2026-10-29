package io.github.marishasahay.agentic.app;

import io.github.marishasahay.agentic.agents.llm.LlmMode;
import java.util.Optional;

/**
 * Chooses who reviews a run, failing closed: nothing ever falls back to automatic approval.
 * Scripted decisions are demonstration behaviour and must be selected explicitly, except that
 * a scenario may declare them as its default for replays of recorded or hand-authored runs.
 */
final class ApprovalModes {

  static final String INTERACTIVE = "interactive";
  static final String SCRIPTED = "scripted";

  private ApprovalModes() {}

  /**
   * Resolves the approval mode of a new run.
   *
   * @param flag value of {@code --approvals}, if given
   * @param scenarioDefault the scenario's declared default, or null
   * @param mode model mode
   * @param console whether a terminal is attached
   * @return {@link #INTERACTIVE} or {@link #SCRIPTED}
   * @throws IllegalArgumentException for an unknown mode, or when no reviewer is available
   */
  static String resolve(Optional<String> flag, String scenarioDefault, LlmMode mode,
      boolean console) {
    if (flag.isPresent()) {
      return parse(flag.get());
    }
    if (scenarioDefault != null && mode == LlmMode.REPLAY) {
      return parse(scenarioDefault);
    }
    if (console) {
      return INTERACTIVE;
    }
    throw new IllegalArgumentException("No reviewer available: no terminal is attached and "
        + "--approvals was not given. Runs never approve automatically; use a terminal, or "
        + "pass --approvals scripted to apply the scenario's demonstration decisions.");
  }

  /**
   * Validates a mode name.
   *
   * @param value mode name
   * @return the normalised mode
   * @throws IllegalArgumentException if it is not a known mode
   */
  static String parse(String value) {
    return switch (value.strip().toLowerCase()) {
      case INTERACTIVE -> INTERACTIVE;
      case SCRIPTED -> SCRIPTED;
      default -> throw new IllegalArgumentException("Unknown approval mode '" + value
          + "' (use interactive or scripted)");
    };
  }
}
