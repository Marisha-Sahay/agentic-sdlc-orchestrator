package io.github.marishasahay.agentic.app;

import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.policy.PolicyEngine;
import io.github.marishasahay.agentic.core.policy.rules.BuildIntegrityRule;
import io.github.marishasahay.agentic.core.policy.rules.ChangeSizeRule;
import io.github.marishasahay.agentic.core.policy.rules.SchemaMigrationRule;
import io.github.marishasahay.agentic.core.policy.rules.SecretLeakRule;
import io.github.marishasahay.agentic.core.policy.rules.SensitiveFileRule;
import io.github.marishasahay.agentic.core.policy.rules.StageWriteScopeRule;
import io.github.marishasahay.agentic.core.policy.rules.WorkspaceScopeRule;
import java.util.List;
import java.util.Map;

/**
 * The change-control policy every run is governed by. Kept in code, reviewed like code: the
 * workspace scope rule protects the policy and gate definitions themselves from agent edits.
 */
final class GovernancePolicy {

  static final List<String> AGENT_WRITABLE = List.of(
      "src/main/**", "src/test/**", "api/**", "docs/**", "README.md", "CHANGELOG.md", "API.md",
      "pom.xml");
  static final List<String> DOCS = List.of("README.md", "CHANGELOG.md", "API.md", "docs/**");
  /** What each stage may write; stages not listed (analysis, review, release) write nothing. */
  static final Map<Stage, List<String>> STAGE_SCOPES = Map.of(
      Stage.DESIGN, List.of("api/**"),
      Stage.IMPLEMENTATION, List.of("src/main/**", "api/**", "pom.xml"),
      Stage.TESTING, List.of("src/test/**"),
      Stage.DOCUMENTATION, DOCS,
      Stage.VERIFICATION, List.of("src/main/**", "src/test/**"));
  static final List<String> PROTECTED = List.of(
      ".github/**", "config/**", "**/checkstyle.xml", "docker-compose.yml", ".gitignore",
      "mvnw", ".mvn/**");

  private GovernancePolicy() {}

  static PolicyEngine standard() {
    return new PolicyEngine(List.of(
        new WorkspaceScopeRule(AGENT_WRITABLE, PROTECTED),
        new StageWriteScopeRule(STAGE_SCOPES),
        new BuildIntegrityRule(),
        new SecretLeakRule(),
        SchemaMigrationRule.flyway(),
        SensitiveFileRule.dependencies(),
        SensitiveFileRule.apiContract(),
        SensitiveFileRule.securityConfig(),
        new ChangeSizeRule(800)));
  }
}
