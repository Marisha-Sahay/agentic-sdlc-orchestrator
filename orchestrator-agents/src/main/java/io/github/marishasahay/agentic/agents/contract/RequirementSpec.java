package io.github.marishasahay.agentic.agents.contract;

import java.util.List;

/**
 * Normalised engineering problem produced from a raw requirement.
 *
 * @param title short name
 * @param problemStatement the problem in one paragraph
 * @param goals outcomes that define success
 * @param functionalRequirements testable behaviours
 * @param nonFunctionalRequirements quality attributes with measures
 * @param ambiguities open questions with a recommended default
 * @param assumptions what was assumed to make the problem well-defined
 * @param outOfScope explicitly excluded items
 * @param risks delivery and operational risks
 */
public record RequirementSpec(
    String title,
    String problemStatement,
    List<String> goals,
    List<FunctionalRequirement> functionalRequirements,
    List<NonFunctionalRequirement> nonFunctionalRequirements,
    List<Ambiguity> ambiguities,
    List<String> assumptions,
    List<String> outOfScope,
    List<Risk> risks) {

  /**
   * A testable behaviour.
   *
   * @param id stable id such as {@code FR-1}
   * @param description behaviour
   * @param priority MUST, SHOULD or COULD
   * @param acceptanceCriteria Given/When/Then style checks
   */
  public record FunctionalRequirement(
      String id, String description, String priority, List<String> acceptanceCriteria) {}

  /**
   * A quality attribute.
   *
   * @param id stable id such as {@code NFR-1}
   * @param category performance, reliability, security...
   * @param description requirement
   * @param measure how it is verified
   */
  public record NonFunctionalRequirement(
      String id, String category, String description, String measure) {}

  /**
   * An open question.
   *
   * @param id stable id such as {@code Q-1}
   * @param question the question for the stakeholder
   * @param whyItMatters design impact of the answer
   * @param options candidate answers
   * @param recommendedDefault what the agent would assume
   */
  public record Ambiguity(
      String id, String question, String whyItMatters, List<String> options,
      String recommendedDefault) {}

  /**
   * A risk.
   *
   * @param description risk
   * @param impact consequence
   * @param mitigation mitigation
   */
  public record Risk(String description, String impact, String mitigation) {}
}
