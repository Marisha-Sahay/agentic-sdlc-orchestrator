package io.github.marishasahay.agentic.agents.analysis;

import java.util.List;

/**
 * Deterministic map of a Java codebase: what exists, what role it plays and how it is wired.
 * Agents reason over this instead of guessing, and gates use it to reject hallucinated paths.
 *
 * @param sources main source types
 * @param endpoints HTTP endpoints
 * @param migrations database migration files
 * @param resources other main resources
 * @param tests test files
 */
public record CodebaseIndex(
    List<SourceType> sources,
    List<Endpoint> endpoints,
    List<String> migrations,
    List<String> resources,
    List<String> tests) {

  /**
   * One top-level type.
   *
   * @param path workspace-relative file
   * @param name simple type name
   * @param kind class, record, interface or enum
   * @param role controller, service, repository, config, web, filter, domain or other
   * @param dependencies constructor-injected types
   * @param publicMethods public method signatures
   * @param lines file length
   */
  public record SourceType(
      String path, String name, String kind, String role, List<String> dependencies,
      List<String> publicMethods, int lines) {}

  /**
   * An HTTP endpoint.
   *
   * @param method HTTP method
   * @param path full path template
   * @param handler {@code Type#method}
   */
  public record Endpoint(String method, String path, String handler) {}

  /**
   * Whether a path is known to exist.
   *
   * @param path workspace-relative path
   * @return true if indexed
   */
  public boolean contains(String path) {
    return sources.stream().anyMatch(s -> s.path().equals(path)) || migrations.contains(path)
        || resources.contains(path) || tests.contains(path);
  }

  /**
   * Compact text rendering for prompts.
   *
   * @return codebase map
   */
  public String render() {
    StringBuilder out = new StringBuilder("### Types\n");
    for (SourceType s : sources) {
      out.append("- ").append(s.path()).append(" [").append(s.role()).append(' ')
          .append(s.kind()).append(']');
      if (!s.dependencies().isEmpty()) {
        out.append(" depends on ").append(s.dependencies());
      }
      out.append('\n');
    }
    out.append("\n### Endpoints\n");
    endpoints.forEach(e -> out.append("- ").append(e.method()).append(' ').append(e.path())
        .append(" -> ").append(e.handler()).append('\n'));
    out.append("\n### Migrations\n");
    migrations.forEach(m -> out.append("- ").append(m).append('\n'));
    out.append("\n### Resources\n");
    resources.forEach(r -> out.append("- ").append(r).append('\n'));
    out.append("\n### Tests\n");
    tests.forEach(t -> out.append("- ").append(t).append('\n'));
    return out.toString();
  }
}
