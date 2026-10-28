package io.github.marishasahay.agentic.core.policy.rules;

import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import io.github.marishasahay.agentic.core.policy.PolicyInput;
import io.github.marishasahay.agentic.core.policy.PolicyRule;
import io.github.marishasahay.agentic.core.policy.Severity;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Blocks proposals that contain credentials. Applies to both file changes and artifacts, since
 * artifacts end up in reports and logs.
 */
public final class SecretLeakRule implements PolicyRule {

  private static final Map<String, Pattern> PATTERNS = Map.of(
      "private key", Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
      "AWS access key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
      "Google API key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
      "GitHub token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"),
      "hard-coded secret", Pattern.compile(
          "(?i)(password|passwd|secret|api[_-]?key|token)\\s*[:=]\\s*[\"'][^\"'${}\\s]{8,}[\"']"));

  @Override
  public String name() {
    return "secret-leak";
  }

  @Override
  public List<PolicyFinding> evaluate(PolicyInput input) {
    List<PolicyFinding> findings = new ArrayList<>();
    for (FileChange change : input.changes().changes()) {
      scan(change.content(), change.path(), findings);
    }
    input.outputs().forEach((key, value) -> scan(value, "artifact:" + key, findings));
    return findings;
  }

  private void scan(String text, String where, List<PolicyFinding> findings) {
    PATTERNS.forEach((label, pattern) -> {
      if (pattern.matcher(text).find()) {
        findings.add(new PolicyFinding(name(), Severity.BLOCK,
            "possible " + label + " detected; use configuration/env vars instead", where));
      }
    });
  }
}
