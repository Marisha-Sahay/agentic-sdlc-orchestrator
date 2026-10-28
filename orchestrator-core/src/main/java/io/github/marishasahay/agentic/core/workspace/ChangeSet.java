package io.github.marishasahay.agentic.core.workspace;

import java.util.List;

/**
 * The full set of file changes an attempt proposes.
 *
 * @param changes individual file changes
 */
public record ChangeSet(List<FileChange> changes) {

  public ChangeSet {
    changes = List.copyOf(changes);
  }

  public static ChangeSet empty() {
    return new ChangeSet(List.of());
  }

  public static ChangeSet of(FileChange... changes) {
    return new ChangeSet(List.of(changes));
  }

  public boolean isEmpty() {
    return changes.isEmpty();
  }

  public List<String> paths() {
    return changes.stream().map(FileChange::path).toList();
  }

  /**
   * Rough size of the change, used by the change-size policy.
   *
   * @return number of lines written (deletes count as one)
   */
  public int linesTouched() {
    return changes.stream()
        .mapToInt(c -> c.operation() == FileChange.Operation.DELETE
            ? 1 : (int) c.content().lines().count())
        .sum();
  }
}
