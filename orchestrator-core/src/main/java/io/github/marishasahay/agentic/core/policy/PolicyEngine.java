package io.github.marishasahay.agentic.core.policy;

import java.util.ArrayList;
import java.util.List;

/** Evaluates all rules against a proposal. A rule that throws is itself treated as a block. */
public final class PolicyEngine {

  private final List<PolicyRule> rules;

  public PolicyEngine(List<PolicyRule> rules) {
    this.rules = List.copyOf(rules);
  }

  public static PolicyEngine permissive() {
    return new PolicyEngine(List.of());
  }

  public List<PolicyRule> rules() {
    return rules;
  }

  /**
   * Runs every rule. Fails closed: an exception inside a rule becomes a BLOCK finding so a
   * broken rule can never silently let a change through.
   *
   * @param input proposal
   * @return all findings
   */
  public List<PolicyFinding> evaluate(PolicyInput input) {
    List<PolicyFinding> findings = new ArrayList<>();
    for (PolicyRule rule : rules) {
      try {
        findings.addAll(rule.evaluate(input));
      } catch (RuntimeException e) {
        findings.add(new PolicyFinding(rule.name(), Severity.BLOCK,
            "policy rule failed to evaluate (fail-closed): " + e.getMessage(), null));
      }
    }
    return findings;
  }

  public static boolean blocks(List<PolicyFinding> findings) {
    return findings.stream().anyMatch(f -> f.severity() == Severity.BLOCK);
  }

  public static boolean requiresApproval(List<PolicyFinding> findings) {
    return findings.stream().anyMatch(f -> f.severity() == Severity.REQUIRE_APPROVAL);
  }
}
