package io.github.marishasahay.agentic.agents.gates;

import io.github.marishasahay.agentic.agents.AgentSupport;
import io.github.marishasahay.agentic.agents.ArtifactKeys;
import io.github.marishasahay.agentic.agents.analysis.CodebaseIndex;
import io.github.marishasahay.agentic.agents.contract.ImpactAnalysis;
import io.github.marishasahay.agentic.core.gate.Gate;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;
import java.util.List;

/**
 * Hallucination guard for brownfield work: every file the impact analysis claims to MODIFY or
 * DELETE must exist in the deterministic codebase index, and every ADD must not already exist.
 */
public final class ImpactGroundingGate implements Gate {

  @Override
  public String name() {
    return "impact-grounding";
  }

  @Override
  public GateResult evaluate(GateContext context) {
    CodebaseIndex index = AgentSupport.parse(context.output(ArtifactKeys.CODEBASE_INDEX),
        CodebaseIndex.class);
    ImpactAnalysis impact;
    try {
      impact = AgentSupport.parse(context.output(ArtifactKeys.IMPACT), ImpactAnalysis.class);
    } catch (IllegalStateException e) {
      return GateResult.fail(name(), e.getMessage());
    }
    List<String> problems = AgentSupport.list(impact.components()).stream()
        .map(c -> {
          String change = c.change() == null ? "" : c.change().toUpperCase();
          boolean exists = index.contains(c.path());
          if ((change.equals("MODIFY") || change.equals("DELETE")) && !exists) {
            return change + " " + c.path() + ": no such file in the codebase index";
          }
          if (change.equals("ADD") && exists) {
            return "ADD " + c.path() + ": file already exists (use MODIFY)";
          }
          return null;
        })
        .filter(p -> p != null)
        .toList();
    if (AgentSupport.list(impact.components()).isEmpty()) {
      return GateResult.fail(name(), "impact analysis lists no affected components");
    }
    return problems.isEmpty()
        ? GateResult.pass(name(), impact.components().size() + " components, all grounded")
        : GateResult.fail(name(), String.join("\n", problems));
  }
}
