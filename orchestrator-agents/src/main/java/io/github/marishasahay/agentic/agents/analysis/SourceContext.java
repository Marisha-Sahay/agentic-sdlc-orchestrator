package io.github.marishasahay.agentic.agents.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Assembles source files into prompt context under a character budget, most relevant first.
 * For a service of this size the whole main source tree fits; for large codebases this is where
 * retrieval would plug in (documented limitation).
 */
public final class SourceContext {

  private static final Set<String> SKIPPED = Set.of("target", ".git", "node_modules", ".idea");
  private static final List<String> TEXT_SUFFIXES = List.of(".java", ".yml", ".yaml", ".sql",
      ".properties", ".xml", ".md");

  private SourceContext() {}

  /**
   * Renders a file tree plus file contents.
   *
   * @param root project root
   * @param priority paths to include first (e.g. the task's files)
   * @param includeTests whether test sources may be included after main sources
   * @param budgetChars maximum characters of file content
   * @return prompt-ready text
   */
  public static String render(Path root, List<String> priority, boolean includeTests,
      int budgetChars) {
    List<String> all = list(root);
    StringBuilder out = new StringBuilder("### Files in the workspace\n");
    all.forEach(p -> out.append("- ").append(p).append('\n'));
    Set<String> ordered = new LinkedHashSet<>();
    priority.stream().filter(all::contains).forEach(ordered::add);
    all.stream().filter(p -> p.startsWith("src/main/") || p.startsWith("api/"))
        .forEach(ordered::add);
    ordered.add("pom.xml");
    if (includeTests) {
      all.stream().filter(p -> p.startsWith("src/test/")).forEach(ordered::add);
    }
    int used = 0;
    int skipped = 0;
    out.append("\n### File contents\n");
    for (String path : ordered) {
      Path file = root.resolve(path);
      if (!Files.isRegularFile(file) || TEXT_SUFFIXES.stream().noneMatch(path::endsWith)) {
        continue;
      }
      String content = read(file);
      if (used + content.length() > budgetChars) {
        skipped++;
        continue;
      }
      used += content.length();
      out.append("\n=== ").append(path).append(" ===\n").append(content).append('\n');
    }
    if (skipped > 0) {
      out.append("\n(").append(skipped).append(" more files omitted to stay within budget)\n");
    }
    return out.toString();
  }

  /**
   * Reads one workspace file if it exists.
   *
   * @param root project root
   * @param path relative path
   * @return content or empty string
   */
  public static String readIfExists(Path root, String path) {
    Path file = root.resolve(path);
    return Files.isRegularFile(file) ? read(file) : "";
  }

  private static List<String> list(Path root) {
    try (Stream<Path> walk = Files.walk(root)) {
      List<String> files = new ArrayList<>();
      walk.filter(Files::isRegularFile).forEach(p -> {
        Path rel = root.relativize(p);
        if (SKIPPED.stream().noneMatch(rel::startsWith)) {
          files.add(rel.toString().replace('\\', '/'));
        }
      });
      files.sort(String::compareTo);
      return files;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
