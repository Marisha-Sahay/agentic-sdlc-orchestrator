package io.github.marishasahay.agentic.core.policy;

import java.util.List;

/** A security, compliance or change-control rule evaluated against every proposal. */
public interface PolicyRule {

  String name();

  /**
   * Evaluates the proposal.
   *
   * @param input proposal and context
   * @return findings; empty when the rule has nothing to say
   */
  List<PolicyFinding> evaluate(PolicyInput input);
}
