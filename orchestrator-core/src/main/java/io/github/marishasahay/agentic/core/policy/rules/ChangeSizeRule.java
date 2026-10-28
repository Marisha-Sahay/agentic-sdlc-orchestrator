package io.github.marishasahay.agentic.core.policy.rules;

import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import io.github.marishasahay.agentic.core.policy.PolicyInput;
import io.github.marishasahay.agentic.core.policy.PolicyRule;
import io.github.marishasahay.agentic.core.policy.Severity;
import java.util.List;

/**
 * Large changes are harder to review and riskier to merge; past a threshold a human looks.
 *
 * @param maxLines lines touched before approval is required
 */
public record ChangeSizeRule(int maxLines) implements PolicyRule {

  @Override
  public String name() {
    return "change-size";
  }

  @Override
  public List<PolicyFinding> evaluate(PolicyInput input) {
    int lines = input.changes().linesTouched();
    return lines > maxLines
        ? List.of(new PolicyFinding(name(), Severity.REQUIRE_APPROVAL,
            "change touches " + lines + " lines (limit " + maxLines + ")", null))
        : List.of();
  }
}
