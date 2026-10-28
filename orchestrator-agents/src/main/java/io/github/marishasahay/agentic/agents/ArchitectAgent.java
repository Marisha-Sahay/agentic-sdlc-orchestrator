package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.SourceContext;
import io.github.marishasahay.agentic.agents.contract.DesignDecision;
import io.github.marishasahay.agentic.agents.contract.DesignSpec;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import io.github.marishasahay.agentic.core.workspace.FileChange;

/**
 * Produces the design and the API contract. The contract is written to {@code api/openapi.yaml}
 * in the workspace, so it is versioned with the code, reviewed at the design gate and checked
 * for breaking changes against the baseline.
 */
public final class ArchitectAgent implements NodeTask {

  public static final String CONTRACT_PATH = "api/openapi.yaml";

  private static final String SYSTEM = """
      You are the architect for a production Java 21 / Spring Boot 4 service on PostgreSQL.
      Produce a design that is simple, secure and operable, and an OpenAPI 3.1 contract.
      - components: cohesive units with one responsibility each, and the files implementing them.
      - openApiYaml: the COMPLETE contract (every endpoint, schema and RFC 9457 problem
        response). For an existing service, keep every existing operation unless a requirement
        explicitly removes it; additive changes only.
      - dataModel: tables, columns, constraints and indexes.
      - decisions: ADR-style; for each, the choice, rationale and rejected alternatives.
      - risks: trade-offs you accepted and what would change them.""";

  private final AgentSupport llm;
  private final String conventions;

  public ArchitectAgent(AgentSupport llm, String conventions) {
    this.llm = llm;
    this.conventions = conventions;
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    String existingContract = SourceContext.readIfExists(ctx.workspaceRoot(), CONTRACT_PATH);
    StringBuilder prompt = new StringBuilder("## Requirements\n")
        .append(ctx.input(ArtifactKeys.REQUIREMENTS));
    ctx.optionalInput(ArtifactKeys.IMPACT)
        .ifPresent(i -> prompt.append("\n\n## Impact analysis\n").append(i));
    if (!existingContract.isBlank()) {
      prompt.append("\n\n## Existing API contract (must stay backward compatible)\n")
          .append(existingContract);
    }
    prompt.append("\n\n## Engineering conventions\n").append(conventions);
    DesignSpec design = llm.ask(ctx, "architect", SYSTEM, prompt.toString(), DesignSpec.class);

    NodeResult.Builder result = NodeResult.builder()
        .output(ArtifactKeys.DESIGN, AgentSupport.pretty(design))
        .output(ArtifactKeys.DESIGN_DOC, render(design))
        .output(ArtifactKeys.OPENAPI, design.openApiYaml())
        .change(FileChange.write(CONTRACT_PATH, design.openApiYaml()))
        .summary(design.overview());
    AgentSupport.list(design.decisions()).forEach(d -> result.decision(d.toDecision()));
    return result.build();
  }

  static String render(DesignSpec design) {
    StringBuilder md = new StringBuilder("# Design\n\n").append(design.overview())
        .append("\n\n## Components\n\n| Component | Responsibility | Files |\n|---|---|---|\n");
    AgentSupport.list(design.components()).forEach(c -> md.append("| ").append(c.name())
        .append(" | ").append(c.responsibility()).append(" | ")
        .append(String.join("<br>", AgentSupport.list(c.files()))).append(" |\n"));
    md.append("\n## Data model\n\n").append(design.dataModel()).append("\n\n## Decisions\n\n");
    for (DesignDecision d : AgentSupport.list(design.decisions())) {
      md.append("### ").append(d.title()).append("\n\n**Decision:** ").append(d.choice())
          .append("\n\n**Rationale:** ").append(d.rationale()).append("\n\n**Alternatives:** ")
          .append(String.join("; ", AgentSupport.list(d.alternatives()))).append("\n\n");
    }
    md.append("## Risks and trade-offs\n\n");
    AgentSupport.list(design.risks()).forEach(r -> md.append("- ").append(r).append('\n'));
    return md.toString();
  }
}
