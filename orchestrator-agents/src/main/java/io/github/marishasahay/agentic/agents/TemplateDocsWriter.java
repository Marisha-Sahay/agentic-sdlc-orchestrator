package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.SourceContext;
import io.github.marishasahay.agentic.agents.gates.OpenApiDocument;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import io.github.marishasahay.agentic.core.workspace.FileChange;

/**
 * Deterministic fallback for documentation: regenerates an "API reference" section of the README
 * from the OpenAPI contract. Less polished than the model, but always correct and never down.
 */
public final class TemplateDocsWriter implements NodeTask {

  private static final String START = "<!-- api-reference:start -->";
  private static final String END = "<!-- api-reference:end -->";

  @Override
  public NodeResult execute(NodeContext ctx) {
    OpenApiDocument contract = OpenApiDocument.parse(ctx.input(ArtifactKeys.OPENAPI));
    StringBuilder section = new StringBuilder(START).append("\n## API reference\n\n")
        .append("_Generated from `api/openapi.yaml`._\n\n")
        .append("| Method | Path | Summary |\n|---|---|---|\n");
    contract.operations().forEach(op -> section.append("| ").append(op.method()).append(" | `")
        .append(op.path()).append("` | ").append(op.summary()).append(" |\n"));
    section.append(END).append('\n');
    String readme = SourceContext.readIfExists(ctx.workspaceRoot(), "README.md");
    String updated = readme.contains(START) && readme.contains(END)
        ? readme.substring(0, readme.indexOf(START)) + section
            + readme.substring(readme.indexOf(END) + END.length()).stripLeading()
        : readme + "\n" + section;
    return NodeResult.builder()
        .change(FileChange.write("README.md", updated))
        .output(ArtifactKeys.DOCS_SUMMARY, "API reference regenerated from the contract "
            + "(template fallback)")
        .summary("Template fallback regenerated the README API reference")
        .build();
  }
}
