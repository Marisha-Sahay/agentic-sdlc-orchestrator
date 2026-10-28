package io.github.marishasahay.agentic.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.policy.rules.ChangeSizeRule;
import io.github.marishasahay.agentic.core.policy.rules.SchemaMigrationRule;
import io.github.marishasahay.agentic.core.policy.rules.SecretLeakRule;
import io.github.marishasahay.agentic.core.policy.rules.SensitiveFileRule;
import io.github.marishasahay.agentic.core.policy.rules.WorkspaceScopeRule;
import io.github.marishasahay.agentic.core.workspace.ChangeSet;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PolicyRulesTest {

  private static final NodeSpec NODE = NodeSpec.builder("impl", Stage.IMPLEMENTATION)
      .task(ctx -> NodeResult.builder().build()).build();

  @TempDir
  Path workspace;

  private PolicyInput input(FileChange... changes) {
    return new PolicyInput(NODE, ChangeSet.of(changes), Map.of(), workspace);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "src/main/java/App.java, ALLOWED",
      "README.md, ALLOWED",
      ".github/workflows/ci.yml, BLOCK",
      "config/checkstyle/checkstyle.xml, BLOCK",
      "../outside.txt, BLOCK",
      "/etc/passwd, BLOCK",
      "scripts/deploy.sh, BLOCK"
  })
  void workspaceScopeAllowsOnlyListedPathsAndProtectsGovernanceFiles(String path, String verdict) {
    WorkspaceScopeRule rule = new WorkspaceScopeRule(List.of("src/**", "README.md"),
        List.of(".github/**", "**/checkstyle.xml"));

    List<PolicyFinding> findings = rule.evaluate(input(FileChange.write(path, "x")));

    if (verdict.equals("ALLOWED")) {
      assertThat(findings).isEmpty();
    } else {
      assertThat(findings).singleElement()
          .extracting(PolicyFinding::severity).isEqualTo(Severity.BLOCK);
    }
  }

  @Test
  void secretLeakRuleScansFilesAndArtifacts() {
    SecretLeakRule rule = new SecretLeakRule();
    PolicyInput leaky = new PolicyInput(NODE,
        ChangeSet.of(FileChange.write("src/Config.java", "String apiKey = \"sk_live_1234567890\";")),
        Map.of("design", "-----BEGIN PRIVATE KEY-----"), workspace);
    PolicyInput clean = input(FileChange.write("src/Config.java",
        "String apiKey = System.getenv(\"API_KEY\"); String p = \"${DB_PASSWORD}\";"));

    assertThat(rule.evaluate(leaky)).hasSize(2)
        .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BLOCK));
    assertThat(rule.evaluate(clean)).isEmpty();
  }

  @Test
  void newMigrationsNeedApprovalAndAppliedMigrationsAreImmutable() throws Exception {
    Path existing = workspace.resolve("src/main/resources/db/migration/V1__links.sql");
    Files.createDirectories(existing.getParent());
    Files.writeString(existing, "create table links();");
    SchemaMigrationRule rule = SchemaMigrationRule.flyway();

    assertThat(rule.evaluate(input(FileChange.write(
        "src/main/resources/db/migration/V2__clicks.sql", "create table clicks();"))))
        .singleElement().extracting(PolicyFinding::severity).isEqualTo(Severity.REQUIRE_APPROVAL);
    assertThat(rule.evaluate(input(FileChange.write(
        "src/main/resources/db/migration/V1__links.sql", "drop table links;"))))
        .singleElement().extracting(PolicyFinding::severity).isEqualTo(Severity.BLOCK);
  }

  @Test
  void sensitiveFilesAndLargeChangesRequireApproval() {
    String big = "line\n".repeat(500);
    PolicyEngine engine = new PolicyEngine(List.of(SensitiveFileRule.dependencies(),
        SensitiveFileRule.apiContract(), SensitiveFileRule.securityConfig(),
        new ChangeSizeRule(400)));

    List<PolicyFinding> findings = engine.evaluate(input(
        FileChange.write("pom.xml", "<project/>"),
        FileChange.write("api/openapi.yaml", "openapi: 3.1.0"),
        FileChange.write("src/main/java/WebSecurityConfig.java", big)));

    assertThat(findings).extracting(PolicyFinding::rule).containsExactlyInAnyOrder(
        "dependency-change", "api-contract-change", "security-config-change", "change-size");
    assertThat(PolicyEngine.requiresApproval(findings)).isTrue();
    assertThat(PolicyEngine.blocks(findings)).isFalse();
  }

  @Test
  void rewritingAFileUnchangedIsNotAChange() throws Exception {
    Files.createDirectories(workspace.resolve("api"));
    Files.writeString(workspace.resolve("api/openapi.yaml"), "openapi: 3.1.0\n");
    PolicyEngine engine = new PolicyEngine(List.of(SensitiveFileRule.apiContract()));

    assertThat(engine.evaluate(input(FileChange.write("api/openapi.yaml", "openapi: 3.1.0\n"))))
        .isEmpty();
    assertThat(engine.evaluate(input(FileChange.write("api/openapi.yaml", "openapi: 3.1.1\n"))))
        .singleElement().extracting(PolicyFinding::rule).isEqualTo("api-contract-change");
  }

  @Test
  void aBrokenRuleFailsClosed() {
    PolicyRule broken = new PolicyRule() {
      @Override
      public String name() {
        return "broken";
      }

      @Override
      public List<PolicyFinding> evaluate(PolicyInput in) {
        throw new IllegalStateException("bad regex");
      }
    };

    List<PolicyFinding> findings = new PolicyEngine(List.of(broken))
        .evaluate(input(FileChange.write("src/A.java", "x")));

    assertThat(PolicyEngine.blocks(findings)).isTrue();
    assertThat(findings.getFirst().message()).contains("fail-closed");
  }
}
