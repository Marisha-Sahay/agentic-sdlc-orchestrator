package io.github.marishasahay.agentic.agents.gates;

import io.github.marishasahay.agentic.agents.analysis.Git;
import io.github.marishasahay.agentic.agents.analysis.SecurityScanner;
import io.github.marishasahay.agentic.agents.contract.SecurityReview;
import io.github.marishasahay.agentic.core.gate.Gate;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;
import java.util.List;

/** The deterministic security scan, re-run on the exact tree being validated. */
public final class SecurityScanGate implements Gate {

  @Override
  public String name() {
    return "security-scan";
  }

  @Override
  public boolean usesWorkspace() {
    return true;
  }

  @Override
  public GateResult evaluate(GateContext context) {
    List<String> changed = Git.changedSinceBaseline(context.workspaceRoot());
    List<SecurityReview.Finding> findings = SecurityScanner.scan(context.workspaceRoot(),
        changed);
    List<String> blocking = findings.stream().filter(f -> "HIGH".equals(f.severity()))
        .map(f -> f.category() + " in " + f.location() + ": " + f.description()).toList();
    return blocking.isEmpty()
        ? GateResult.pass(name(), "no HIGH scanner findings in " + changed.size()
            + " changed files (" + findings.size() + " lower-severity)")
        : GateResult.fail(name(), "HIGH findings:\n- " + String.join("\n- ", blocking));
  }
}
