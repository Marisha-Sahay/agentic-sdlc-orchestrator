package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.SourceContext;
import io.github.marishasahay.agentic.agents.contract.CodeProposal;
import io.github.marishasahay.agentic.agents.contract.TaskPlan;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import java.util.List;

/** Implements one planned task. Proposes complete files; the engine applies and compiles them. */
public final class CoderAgent implements NodeTask {

  private static final String SYSTEM = """
      You are a senior Java engineer implementing ONE task of an approved plan in an existing
      Spring Boot 4 / Java 21 codebase. Production quality: small cohesive classes, constructor
      injection, records for DTOs, validation at the boundary, meaningful errors, no dead code.
      - Return COMPLETE file contents for every file you create or change (no diffs, no
        placeholders, no "rest unchanged" comments). Keep unrelated code exactly as it is.
      - Stay within the task. Write main code only; tests are written in a later stage.
      - Follow the engineering conventions exactly; the build runs Checkstyle.
      - Never modify existing Flyway migrations; add a new versioned one if the schema changes.
      - The code must compile against the files shown. If a previous attempt failed to compile,
        fix the reported errors.""";

  private final AgentSupport llm;
  private final String taskId;
  private final String conventions;

  /**
   * Creates the agent.
   *
   * @param llm model access
   * @param taskId planned task id this node implements
   * @param conventions coding standards injected into the prompt
   */
  public CoderAgent(AgentSupport llm, String taskId, String conventions) {
    this.llm = llm;
    this.taskId = taskId;
    this.conventions = conventions;
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    TaskPlan plan = AgentSupport.parse(ctx.input(ArtifactKeys.PLAN), TaskPlan.class);
    TaskPlan.PlannedTask task = AgentSupport.list(plan.tasks()).stream()
        .filter(t -> t.id().equals(taskId)).findFirst()
        .orElseThrow(() -> new IllegalStateException("Task " + taskId + " not in plan"));
    String prompt = "## Your task: " + task.title() + " (" + task.id() + ")\n"
        + task.description() + "\n\nFiles: " + task.files()
        + "\nAcceptance criteria: " + task.acceptanceCriteria()
        + "\nRequirements: " + task.requirementIds()
        + "\n\n## Approved design\n" + ctx.input(ArtifactKeys.DESIGN_DOC)
        + "\n\n## API contract\n" + ctx.input(ArtifactKeys.OPENAPI)
        + "\n\n## Engineering conventions\n" + conventions
        + "\n\n## Current workspace\n" + SourceContext.render(ctx.workspaceRoot(),
            AgentSupport.list(task.files()), false, 110_000);
    CodeProposal proposal = llm.ask(ctx, "coder", SYSTEM, prompt, CodeProposal.class);
    List<String> paths = AgentSupport.list(proposal.files()).stream()
        .map(CodeProposal.ProposedFile::path).toList();
    NodeResult.Builder result = NodeResult.builder()
        .changes(proposal.toChanges())
        .output(ArtifactKeys.taskResult(taskId), proposal.summary() + "\nFiles: " + paths)
        .summary(proposal.summary());
    AgentSupport.list(proposal.decisions()).forEach(d -> result.decision(d.toDecision()));
    return result.build();
  }
}
