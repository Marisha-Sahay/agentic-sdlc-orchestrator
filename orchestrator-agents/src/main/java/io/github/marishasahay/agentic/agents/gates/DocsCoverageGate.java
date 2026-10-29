package io.github.marishasahay.agentic.agents.gates;

import io.github.marishasahay.agentic.agents.ArchitectAgent;
import io.github.marishasahay.agentic.agents.ArtifactKeys;
import io.github.marishasahay.agentic.agents.analysis.SourceContext;
import io.github.marishasahay.agentic.core.gate.Gate;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;
import java.util.List;

/**
 * Documentation must mention every path in the API contract: the design's contract while the
 * docs are written, or the contract file in the workspace at final validation.
 */
public final class DocsCoverageGate implements Gate {

  private final boolean workspaceContract;

  public DocsCoverageGate() {
    this(false);
  }

  /**
   * Creates the gate.
   *
   * @param workspaceContract read the contract from {@code api/openapi.yaml} in the workspace
   *     instead of the design artifact
   */
  public DocsCoverageGate(boolean workspaceContract) {
    this.workspaceContract = workspaceContract;
  }

  @Override
  public String name() {
    return workspaceContract ? "final-docs-coverage" : "docs-coverage";
  }

  @Override
  public boolean usesWorkspace() {
    return true;
  }

  @Override
  public GateResult evaluate(GateContext context) {
    String readme = SourceContext.readIfExists(context.workspaceRoot(), "README.md");
    String contract = workspaceContract
        ? SourceContext.readIfExists(context.workspaceRoot(), ArchitectAgent.CONTRACT_PATH)
        : context.output(ArtifactKeys.OPENAPI);
    List<String> missing = OpenApiDocument.parse(contract)
        .operations().stream().map(OpenApiDocument.Operation::path).distinct()
        .filter(path -> !readme.contains(path)).toList();
    return missing.isEmpty()
        ? GateResult.pass(name(), "README documents every API path")
        : GateResult.fail(name(), "README.md does not document: " + missing);
  }
}
