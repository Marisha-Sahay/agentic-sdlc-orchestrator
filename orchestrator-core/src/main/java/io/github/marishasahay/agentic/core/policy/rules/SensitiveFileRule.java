package io.github.marishasahay.agentic.core.policy.rules;

import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import io.github.marishasahay.agentic.core.policy.PolicyInput;
import io.github.marishasahay.agentic.core.policy.PolicyRule;
import io.github.marishasahay.agentic.core.policy.Severity;
import io.github.marishasahay.agentic.core.util.Globs;
import java.util.List;

/**
 * Flags changes to files whose modification is high-impact by nature, e.g. the build file (new
 * dependencies = supply-chain risk) or the public API contract (consumers may break).
 *
 * @param name rule name
 * @param glob files the rule applies to
 * @param reason why the change needs a human
 */
public record SensitiveFileRule(String name, String glob, String reason) implements PolicyRule {

  public static SensitiveFileRule dependencies() {
    return new SensitiveFileRule("dependency-change", "**/pom.xml",
        "build/dependency change (supply-chain and licence review)");
  }

  public static SensitiveFileRule apiContract() {
    return new SensitiveFileRule("api-contract-change", "**/openapi.yaml",
        "public API contract change (consumer impact)");
  }

  public static SensitiveFileRule securityConfig() {
    return new SensitiveFileRule("security-config-change", "**/*Security*.java",
        "security configuration change");
  }

  @Override
  public List<PolicyFinding> evaluate(PolicyInput input) {
    return input.changes().paths().stream()
        .filter(p -> Globs.matches(glob, p))
        .map(p -> new PolicyFinding(name, Severity.REQUIRE_APPROVAL, reason, p))
        .toList();
  }
}
