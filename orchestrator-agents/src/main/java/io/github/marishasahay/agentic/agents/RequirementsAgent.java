package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.contract.RequirementSpec;
import io.github.marishasahay.agentic.core.engine.Decision;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import java.util.List;

/** Interprets the raw request and normalises it into a testable {@link RequirementSpec}. */
public final class RequirementsAgent implements NodeTask {

  static final String SYSTEM = """
      You are a principal engineer doing requirements analysis for a production Java service.
      Turn the stakeholder request into a precise, testable engineering problem.
      - Functional requirements: ids FR-1, FR-2...; priority MUST, SHOULD or COULD; each with
        concrete, verifiable acceptance criteria (Given/When/Then).
      - Non-functional requirements: ids NFR-1...; category; a measurable verification method.
      - Ambiguity: list every point where the request admits several reasonable readings as a
        question (ids Q-1...) with options and a recommended default. Never guess silently.
      - Assumptions: state them explicitly. Keep scope tight and list what is out of scope.
      - Risks: security, abuse, data integrity, operability, each with a mitigation.""";

  private final AgentSupport llm;
  private final String outputKey;
  private final String context;

  /**
   * Creates the agent.
   *
   * @param llm model access
   * @param outputKey artifact key to publish the spec under
   * @param context scenario context (e.g. "extends an existing service")
   */
  public RequirementsAgent(AgentSupport llm, String outputKey, String context) {
    this.llm = llm;
    this.outputKey = outputKey;
    this.context = context;
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    String request = ctx.input(ArtifactKeys.REQUIREMENT_RAW);
    RequirementSpec spec = llm.ask(ctx, "requirements-analyst", SYSTEM,
        "## Stakeholder request\n" + request + "\n\n## Context\n" + context,
        RequirementSpec.class);
    NodeResult.Builder result = NodeResult.builder()
        .output(outputKey, AgentSupport.pretty(spec))
        .summary(spec.title() + ": " + AgentSupport.list(spec.functionalRequirements()).size()
            + " functional, " + AgentSupport.list(spec.nonFunctionalRequirements()).size()
            + " non-functional requirements, " + AgentSupport.list(spec.ambiguities()).size()
            + " open questions");
    for (String assumption : AgentSupport.list(spec.assumptions())) {
      result.decision(new Decision("Assumption", assumption,
          "Stated explicitly to make the requirement testable", List.of()));
    }
    return result.build();
  }
}
