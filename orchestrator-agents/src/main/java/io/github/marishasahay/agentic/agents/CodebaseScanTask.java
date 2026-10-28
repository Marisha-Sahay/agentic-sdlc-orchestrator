package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.CodebaseIndex;
import io.github.marishasahay.agentic.agents.analysis.CodebaseIndexer;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;

/** Deterministic codebase map (JavaParser). Runs in parallel with requirements analysis. */
public final class CodebaseScanTask implements NodeTask {

  @Override
  public NodeResult execute(NodeContext ctx) {
    CodebaseIndex index = new CodebaseIndexer().index(ctx.workspaceRoot());
    return NodeResult.builder()
        .output(ArtifactKeys.CODEBASE_INDEX, AgentSupport.pretty(index))
        .summary("Indexed " + index.sources().size() + " types, " + index.endpoints().size()
            + " endpoints, " + index.migrations().size() + " migrations, "
            + index.tests().size() + " test files")
        .build();
  }
}
