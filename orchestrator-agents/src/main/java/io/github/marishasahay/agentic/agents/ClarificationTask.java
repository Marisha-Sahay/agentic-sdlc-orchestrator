package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.contract.RequirementSpec;
import io.github.marishasahay.agentic.core.engine.Decision;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import java.util.List;

/**
 * Turns the draft spec's open questions into a question sheet for the stakeholder, plus a
 * proposed answer sheet pre-filled with the recommended defaults. The node requires approval:
 * the reviewer edits {@code clarification.answers} (an amendment), and whatever they leave
 * untouched is an explicit, recorded decision to accept the default. Deterministic on purpose:
 * asking the questions needs no model.
 */
public final class ClarificationTask implements NodeTask {

  @Override
  public NodeResult execute(NodeContext ctx) {
    RequirementSpec draft = AgentSupport.parse(ctx.input(ArtifactKeys.REQUIREMENTS_DRAFT),
        RequirementSpec.class);
    List<RequirementSpec.Ambiguity> questions = AgentSupport.list(draft.ambiguities());
    StringBuilder sheet = new StringBuilder("# Clarifying questions\n\n");
    StringBuilder answers = new StringBuilder();
    NodeResult.Builder result = NodeResult.builder();
    for (RequirementSpec.Ambiguity q : questions) {
      sheet.append("## ").append(q.id()).append(": ").append(q.question()).append("\n\n")
          .append("*Why it matters:* ").append(q.whyItMatters()).append("\n\n")
          .append("Options: ").append(String.join(" | ", AgentSupport.list(q.options())))
          .append("\n\nRecommended default: **").append(q.recommendedDefault())
          .append("**\n\n");
      answers.append(q.id()).append(": ").append(q.recommendedDefault()).append('\n');
      result.decision(new Decision("Proposed default for " + q.id(), q.recommendedDefault(),
          q.whyItMatters(), AgentSupport.list(q.options())));
    }
    if (questions.isEmpty()) {
      sheet.append("No open questions: the request is unambiguous as analysed.\n");
    }
    return result
        .output(ArtifactKeys.CLARIFICATION_QUESTIONS, sheet.toString())
        .output(ArtifactKeys.CLARIFICATION_ANSWERS, answers.toString())
        .summary(questions.size() + " clarifying question(s); defaults pre-filled for review")
        .build();
  }
}
