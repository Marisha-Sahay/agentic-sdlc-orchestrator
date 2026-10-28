package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.SourceContext;
import io.github.marishasahay.agentic.agents.contract.CodeProposal;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;

/** Updates the service README and changelog for the change. */
public final class DocsAgent implements NodeTask {

  private static final String SYSTEM = """
      You are a staff engineer updating developer documentation for a change to a service.
      - Update README.md: keep its structure, document every endpoint in the API contract with a
        curl example, document new configuration, and explain behaviour changes.
      - Append an entry to CHANGELOG.md (create it if missing) under an "Unreleased" heading.
      - Only modify README.md, CHANGELOG.md or files under docs/. Be concise and factual; do not
        claim anything the code does not do.
      - Return complete file contents.""";

  private final AgentSupport llm;

  public DocsAgent(AgentSupport llm) {
    this.llm = llm;
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    CodeProposal proposal = llm.ask(ctx, "tech-writer", SYSTEM,
        "## Requirements\n" + ctx.input(ArtifactKeys.REQUIREMENTS)
            + "\n\n## Design\n" + ctx.input(ArtifactKeys.DESIGN_DOC)
            + "\n\n## API contract\n" + ctx.input(ArtifactKeys.OPENAPI)
            + "\n\n## Changed files\n" + ctx.input(ArtifactKeys.CHANGED_FILES)
            + "\n\n## Current README.md\n" + SourceContext.readIfExists(ctx.workspaceRoot(),
                "README.md")
            + "\n\n## Current CHANGELOG.md\n" + SourceContext.readIfExists(ctx.workspaceRoot(),
                "CHANGELOG.md"),
        CodeProposal.class);
    return NodeResult.builder()
        .changes(proposal.toChanges())
        .output(ArtifactKeys.DOCS_SUMMARY, proposal.summary())
        .summary(proposal.summary())
        .build();
  }
}
