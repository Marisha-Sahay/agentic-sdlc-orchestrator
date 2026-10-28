package io.github.marishasahay.agentic.core.policy.rules;

import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import io.github.marishasahay.agentic.core.policy.PolicyInput;
import io.github.marishasahay.agentic.core.policy.PolicyRule;
import io.github.marishasahay.agentic.core.policy.Severity;
import io.github.marishasahay.agentic.core.util.Globs;
import java.util.ArrayList;
import java.util.List;

/**
 * Autonomy boundary: agents may only touch allow-listed paths, and never the files that define
 * their own guardrails (CI workflows, Checkstyle rules, policy config). Without the second half
 * an agent could make a failing gate pass by weakening the gate.
 *
 * @param allowed globs agents may write
 * @param protectedPaths globs agents may never write, even if allowed
 */
public record WorkspaceScopeRule(List<String> allowed, List<String> protectedPaths)
    implements PolicyRule {

  public WorkspaceScopeRule {
    allowed = List.copyOf(allowed);
    protectedPaths = List.copyOf(protectedPaths);
  }

  @Override
  public String name() {
    return "workspace-scope";
  }

  @Override
  public List<PolicyFinding> evaluate(PolicyInput input) {
    List<PolicyFinding> findings = new ArrayList<>();
    for (String path : input.changes().paths()) {
      if (path.startsWith("/") || path.contains("..")) {
        findings.add(new PolicyFinding(name(), Severity.BLOCK,
            "path traversal outside the workspace is not allowed", path));
      } else if (protectedPaths.stream().anyMatch(g -> Globs.matches(g, path))) {
        findings.add(new PolicyFinding(name(), Severity.BLOCK,
            "governance file is protected from agent changes", path));
      } else if (allowed.stream().noneMatch(g -> Globs.matches(g, path))) {
        findings.add(new PolicyFinding(name(), Severity.BLOCK,
            "path is outside the agent's allowed scope " + allowed, path));
      }
    }
    return findings;
  }
}
