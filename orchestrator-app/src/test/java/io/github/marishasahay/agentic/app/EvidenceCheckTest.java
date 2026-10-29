package io.github.marishasahay.agentic.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.util.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceCheckTest {

  @TempDir
  Path tmp;

  private Path evidence(boolean releasedOnValidatedTree) throws Exception {
    Path dir = tmp.resolve("brownfield");
    Files.createDirectories(dir.resolve("artifacts/release.notes"));
    Files.writeString(dir.resolve("artifacts/release.notes/v1.txt"), "notes");
    Files.writeString(dir.resolve("release.patch"), "diff --git a/x b/x");
    EventLog log = new EventLog("r", dir.resolve("events.jsonl"), Clock.systemUTC());
    log.append(new RunEvent.RunStarted("brownfield", "sha", 1, Map.of()));
    log.append(new RunEvent.AgentCalled("release", "release-manager", "fixture:hand-authored",
        "fixture", 0, -1, -1));
    log.append(new RunEvent.NodeSucceeded("final-validation", "VERIFICATION", 1, Map.of(),
        Map.of(), null, "tree-1"));
    log.append(new RunEvent.ArtifactProduced("release.notes", 1, Hashing.sha256("notes"),
        "release", "release.notes/v1.txt"));
    log.append(new RunEvent.ApprovalResolved("release", true, "demo", "ok", List.of(),
        "ACCEPTANCE", "p", releasedOnValidatedTree ? "tree-1" : "tree-0"));
    log.append(new RunEvent.RunFinished("SUCCEEDED", ""));
    return dir;
  }

  @Test
  void acceptsCompleteEvidenceAndReportsModelProvenance() throws Exception {
    EvidenceCheck.Result result = EvidenceCheck.check(evidence(true));

    assertThat(result.problems()).isEmpty();
    assertThat(result.provenance()).containsEntry("fixture", 1);
  }

  @Test
  void failsWhenAScenarioOrItsEvidenceIsMissingOrDoesNotHoldUp() throws Exception {
    assertThat(EvidenceCheck.check(tmp.resolve("ambiguous")).problems()).singleElement()
        .asString().contains("missing").contains("events.jsonl");

    Path dir = evidence(false);
    Files.writeString(dir.resolve("artifacts/release.notes/v1.txt"), "edited");
    Files.delete(dir.resolve("release.patch"));

    assertThat(EvidenceCheck.check(dir).problems()).containsExactlyInAnyOrder(
        "artifact release.notes@v1 does not match its hash",
        "release was not accepted on the validated tree tree-1",
        "missing or empty release.patch");
  }
}
