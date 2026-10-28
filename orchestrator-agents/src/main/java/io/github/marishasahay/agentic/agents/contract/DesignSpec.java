package io.github.marishasahay.agentic.agents.contract;

import java.util.List;

/**
 * Architecture/design output. The OpenAPI document is the API contract downstream agents build
 * against and the compatibility gate checks.
 *
 * @param overview design summary
 * @param components components and their responsibilities
 * @param openApiYaml complete OpenAPI 3.1 document
 * @param dataModel tables/columns/indexes in prose or SQL
 * @param decisions design decisions with rationale (ADR-style)
 * @param risks design risks and trade-offs
 */
public record DesignSpec(
    String overview,
    List<Component> components,
    String openApiYaml,
    String dataModel,
    List<DesignDecision> decisions,
    List<String> risks) {

  /**
   * A component.
   *
   * @param name component name
   * @param responsibility single responsibility
   * @param files files that implement it
   */
  public record Component(String name, String responsibility, List<String> files) {}
}
