package io.github.marishasahay.agentic.core.policy.rules;

import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import io.github.marishasahay.agentic.core.policy.PolicyInput;
import io.github.marishasahay.agentic.core.policy.PolicyRule;
import io.github.marishasahay.agentic.core.policy.Severity;
import io.github.marishasahay.agentic.core.util.Globs;
import java.util.List;

/**
 * Flags changes to files whose modification is high-impact by nature, e.g. the build file (new
 * dependencies = supply-chain risk, and plugins execute code during the build) or the public
 * API contract (consumers may break).
 *
 * @param name rule name
 * @param glob files the rule applies to
 * @param reason why the change needs a human
 * @param severity {@link Severity#REQUIRE_AUTHORIZATION} when the file is executed by the gates
 *     (a human must allow it before any build runs), otherwise {@link Severity#REQUIRE_APPROVAL}
 */
public record SensitiveFileRule(String name, String glob, String reason, Severity severity)
    implements PolicyRule {

  /**
   * The build file: authorization before execution, because a build runs whatever plugins and
   * extensions it declares.
   *
   * @return the rule
   */
  public static SensitiveFileRule dependencies() {
    return new SensitiveFileRule("dependency-change", "**/pom.xml",
        "build/dependency change (supply-chain and licence review; the build executes it)",
        Severity.REQUIRE_AUTHORIZATION);
  }

  public static SensitiveFileRule apiContract() {
    return new SensitiveFileRule("api-contract-change", "**/openapi.yaml",
        "public API contract change (consumer impact)", Severity.REQUIRE_APPROVAL);
  }

  public static SensitiveFileRule securityConfig() {
    return new SensitiveFileRule("security-config-change", "**/*Security*.java",
        "security configuration change", Severity.REQUIRE_APPROVAL);
  }

  @Override
  public List<PolicyFinding> evaluate(PolicyInput input) {
    return input.effectiveChanges().paths().stream()
        .filter(p -> Globs.matches(glob, p))
        .map(p -> new PolicyFinding(name, severity, reason, p))
        .toList();
  }
}
