package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.Git;
import io.github.marishasahay.agentic.agents.contract.ReleaseNotes;
import io.github.marishasahay.agentic.agents.contract.RequirementSpec;
import io.github.marishasahay.agentic.agents.contract.TaskPlan;
import io.github.marishasahay.agentic.agents.contract.ValidationEvidence;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import io.github.marishasahay.agentic.core.event.RunEvent;
import java.util.List;

/**
 * Release readiness: the reviewable outcome of the run. Produces the patch against the baseline,
 * a requirement-to-task-to-file traceability matrix (deterministic, from the plan) and release
 * notes with risks and a rollback plan (model). The notes end with the final validation
 * evidence for the exact tree being released, rendered from the audit log rather than written
 * by the model. The node always requires human sign-off; degraded verification additionally
 * requires an explicit waiver.
 */
public final class ReleaseAgent implements NodeTask {

  private static final String SYSTEM = """
      You are the release manager preparing a change for human sign-off.
      Write release notes a reviewer can act on: what ships, notable changes, residual risks
      (be honest, include anything the security review or verification flagged), a concrete
      rollback plan (code and database), how it was tested, and follow-ups.""";

  static final String DEGRADED = "degraded-verification";

  private final AgentSupport llm;
  private final String validationNode;

  public ReleaseAgent(AgentSupport llm) {
    this(llm, "final-validation");
  }

  /**
   * Creates the agent.
   *
   * @param llm model access
   * @param validationNode node whose accepted gate results are the final validation evidence
   */
  public ReleaseAgent(AgentSupport llm, String validationNode) {
    this.llm = llm;
    this.validationNode = validationNode;
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    String patch = Git.diffSinceBaseline(ctx.workspaceRoot());
    RequirementSpec spec = AgentSupport.parse(ctx.input(ArtifactKeys.REQUIREMENTS),
        RequirementSpec.class);
    TaskPlan plan = AgentSupport.parse(ctx.input(ArtifactKeys.PLAN), TaskPlan.class);
    String traceability = traceability(spec, plan, ctx.input(ArtifactKeys.CHANGED_FILES));
    ValidationEvidence validation = AgentSupport.parse(ctx.input(ArtifactKeys.VALIDATION),
        ValidationEvidence.class);
    String evidence = validationSection(validation,
        ctx.acceptedGateResults(validationNode));
    ReleaseNotes notes = llm.ask(ctx, "release-manager", SYSTEM,
        "## Requirements\n" + ctx.input(ArtifactKeys.REQUIREMENTS)
            + "\n\n## Traceability\n" + traceability
            + "\n\n## Security review\n" + ctx.input(ArtifactKeys.SECURITY)
            + "\n\n## Verification\n" + ctx.input(ArtifactKeys.VERIFICATION)
            + "\n\n## Tests\n" + ctx.input(ArtifactKeys.TEST_REPORT)
            + "\n\n## Docs\n" + ctx.input(ArtifactKeys.DOCS_SUMMARY)
            + "\n\n" + evidence
            + "\n\n## Patch (truncated)\n" + patch.substring(0, Math.min(patch.length(), 60_000)),
        ReleaseNotes.class);
    NodeResult.Builder result = NodeResult.builder()
        .output(ArtifactKeys.RELEASE_PATCH, patch)
        .output(ArtifactKeys.TRACEABILITY, traceability
            + "\nValidated tree: `" + validation.treeSha() + "`\n")
        .output(ArtifactKeys.RELEASE_NOTES, render(notes) + "\n" + evidence)
        .summary(notes.title() + " [verification " + validation.verificationProfile() + ", tree "
            + validation.treeSha().substring(0, 12) + "]");
    if (validation.degraded()) {
      result.requireApproval(DEGRADED, "verification was DEGRADED (not run: "
          + validation.skippedChecks() + "); approving waives these checks for this release");
    }
    return result.build();
  }

  static String validationSection(ValidationEvidence validation,
      List<RunEvent.GateEvaluated> gates) {
    StringBuilder md = new StringBuilder("## Validation evidence\n\n")
        .append("_Rendered from the audit log; not written by the model._\n\n")
        .append("- Validated tree: `").append(validation.treeSha()).append("` (commit `")
        .append(validation.revision() == null ? "?" : validation.revision().substring(0, 12))
        .append("`)\n- Verification: **").append(validation.verificationProfile()).append("**");
    if (validation.degraded()) {
      md.append(" (not run: ").append(String.join(", ", validation.skippedChecks()))
          .append(")");
    }
    md.append("\n- Builds and tests ran in: ").append(validation.sandbox())
        .append("\n- Final gates on this tree:\n");
    if (gates.isEmpty()) {
      md.append("  - (none recorded)\n");
    }
    for (RunEvent.GateEvaluated gate : gates) {
      md.append("  - ").append(gate.passed() ? "PASS " : "FAIL ").append(gate.gate())
          .append(": ").append(gate.evidence().lines().findFirst().orElse("")).append('\n');
    }
    return md.toString();
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
