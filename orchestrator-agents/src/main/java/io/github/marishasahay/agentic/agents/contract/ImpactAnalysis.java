package io.github.marishasahay.agentic.agents.contract;

import java.util.List;

/**
 * Brownfield impact analysis grounded in the codebase index.
 *
 * @param summary overview
 * @param components affected files
 * @param apiChanges endpoint-level changes and their compatibility
 * @param dataChanges schema/data changes
 * @param dataFlows request/data flows that change
 * @param risks regression and operational risks
 * @param testStrategy how the change will be verified
 */
public record ImpactAnalysis(
    String summary,
    List<ImpactedComponent> components,
    List<String> apiChanges,
    List<String> dataChanges,
    List<String> dataFlows,
    List<RequirementSpec.Risk> risks,
    String testStrategy) {

  /**
   * One affected file.
   *
   * @param path workspace-relative path
   * @param kind controller, service, repository, migration, config, test...
   * @param change ADD, MODIFY or DELETE
   * @param reason why it changes
   */
  public record ImpactedComponent(String path, String kind, String change, String reason) {}
}
