package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.Git;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import java.util.List;

/**
 * Join point after all implementation tasks: records what changed since the baseline so the
 * parallel testing, documentation and security stages work from the same picture.
 */
public final class IntegrationTask implements NodeTask {

  @Override
  public NodeResult execute(NodeContext ctx) {
    ctx.input(ArtifactKeys.PLAN);
    List<String> changed = Git.changedSinceBaseline(ctx.workspaceRoot());
    return NodeResult.builder()
        .output(ArtifactKeys.CHANGED_FILES, String.join("\n", changed))
        .summary(changed.size() + " files changed since baseline")
        .build();
  }
}
