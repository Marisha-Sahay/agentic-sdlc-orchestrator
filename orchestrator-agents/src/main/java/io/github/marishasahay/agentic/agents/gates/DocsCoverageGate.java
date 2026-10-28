package io.github.marishasahay.agentic.agents.gates;

import io.github.marishasahay.agentic.agents.ArtifactKeys;
import io.github.marishasahay.agentic.agents.analysis.SourceContext;
import io.github.marishasahay.agentic.core.gate.Gate;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;
import java.util.List;

/** Documentation must mention every path in the API contract. */
public final class DocsCoverageGate implements Gate {

  @Override
  public String name() {
    return "docs-coverage";
  }

  @Override
  public boolean usesWorkspace() {
    return true;
  }

  @Override
  public GateResult evaluate(GateContext context) {
    String readme = SourceContext.readIfExists(context.workspaceRoot(), "README.md");
    List<String> missing = OpenApiDocument.parse(context.output(ArtifactKeys.OPENAPI))
        .operations().stream().map(OpenApiDocument.Operation::path).distinct()
        .filter(path -> !readme.contains(path)).toList();
    return missing.isEmpty()
        ? GateResult.pass(name(), "README documents every API path")
        : GateResult.fail(name(), "README.md does not document: " + missing);
  }
}
