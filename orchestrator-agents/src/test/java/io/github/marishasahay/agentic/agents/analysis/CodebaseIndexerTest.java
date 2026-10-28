package io.github.marishasahay.agentic.agents.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodebaseIndexerTest {

  private static final Path BASELINE = Path.of("..", "url-shortener").toAbsolutePath()
      .normalize();

  @TempDir
  Path tmp;

  @Test
  void indexesTheRealBaselineServiceDeterministically() {
    CodebaseIndex index = new CodebaseIndexer().index(BASELINE);

    assertThat(index.endpoints()).extracting(e -> e.method() + " " + e.path())
        .contains("POST /api/v1/links", "GET /api/v1/links/{code}",
            "DELETE /api/v1/links/{code}");
    assertThat(index.endpoints()).anySatisfy(e ->
        assertThat(e.path()).startsWith("/{code"));
    assertThat(index.sources()).anySatisfy(s -> {
      assertThat(s.name()).isEqualTo("LinkService");
      assertThat(s.role()).isEqualTo("service");
      assertThat(s.dependencies()).contains("LinkRepository", "LinkResolver", "Clock");
    });
    assertThat(index.migrations()).containsExactly(
        "src/main/resources/db/migration/V1__create_links.sql");
    assertThat(index.contains("src/main/java/io/github/marishasahay/shortener/link/"
        + "LinkResolver.java")).isTrue();
    assertThat(index.contains("src/main/java/Invented.java")).isFalse();
    assertThat(index.render()).contains("[controller class]").contains("### Endpoints");
    assertThat(new CodebaseIndexer().index(BASELINE)).isEqualTo(index);
  }

  @Test
  void sourceContextPrioritisesRequestedFilesAndRespectsTheBudget() throws Exception {
    Files.createDirectories(tmp.resolve("src/main/java"));
    Files.writeString(tmp.resolve("src/main/java/A.java"), "class A {}");
    Files.writeString(tmp.resolve("src/main/java/Big.java"), "x".repeat(5_000));
    Files.writeString(tmp.resolve("pom.xml"), "<project/>");

    String context = SourceContext.render(tmp, java.util.List.of("src/main/java/A.java"), false,
        1_000);

    assertThat(context).contains("- src/main/java/Big.java").contains("=== src/main/java/A.java")
        .doesNotContain("=== src/main/java/Big.java").contains("omitted to stay within budget");
    assertThat(SourceContext.readIfExists(tmp, "missing.txt")).isEmpty();
  }
}
