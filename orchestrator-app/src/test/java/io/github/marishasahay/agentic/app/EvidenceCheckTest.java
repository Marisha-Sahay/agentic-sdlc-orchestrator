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

  private static final String PATCH = "diff --git a/x b/x\n+accepted\n";

  private Path evidence(boolean releasedOnValidatedTree) throws Exception {
    return evidence("SUCCEEDED", List.of(
        new RunEvent.NodeStarted("release", "RELEASE_READINESS", 1, "primary"),
        accepted(releasedOnValidatedTree ? "tree-1" : "tree-0"),
        released("tree-1")));
  }

  /** Final validation accepts tree-1, then the given release events, then the run ends. */
  private Path evidence(String status, List<RunEvent> releaseEvents) throws Exception {
    Path dir = tmp.resolve("brownfield");
    Files.createDirectories(dir.resolve("artifacts/release.notes"));
    Files.createDirectories(dir.resolve("artifacts/release.patch"));
    Files.writeString(dir.resolve("artifacts/release.notes/v1.txt"), "notes");
    Files.writeString(dir.resolve("artifacts/release.patch/v1.txt"), PATCH);
    Files.writeString(dir.resolve("release.patch"), PATCH);
    EventLog log = new EventLog("r", dir.resolve("events.jsonl"), Clock.systemUTC());
    log.append(new RunEvent.RunStarted("brownfield", "sha", 1, Map.of()));
    log.append(new RunEvent.AgentCalled("release", "release-manager", "fixture:hand-authored",
        "fixture", 0, -1, -1));
    log.append(new RunEvent.NodeSucceeded("final-validation", "VERIFICATION", 1, Map.of(),
        Map.of(), null, "tree-1"));
    log.append(new RunEvent.ArtifactProduced("release.notes", 1, Hashing.sha256("notes"),
        "release", "release.notes/v1.txt"));
    log.append(new RunEvent.ArtifactProduced("release.patch", 1, Hashing.sha256(PATCH),
        "release", "release.patch/v1.txt"));
    for (RunEvent event : releaseEvents) {
      log.append(event);
    }
    log.append(new RunEvent.RunFinished(status, ""));
    return dir;
  }

  private static RunEvent.ApprovalResolved accepted(String tree) {
    return new RunEvent.ApprovalResolved("release", true, "demo", "ok", List.of(), "ACCEPTANCE",
        "p", tree);
  }

  private static RunEvent.NodeSucceeded released(String tree) {
    return new RunEvent.NodeSucceeded("release", "RELEASE_READINESS", 1, Map.of(),
        Map.of("release.patch", "release.patch@v1#" + Hashing.sha256(PATCH).substring(0, 12)),
        null, tree);
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

  @Test
  void failsWhenOnlyTheExportedPatchWasChanged() throws Exception {
    Path dir = evidence(true);
    Files.writeString(dir.resolve("release.patch"), PATCH + "+smuggled\n");

    assertThat(EvidenceCheck.check(dir).problems()).singleElement().asString()
        .startsWith("exported release.patch differs from the accepted artifact release.patch@v1");
  }

  @Test
  void reportsARunThatNeverReachedReleaseInsteadOfCrashing() throws Exception {
    // A paused run: final validation passed, the release never succeeded, and a patch file was
    // left in the run directory.
    Path dir = evidence("PAUSED", List.of(
        new RunEvent.NodeStarted("release", "RELEASE_READINESS", 1, "primary")));

    assertThat(EvidenceCheck.check(dir).problems()).containsExactlyInAnyOrder(
        "run did not finish SUCCEEDED (PAUSED)",
        "no accepted release",
        "no release.patch artifact published by an accepted release");
  }

  @Test
  void failsWhenTheReleaseCommittedOnATreeOtherThanTheValidatedOne() throws Exception {
    // Attempt 1 is accepted on the validated tree but not committed; attempt 2 commits on
    // another tree without being accepted.
    Path dir = evidence("SUCCEEDED", List.of(
        new RunEvent.NodeStarted("release", "RELEASE_READINESS", 1, "primary"),
        accepted("tree-1"),
        new RunEvent.NodeStarted("release", "RELEASE_READINESS", 2, "primary"),
        released("tree-2")));

    assertThat(EvidenceCheck.check(dir).problems()).containsExactly(
        "release committed on tree tree-2, not the validated tree tree-1");
  }

  @Test
  void theAcceptedPatchIsTheVersionTheReleaseNamedNotTheNewestOne() throws Exception {
    // v2 was persisted (and even logged) by an attempt that was then withdrawn.
    String withdrawn = PATCH + "+withdrawn\n";
    Path dir = evidence("SUCCEEDED", List.of(
        new RunEvent.NodeStarted("release", "RELEASE_READINESS", 1, "primary"),
        accepted("tree-1"),
        released("tree-1"),
        new RunEvent.ArtifactProduced("release.patch", 2, Hashing.sha256(withdrawn), "release",
            "release.patch/v2.txt")));
    Files.writeString(dir.resolve("artifacts/release.patch/v2.txt"), withdrawn);

    assertThat(EvidenceCheck.acceptedPatch(EventLog.readFile(dir.resolve("events.jsonl"))))
        .hasValueSatisfying(a -> assertThat(a.path()).isEqualTo("release.patch/v1.txt"));
    assertThat(EvidenceCheck.check(dir).problems()).isEmpty();
  }

  @Test
  void anAcceptanceCountsOnlyForTheReleaseAttemptItWasGivenIn() throws Exception {
    // Same tree, but the attempt that committed was never accepted.
    Path dir = evidence("SUCCEEDED", List.of(
        new RunEvent.NodeStarted("release", "RELEASE_READINESS", 1, "primary"),
        accepted("tree-1"),
        new RunEvent.NodeStarted("release", "RELEASE_READINESS", 2, "primary"),
        released("tree-1")));

    assertThat(EvidenceCheck.check(dir).problems()).containsExactly(
        "release was not accepted on the validated tree tree-1");
  }
}
