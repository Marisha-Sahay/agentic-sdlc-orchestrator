package io.github.marishasahay.agentic.core.policy.rules;

import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import io.github.marishasahay.agentic.core.policy.PolicyInput;
import io.github.marishasahay.agentic.core.policy.PolicyRule;
import io.github.marishasahay.agentic.core.policy.Severity;
import io.github.marishasahay.agentic.core.util.Globs;
import java.util.ArrayList;
import java.util.List;

/**
 * Database change control. A new migration is a high-impact change that needs approval; editing
 * an existing migration is blocked outright, because it has already run in some environment and
 * Flyway would refuse to start (checksum mismatch) or, worse, environments would diverge.
 *
 * @param migrationGlob where migrations live
 */
public record SchemaMigrationRule(String migrationGlob) implements PolicyRule {

  public static SchemaMigrationRule flyway() {
    return new SchemaMigrationRule("**/db/migration/*.sql");
  }

  @Override
  public String name() {
    return "schema-migration";
  }

  @Override
  public List<PolicyFinding> evaluate(PolicyInput input) {
    List<PolicyFinding> findings = new ArrayList<>();
    for (String path : input.effectiveChanges().paths()) {
      if (!Globs.matches(migrationGlob, path)) {
        continue;
      }
      if (input.existsInWorkspace(path)) {
        findings.add(new PolicyFinding(name(), Severity.BLOCK,
            "applied migrations are immutable; add a new versioned migration instead", path));
      } else {
        findings.add(new PolicyFinding(name(), Severity.REQUIRE_APPROVAL,
            "new database migration (schema change)", path));
      }
    }
    return findings;
  }
}
