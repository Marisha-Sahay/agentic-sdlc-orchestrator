package io.github.marishasahay.agentic.core.engine;

import io.github.marishasahay.agentic.core.workspace.ChangeSet;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node's proposal: artifacts to publish, file changes to apply and decisions to record.
 * Nothing in here takes effect until it has passed policy, gates and (if needed) approval.
 *
 * @param outputs artifact key to content
 * @param changes proposed workspace changes
 * @param decisions decisions with rationale
 * @param summary one-paragraph summary for reviewers
 * @param approvalRequirements reasons the task itself declares for a human acceptance decision,
 *     code to message (e.g. {@code degraded-verification}); a task can add reasons, never
 *     remove the ones the node configuration or policy require
 */
public record NodeResult(
    Map<String, String> outputs, ChangeSet changes, List<Decision> decisions, String summary,
    Map<String, String> approvalRequirements) {

  public NodeResult {
    outputs = Map.copyOf(outputs);
    changes = changes == null ? ChangeSet.empty() : changes;
    decisions = List.copyOf(decisions);
    summary = summary == null ? "" : summary;
    approvalRequirements = approvalRequirements == null ? Map.of()
        : Map.copyOf(approvalRequirements);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder. */
  public static final class Builder {
    private final Map<String, String> outputs = new LinkedHashMap<>();
    private final List<FileChange> changes = new ArrayList<>();
    private final List<Decision> decisions = new ArrayList<>();
    private String summary = "";
    private final Map<String, String> approvalRequirements = new LinkedHashMap<>();

    private Builder() {}

    public Builder output(String key, String content) {
      outputs.put(key, content);
      return this;
    }

    public Builder change(FileChange change) {
      changes.add(change);
      return this;
    }

    public Builder changes(List<FileChange> values) {
      changes.addAll(values);
      return this;
    }

    public Builder decision(Decision decision) {
      decisions.add(decision);
      return this;
    }

    public Builder summary(String value) {
      this.summary = value;
      return this;
    }

    /**
     * Declares that accepting this result needs a human decision.
     *
     * @param code stable reason code a scripted decision must cover
     * @param message what the reviewer is accepting
     * @return this builder
     */
    public Builder requireApproval(String code, String message) {
      approvalRequirements.put(code, message);
      return this;
    }

    public NodeResult build() {
      return new NodeResult(outputs, new ChangeSet(changes), decisions, summary,
          approvalRequirements);
    }
  }
}
