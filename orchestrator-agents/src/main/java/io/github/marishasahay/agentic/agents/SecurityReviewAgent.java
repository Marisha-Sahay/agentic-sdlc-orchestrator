package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.Git;
import io.github.marishasahay.agentic.agents.analysis.SourceContext;
import io.github.marishasahay.agentic.agents.contract.SecurityReview;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Two-layer review. A deterministic scanner flags known-dangerous patterns in changed files;
 * these findings are authoritative and HIGH ones block. A model then reviews the diff for logic
 * flaws the scanner cannot see; its findings are advisory and surface at release approval, so
 * a model false positive can't stall the pipeline and a model false negative can't wave a
 * scanner finding through.
 */
public final class SecurityReviewAgent implements NodeTask {

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

  private static final String SYSTEM = """
      You are an application security engineer reviewing a change to a public URL shortener.
      Look for: open redirect and phishing abuse, SSRF, injection, missing input validation,
      enumeration of codes, rate-limit bypass, privacy of analytics data (IPs, user agents),
      unbounded growth (DoS), error messages leaking internals, unsafe defaults.
      Report concrete findings only, each with location and recommendation. Use source
      "reviewer" for every finding.""";

  private final AgentSupport llm;

  public SecurityReviewAgent(AgentSupport llm) {
    this.llm = llm;
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    List<String> changed = List.of(ctx.input(ArtifactKeys.CHANGED_FILES).split("\n"));
    List<SecurityReview.Finding> findings = new ArrayList<>(scan(ctx, changed));
    String diff = Git.diffSinceBaseline(ctx.workspaceRoot());
    SecurityReview review = llm.ask(ctx, "security-reviewer", SYSTEM,
        "## Requirements\n" + ctx.input(ArtifactKeys.REQUIREMENTS)
            + "\n\n## Diff since baseline\n" + diff.substring(0, Math.min(diff.length(), 90_000)),
        SecurityReview.class);
    findings.addAll(AgentSupport.list(review.findings()).stream()
        .map(f -> new SecurityReview.Finding(f.severity(), "reviewer", f.category(),
            f.location(), f.description(), f.recommendation())).toList());
    SecurityReview combined = new SecurityReview(review.summary(), findings);
    return NodeResult.builder()
        .output(ArtifactKeys.SECURITY, AgentSupport.pretty(combined))
        .summary(findings.size() + " finding(s); " + review.summary())
        .build();
  }

  static List<SecurityReview.Finding> scan(NodeContext ctx, List<String> changed) {
    List<SecurityReview.Finding> findings = new ArrayList<>();
    for (String path : changed) {
      if (!path.startsWith("src/main/") || !path.endsWith(".java")) {
        continue;
      }
      String code = SourceContext.readIfExists(ctx.workspaceRoot(), path);
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
