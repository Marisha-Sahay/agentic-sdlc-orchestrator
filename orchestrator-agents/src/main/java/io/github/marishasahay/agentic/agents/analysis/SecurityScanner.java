package io.github.marishasahay.agentic.agents.analysis;

import io.github.marishasahay.agentic.agents.contract.SecurityReview;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic scanner for known-dangerous patterns in changed main sources. Its findings are
 * authoritative (HIGH ones block) and, being deterministic, it is re-run on the exact final
 * tree at final validation.
 */
public final class SecurityScanner {

  private static final Map<Pattern, String[]> RULES = Map.of(
      Pattern.compile("\"\\s*(SELECT|INSERT|UPDATE|DELETE)\\b[^\"]*\"\\s*\\+",
          Pattern.CASE_INSENSITIVE),
      new String[] {"HIGH", "injection", "SQL built by string concatenation; bind parameters"},
      Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec|new ProcessBuilder"),
      new String[] {"HIGH", "command-execution", "process execution from service code"},
      Pattern.compile("@CrossOrigin\\(\\s*(origins\\s*=\\s*)?\"\\*\""),
      new String[] {"MEDIUM", "cors", "wildcard CORS origin"},
      Pattern.compile("printStackTrace\\(\\)"),
      new String[] {"LOW", "information-disclosure", "stack traces printed instead of logged"},
      Pattern.compile("new Random\\("),
      new String[] {"MEDIUM", "weak-randomness",
          "java.util.Random where SecureRandom may be required (identifiers, tokens)"},
      Pattern.compile("ObjectInputStream"),
      new String[] {"HIGH", "deserialization", "native Java deserialization of untrusted data"});

  private SecurityScanner() {}

  /**
   * Scans changed Java main sources.
   *
   * @param root tree to read
   * @param changed workspace-relative paths changed since the baseline
   * @return scanner findings
   */
  public static List<SecurityReview.Finding> scan(Path root, List<String> changed) {
    List<SecurityReview.Finding> findings = new ArrayList<>();
    for (String path : changed) {
      if (!path.startsWith("src/main/") || !path.endsWith(".java")) {
        continue;
      }
      String code = SourceContext.readIfExists(root, path);
      RULES.forEach((pattern, rule) -> {
        if (pattern.matcher(code).find()) {
          findings.add(new SecurityReview.Finding(rule[0], "scanner", rule[1], path, rule[2],
              "fix or justify in review"));
        }
      });
    }
    return findings;
  }
}
