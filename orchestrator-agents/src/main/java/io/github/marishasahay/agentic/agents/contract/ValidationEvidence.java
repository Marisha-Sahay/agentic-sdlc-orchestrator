package io.github.marishasahay.agentic.agents.contract;

import java.util.List;

/**
 * What final validation established, and for exactly which tree. Release readiness may only
 * reference this tree; anything that changes it afterwards invalidates the evidence.
 *
 * @param treeSha git tree validated
 * @param revision commit holding that tree
 * @param verificationProfile {@code FULL}, or {@code DEGRADED} when checks were skipped
 * @param checks gates run on the tree
 * @param skippedChecks checks that could not run in this environment (empty when FULL)
 * @param sandbox where builds and tests executed
 * @param staleEvidence upstream evidence that no longer describes this tree (must be empty)
 */
public record ValidationEvidence(
    String treeSha,
    String revision,
    String verificationProfile,
    List<String> checks,
    List<String> skippedChecks,
    String sandbox,
    List<String> staleEvidence) {

  public boolean degraded() {
    return !"FULL".equals(verificationProfile);
  }
}
