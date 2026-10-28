package io.github.marishasahay.agentic.core.approval;

import java.util.Map;

/**
 * A reviewer's decision. Amendments let the reviewer edit any artifact (not only the node's own
 * outputs) while approving; the engine stores them as new versions authored by the reviewer and
 * re-plans every node that consumed the previous version.
 *
 * @param approved whether the proposal is accepted
 * @param reviewer who decided
 * @param comment rationale; on rejection it is fed back to the agent
 * @param amendments artifact key to replacement content
 */
public record ApprovalDecision(
    boolean approved, String reviewer, String comment, Map<String, String> amendments) {

  public ApprovalDecision {
    comment = comment == null ? "" : comment;
    amendments = amendments == null ? Map.of() : Map.copyOf(amendments);
  }

  public static ApprovalDecision approve(String reviewer, String comment) {
    return new ApprovalDecision(true, reviewer, comment, Map.of());
  }

  public static ApprovalDecision reject(String reviewer, String comment) {
    return new ApprovalDecision(false, reviewer, comment, Map.of());
  }

  public static ApprovalDecision approveWithAmendments(
      String reviewer, String comment, Map<String, String> amendments) {
    return new ApprovalDecision(true, reviewer, comment, amendments);
  }
}
