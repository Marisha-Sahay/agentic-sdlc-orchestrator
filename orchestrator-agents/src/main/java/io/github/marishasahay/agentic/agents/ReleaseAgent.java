package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.Git;
import io.github.marishasahay.agentic.agents.contract.ReleaseNotes;
import io.github.marishasahay.agentic.agents.contract.RequirementSpec;
import io.github.marishasahay.agentic.agents.contract.TaskPlan;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import java.util.List;

/**
 * Release readiness: the reviewable outcome of the run. Produces the patch against the baseline,
 * a requirement-to-task-to-file traceability matrix (deterministic, from the plan) and release
 * notes with risks and a rollback plan (model). The node always requires human sign-off.
 */
public final class ReleaseAgent implements NodeTask {

  private static final String SYSTEM = """
      You are the release manager preparing a change for human sign-off.
      Write release notes a reviewer can act on: what ships, notable changes, residual risks
      (be honest, include anything the security review or verification flagged), a concrete
      rollback plan (code and database), how it was tested, and follow-ups.""";

  private final AgentSupport llm;

  public ReleaseAgent(AgentSupport llm) {
    this.llm = llm;
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    String patch = Git.diffSinceBaseline(ctx.workspaceRoot());
    RequirementSpec spec = AgentSupport.parse(ctx.input(ArtifactKeys.REQUIREMENTS),
        RequirementSpec.class);
    TaskPlan plan = AgentSupport.parse(ctx.input(ArtifactKeys.PLAN), TaskPlan.class);
    String traceability = traceability(spec, plan, ctx.input(ArtifactKeys.CHANGED_FILES));
    ReleaseNotes notes = llm.ask(ctx, "release-manager", SYSTEM,
        "## Requirements\n" + ctx.input(ArtifactKeys.REQUIREMENTS)
            + "\n\n## Traceability\n" + traceability
            + "\n\n## Security review\n" + ctx.input(ArtifactKeys.SECURITY)
            + "\n\n## Verification\n" + ctx.input(ArtifactKeys.VERIFICATION)
            + "\n\n## Tests\n" + ctx.input(ArtifactKeys.TEST_REPORT)
            + "\n\n## Docs\n" + ctx.input(ArtifactKeys.DOCS_SUMMARY)
            + "\n\n## Patch (truncated)\n" + patch.substring(0, Math.min(patch.length(), 60_000)),
        ReleaseNotes.class);
    return NodeResult.builder()
        .output(ArtifactKeys.RELEASE_PATCH, patch)
        .output(ArtifactKeys.TRACEABILITY, traceability)
        .output(ArtifactKeys.RELEASE_NOTES, render(notes))
        .summary(notes.title())
        .build();
  }

  static String traceability(RequirementSpec spec, TaskPlan plan, String changedFiles) {
    StringBuilder md = new StringBuilder("| Requirement | Priority | Tasks | Files |\n"
        + "|---|---|---|---|\n");
    for (RequirementSpec.FunctionalRequirement fr : AgentSupport.list(
        spec.functionalRequirements())) {
      List<TaskPlan.PlannedTask> tasks = AgentSupport.list(plan.tasks()).stream()
          .filter(t -> AgentSupport.list(t.requirementIds()).contains(fr.id())).toList();
      md.append("| ").append(fr.id()).append(": ").append(fr.description()).append(" | ")
          .append(fr.priority()).append(" | ")
          .append(tasks.isEmpty() ? "**untraced**"
              : String.join(", ", tasks.stream().map(TaskPlan.PlannedTask::id).toList()))
          .append(" | ")
          .append(String.join("<br>", tasks.stream()
              .flatMap(t -> AgentSupport.list(t.files()).stream()).distinct().toList()))
          .append(" |\n");
    }
    md.append("\nFiles changed since baseline:\n\n");
    for (String file : changedFiles.split("\n")) {
      if (!file.isBlank()) {
        md.append("- ").append(file).append('\n');
      }
    }
    return md.toString();
  }

  static String render(ReleaseNotes notes) {
    StringBuilder md = new StringBuilder("# ").append(notes.title()).append("\n\n")
        .append(notes.summary()).append("\n\n## Changes\n");
    AgentSupport.list(notes.changes()).forEach(c -> md.append("- ").append(c).append('\n'));
    md.append("\n## Risks\n");
    AgentSupport.list(notes.risks()).forEach(r -> md.append("- ").append(r).append('\n'));
    md.append("\n## Rollback plan\n").append(notes.rollbackPlan())
        .append("\n\n## Testing\n").append(notes.testingSummary()).append("\n\n## Follow-ups\n");
    AgentSupport.list(notes.followUps()).forEach(f -> md.append("- ").append(f).append('\n'));
    return md.toString();
  }
}
