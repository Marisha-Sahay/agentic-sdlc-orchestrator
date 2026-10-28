package io.github.marishasahay.agentic.core.workspace;

import java.util.Objects;

/**
 * A proposed change to one file. Agents only ever propose; the engine decides whether and when
 * to apply, which is what keeps agent autonomy bounded.
 *
 * @param path workspace-relative path using forward slashes
 * @param content full new content for {@link Operation#WRITE}, ignored for delete
 * @param operation write or delete
 */
public record FileChange(String path, String content, Operation operation) {

  /** Kind of change. */
  public enum Operation {
    WRITE,
    DELETE
  }

  public FileChange {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(operation, "operation");
    path = path.replace('\\', '/');
    content = content == null ? "" : content;
  }

  public static FileChange write(String path, String content) {
    return new FileChange(path, content, Operation.WRITE);
  }

  public static FileChange delete(String path) {
    return new FileChange(path, "", Operation.DELETE);
  }
}
