package io.github.marishasahay.agentic.core.policy.rules;

import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import io.github.marishasahay.agentic.core.policy.PolicyInput;
import io.github.marishasahay.agentic.core.policy.PolicyRule;
import io.github.marishasahay.agentic.core.policy.Severity;
import io.github.marishasahay.agentic.core.util.Globs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Least privilege per stage: a documentation node may only write documentation, a test node
 * only tests, and analysis or release nodes nothing at all. Besides limiting a confused agent,
 * this is what makes evidence ordering sound: once verification and security review have run,
 * the only stage still allowed to write cannot change code.
 *
 * @param scopes stage to the globs it may write; a stage without an entry may write nothing
 */
public record StageWriteScopeRule(Map<Stage, List<String>> scopes) implements PolicyRule {

  public StageWriteScopeRule {
    scopes = Map.copyOf(scopes);
  }

  @Override
  public String name() {
    return "stage-write-scope";
  }

  @Override
  public List<PolicyFinding> evaluate(PolicyInput input) {
    List<String> allowed = scopes.getOrDefault(input.node().stage(), List.of());
    String stage = input.node().stage().name().toLowerCase().replace('_', ' ');
    List<PolicyFinding> findings = new ArrayList<>();
    for (String path : input.changes().paths()) {
      if (allowed.stream().noneMatch(g -> Globs.matches(g, path))) {
        findings.add(new PolicyFinding(name(), Severity.BLOCK,
            stage + " nodes may only write " + allowed,
            path));
      }
    }
    return findings;
  }
}
