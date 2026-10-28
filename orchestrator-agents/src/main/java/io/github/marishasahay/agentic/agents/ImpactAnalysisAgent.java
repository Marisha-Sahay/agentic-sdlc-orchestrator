package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.CodebaseIndex;
import io.github.marishasahay.agentic.agents.analysis.SourceContext;
import io.github.marishasahay.agentic.agents.contract.ImpactAnalysis;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import java.util.List;

/** Brownfield reasoning: which modules, APIs, data and flows a requirement touches. */
public final class ImpactAnalysisAgent implements NodeTask {

  private static final String SYSTEM = """
      You are a principal engineer analysing the impact of a change on an existing codebase.
      Ground every statement in the codebase map and sources you are given:
      - components: every file to ADD, MODIFY or DELETE, with kind and reason. Paths you MODIFY or
        DELETE must exist in the map; never invent existing files.
      - apiChanges: endpoint-level changes and whether each is backward compatible.
      - dataChanges: schema changes. Existing Flyway migrations are immutable; new schema changes
        need a new versioned migration.
      - dataFlows: request/data flows that change (e.g. redirect hot path, async recording).
      - risks: regressions, performance on hot paths, data growth; with mitigations.
      - testStrategy: unit, integration and regression tests that prove the change.""";

  private final AgentSupport llm;

  public ImpactAnalysisAgent(AgentSupport llm) {
    this.llm = llm;
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    CodebaseIndex index = AgentSupport.parse(ctx.input(ArtifactKeys.CODEBASE_INDEX),
        CodebaseIndex.class);
    ImpactAnalysis impact = llm.ask(ctx, "impact-analyst", SYSTEM,
        "## Requirements\n" + ctx.input(ArtifactKeys.REQUIREMENTS)
            + "\n\n## Codebase map\n" + index.render()
            + "\n\n## Sources\n" + SourceContext.render(ctx.workspaceRoot(), List.of(), false,
                90_000),
        ImpactAnalysis.class);
    return NodeResult.builder()
        .output(ArtifactKeys.IMPACT, AgentSupport.pretty(impact))
        .summary(impact.summary())
        .build();
  }
}
