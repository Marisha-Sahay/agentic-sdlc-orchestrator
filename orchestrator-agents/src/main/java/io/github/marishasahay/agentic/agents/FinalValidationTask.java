package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.Git;
import io.github.marishasahay.agentic.agents.contract.ValidationEvidence;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import io.github.marishasahay.agentic.core.util.Globs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Final validation over one immutable tree. Deterministic: it pins the tree (the snapshot it
 * reads is the final accepted state) and checks that evidence which cannot be cheaply re-run,
 * such as the model-assisted security review, still describes that tree. The node's exit gates
 * then re-run every deterministic check (build, tests, scanner, contract, docs) on the same
 * tree, and release readiness may only reference it.
 */
public final class FinalValidationTask implements NodeTask {

  private final String verificationProfile;
  private final List<String> checks;
  private final List<String> skippedChecks;
  private final String sandbox;
  private final Map<String, List<String>> evidence;

  /**
   * Creates the task.
   *
   * @param verificationProfile {@code FULL} or {@code DEGRADED}
   * @param checks names of the gates that validate the tree
   * @param skippedChecks checks this environment cannot run
   * @param sandbox where builds execute
   * @param evidence upstream node to the globs of files that may change after it without
   *     invalidating its evidence (e.g. documentation after a security review)
   */
  public FinalValidationTask(String verificationProfile, List<String> checks,
      List<String> skippedChecks, String sandbox, Map<String, List<String>> evidence) {
    this.verificationProfile = verificationProfile;
    this.checks = List.copyOf(checks);
    this.skippedChecks = List.copyOf(skippedChecks);
    this.sandbox = sandbox;
    this.evidence = Map.copyOf(evidence);
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    Path root = ctx.workspaceRoot();
    String tree = Git.tree(root, "HEAD");
    List<String> stale = new ArrayList<>();
    evidence.forEach((node, mayChangeAfter) -> {
      Optional<String> reviewed = ctx.acceptedTree(node);
      if (reviewed.isEmpty()) {
        stale.add(node + ": no accepted result to rely on");
        return;
      }
      List<String> changed = Git.changedBetween(root, reviewed.get(), tree).stream()
          .filter(path -> mayChangeAfter.stream().noneMatch(g -> Globs.matches(g, path)))
          .toList();
      if (!changed.isEmpty()) {
        stale.add(node + " evaluated tree " + reviewed.get().substring(0, 12) + ", but "
            + changed + " changed afterwards; its evidence does not cover the final tree");
      }
    });
    ValidationEvidence result = new ValidationEvidence(tree, ctx.baseRevision(),
        verificationProfile, checks, skippedChecks, sandbox, stale);
    return NodeResult.builder()
        .output(ArtifactKeys.VALIDATION, AgentSupport.pretty(result))
        .summary("Final validation of tree " + tree.substring(0, 12) + " (" + verificationProfile
            + ")" + (stale.isEmpty() ? "" : "; stale evidence: " + stale))
        .build();
  }
}
