package io.github.marishasahay.agentic.agents.contract;

import java.util.List;

/**
 * Security review of the change.
 *
 * @param summary overall assessment
 * @param findings individual findings
 */
public record SecurityReview(String summary, List<Finding> findings) {

  /**
   * A finding.
   *
   * @param severity HIGH, MEDIUM or LOW
   * @param source {@code scanner} (deterministic) or {@code reviewer} (model)
   * @param category OWASP-style category
   * @param location file or component
   * @param description issue
   * @param recommendation fix
   */
  public record Finding(
      String severity, String source, String category, String location, String description,
      String recommendation) {}
}
