package io.github.marishasahay.agentic.agents.contract;

import java.util.List;

/**
 * Implementation plan; each task becomes a node at run time.
 *
 * @param rationale why the work is split this way
 * @param tasks tasks with dependencies
 */
public record TaskPlan(String rationale, List<PlannedTask> tasks) {

  /**
   * A unit of implementation work small enough to compile and review on its own.
   *
   * @param id kebab-case id, unique in the plan
   * @param title short title
   * @param description what to build
   * @param requirementIds requirement ids it satisfies (traceability)
   * @param files files it creates or modifies
   * @param dependsOn ids of tasks that must land first
   * @param acceptanceCriteria how to tell it is done
   */
  public record PlannedTask(
      String id,
      String title,
      String description,
      List<String> requirementIds,
      List<String> files,
      List<String> dependsOn,
      List<String> acceptanceCriteria) {}
}
