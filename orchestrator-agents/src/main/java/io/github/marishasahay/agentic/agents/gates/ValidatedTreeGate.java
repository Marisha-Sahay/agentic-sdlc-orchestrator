package io.github.marishasahay.agentic.agents.gates;

import io.github.marishasahay.agentic.agents.AgentSupport;
import io.github.marishasahay.agentic.agents.ArtifactKeys;
import io.github.marishasahay.agentic.agents.analysis.Git;
import io.github.marishasahay.agentic.agents.contract.ValidationEvidence;
import io.github.marishasahay.agentic.core.gate.Gate;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;

/** Release readiness may only proceed on the exact tree final validation validated. */
public final class ValidatedTreeGate implements Gate {

  @Override
  public String name() {
    return "validated-tree";
  }

  @Override
  public GateResult evaluate(GateContext context) {
    String content = context.output(ArtifactKeys.VALIDATION);
    if (content == null) {
      return GateResult.fail(name(), "no final validation evidence");
    }
    ValidationEvidence evidence = AgentSupport.parse(content, ValidationEvidence.class);
    String current = Git.tree(context.workspaceRoot(), "HEAD");
    return current.equals(evidence.treeSha())
        ? GateResult.pass(name(), "workspace is the validated tree " + current.substring(0, 12))
        : GateResult.fail(name(), "workspace tree " + current.substring(0, 12)
            + " is not the validated tree " + evidence.treeSha().substring(0, 12));
  }
}
