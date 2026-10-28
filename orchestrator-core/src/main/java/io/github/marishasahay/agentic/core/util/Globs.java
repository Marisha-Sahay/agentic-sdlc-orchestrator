package io.github.marishasahay.agentic.core.util;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;

/** Glob matching where {@code **}{@code /x} also matches {@code x} at the root. */
public final class Globs {

  private Globs() {}

  /**
   * Matches a workspace-relative path against a glob.
   *
   * @param glob pattern such as {@code src/main/**} or {@code **}{@code /pom.xml}
   * @param path workspace-relative path with forward slashes
   * @return whether the path matches
   */
  public static boolean matches(String glob, String path) {
    Path candidate = Path.of(path);
    if (matcher(glob).matches(candidate)) {
      return true;
    }
    return glob.startsWith("**/") && matcher(glob.substring(3)).matches(candidate);
  }

  private static PathMatcher matcher(String glob) {
    return FileSystems.getDefault().getPathMatcher("glob:" + glob);
  }
}
