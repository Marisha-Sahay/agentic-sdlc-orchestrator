package io.github.marishasahay.agentic.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorkspaceTest {

  @TempDir
  Path tmp;

  @Test
  void copiesSourcesWithoutBuildOutputIntoAFreshRepository() throws Exception {
    Path source = tmp.resolve("source");
    Files.createDirectories(source.resolve("src"));
    Files.createDirectories(source.resolve("target/classes"));
    Files.writeString(source.resolve("src/App.java"), "class App {}");
    Files.writeString(source.resolve("target/classes/App.class"), "binary");

    GitWorkspace workspace = GitWorkspace.createFrom(source, tmp.resolve("ws"));

    assertThat(workspace.root().resolve("src/App.java")).exists();
    assertThat(workspace.root().resolve("target")).doesNotExist();
    assertThat(workspace.head()).hasSize(40);
  }

  @Test
  void checkpointsDiffsAndRollsBackIncludingUntrackedFiles() throws Exception {
    GitWorkspace workspace = GitWorkspace.createFrom(null, tmp.resolve("ws"));
    workspace.apply(ChangeSet.of(FileChange.write("src/A.java", "class A {}")));
    String accepted = workspace.checkpoint("accept A");

    workspace.apply(ChangeSet.of(FileChange.write("src/A.java", "class A { broken"),
        FileChange.write("src/B.java", "class B {}"), FileChange.delete("missing.txt")));

    assertThat(workspace.changedFiles(accepted)).containsExactlyInAnyOrder("src/A.java",
        "src/B.java");
    assertThat(workspace.diff(accepted)).contains("+class A { broken").contains("src/B.java");

    workspace.rollbackTo(accepted);

    assertThat(Files.readString(workspace.root().resolve("src/A.java"))).isEqualTo("class A {}");
    assertThat(workspace.root().resolve("src/B.java")).doesNotExist();
    assertThat(workspace.head()).isEqualTo(accepted);
  }

  @Test
  void refusesPathsThatEscapeTheWorkspaceOrTouchGitInternals() {
    GitWorkspace workspace = GitWorkspace.createFrom(null, tmp.resolve("ws"));

    assertThatThrownBy(() -> workspace.apply(ChangeSet.of(FileChange.write("../evil.txt", "x"))))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> workspace.apply(ChangeSet.of(FileChange.write(".git/config", "x"))))
        .isInstanceOf(SecurityException.class);
    assertThat(tmp.resolve("evil.txt")).doesNotExist();
  }

  @Test
  void reopensAnExistingWorkspace() {
    GitWorkspace created = GitWorkspace.createFrom(null, tmp.resolve("ws"));

    assertThat(GitWorkspace.open(tmp.resolve("ws")).head()).isEqualTo(created.head());
    assertThatThrownBy(() -> GitWorkspace.open(tmp)).isInstanceOf(IllegalArgumentException.class);
  }
}
