package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.SourceContext;
import io.github.marishasahay.agentic.agents.contract.CodeProposal;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import java.util.List;

/**
 * Full verification with bounded self-repair. The first attempt proposes nothing, so the exit
 * gate simply runs the full build. If it fails, the next attempt receives the build output as
 * feedback and asks the model for the smallest fix; every repair is still policy-checked, gated
 * and rolled back if it does not make the build green.
 */
public final class VerifyAndRepairAgent implements NodeTask {

  private static final String SYSTEM = """
      You are a senior engineer fixing a failing build (compile errors, failing tests,
      Checkstyle violations or a coverage gate) on a Spring Boot 4 / Java 21 service.
      - Make the SMALLEST change that makes the build pass. Fix the root cause; never delete or
        weaken tests, assertions, gates or Checkstyle rules to get green.
      - If coverage is too low, add meaningful tests for uncovered logic.
      - Return complete contents for each file you change.""";

  private final AgentSupport llm;
  private final String conventions;
  private final String profile;

  public VerifyAndRepairAgent(AgentSupport llm, String conventions) {
    this(llm, conventions, "FULL: unit and integration tests, Checkstyle, coverage gate");
  }

  /**
   * Creates the agent.
   *
   * @param llm model access
   * @param conventions coding standards
   * @param profile what the build gate actually verifies in this environment; reports never
   *     claim more than this
   */
  public VerifyAndRepairAgent(AgentSupport llm, String conventions, String profile) {
    this.llm = llm;
    this.conventions = conventions;
    this.profile = profile;
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    String changed = ctx.input(ArtifactKeys.CHANGED_FILES);
    if (!ctx.isRetry()) {
      return NodeResult.builder()
          .output(ArtifactKeys.VERIFICATION, "Build gate passed on the first run without "
              + "repair. Verification profile: " + profile + ".")
          .summary("Running the build gate (" + profile + ")")
          .build();
    }
    CodeProposal fix = llm.ask(ctx, "build-fixer", SYSTEM,
        "## Changed files in this run\n" + changed
            + "\n\n## Engineering conventions\n" + conventions
            + "\n\n## Workspace\n" + SourceContext.render(ctx.workspaceRoot(),
                List.of(changed.split("\n")), true, 120_000),
        CodeProposal.class);
    return NodeResult.builder()
        .changes(fix.toChanges())
        .output(ArtifactKeys.VERIFICATION, "Build gate passed after automated repair (attempt "
            + ctx.attempt() + "): " + fix.summary() + " Verification profile: " + profile + ".")
        .summary(fix.summary())
        .build();
  }
}
