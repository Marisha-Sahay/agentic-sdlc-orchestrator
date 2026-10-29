package io.github.marishasahay.agentic.core.approval;

import java.util.List;

/**
 * Checks a reviewer's amendment before it is accepted. An amended artifact replaces an agent's
 * output and is consumed downstream, so it has to satisfy the same contract as the original:
 * a reviewer's typo must not become an unparseable requirement spec or a broken contract.
 */
@FunctionalInterface
public interface AmendmentValidator {

  /** Accepts every amendment (the engine still checks keys and policy). */
  AmendmentValidator NONE = (key, content) -> List.of();

  /**
   * Validates one amended artifact.
   *
   * @param key artifact key
   * @param content proposed replacement content
   * @return human-readable problems; empty when the amendment is acceptable
   */
  List<String> validate(String key, String content);
}
