package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.contract.TaskPlan;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;

/** Decomposes the design into implementation tasks with explicit dependencies. */
public final class PlannerAgent implements NodeTask {

  private static final String SYSTEM = """
      You are a tech lead decomposing an approved design into implementation tasks.
      - Each task is small enough to compile and review on its own, touches a few files, and
        lists the requirement ids it satisfies (traceability).
      - dependsOn lists ids of tasks whose code this task needs (e.g. repository before
        service). Tasks without a dependency between them can run in parallel; do not
        serialise needlessly, and never create cycles.
      - Implementation tasks write main code only (src/main/**, migrations, and pom.xml when a
        dependency is truly needed; a human must authorize that before it is built). Tests and
        docs are separate later stages; do not plan them.
      - ids are short kebab-case (e.g. "click-repository"). At most %d tasks.""";

  private final AgentSupport llm;
  private final int maxTasks;

  public PlannerAgent(AgentSupport llm, int maxTasks) {
    this.llm = llm;
    this.maxTasks = maxTasks;
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    StringBuilder prompt = new StringBuilder("## Requirements\n")
        .append(ctx.input(ArtifactKeys.REQUIREMENTS))
        .append("\n\n## Approved design\n").append(ctx.input(ArtifactKeys.DESIGN_DOC));
    ctx.optionalInput(ArtifactKeys.IMPACT)
        .ifPresent(i -> prompt.append("\n\n## Impact analysis\n").append(i));
    ctx.optionalInput(ArtifactKeys.CODEBASE_INDEX)
        .ifPresent(i -> prompt.append("\n\n## Codebase index\n").append(i));
    TaskPlan plan = llm.ask(ctx, "planner", SYSTEM.formatted(maxTasks), prompt.toString(),
        TaskPlan.class);
    return NodeResult.builder()
        .output(ArtifactKeys.PLAN, AgentSupport.pretty(plan))
        .summary(AgentSupport.list(plan.tasks()).size() + " tasks: " + plan.rationale())
        .build();
  }
}
