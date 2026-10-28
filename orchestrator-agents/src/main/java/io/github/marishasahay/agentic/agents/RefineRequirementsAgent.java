package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.contract.RequirementSpec;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;

/** Folds the stakeholder's answers into the draft and produces the final, unambiguous spec. */
public final class RefineRequirementsAgent implements NodeTask {

  private static final String SYSTEM = RequirementsAgent.SYSTEM + """

      You are now refining a draft after the stakeholder answered the open questions.
      - Apply every answer; where an answer changes scope, update requirements accordingly.
      - Record each answer as an assumption in the form "Q-n: <answer>".
      - The final spec must have NO remaining ambiguities (empty list).""";

  private final AgentSupport llm;

  public RefineRequirementsAgent(AgentSupport llm) {
    this.llm = llm;
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    RequirementSpec spec = llm.ask(ctx, "requirements-refiner", SYSTEM,
        "## Original request\n" + ctx.input(ArtifactKeys.REQUIREMENT_RAW)
            + "\n\n## Draft specification\n" + ctx.input(ArtifactKeys.REQUIREMENTS_DRAFT)
            + "\n\n## Stakeholder answers\n" + ctx.input(ArtifactKeys.CLARIFICATION_ANSWERS),
        RequirementSpec.class);
    return NodeResult.builder()
        .output(ArtifactKeys.REQUIREMENTS, AgentSupport.pretty(spec))
        .summary("Refined '" + spec.title() + "' with stakeholder answers")
        .build();
  }
}
