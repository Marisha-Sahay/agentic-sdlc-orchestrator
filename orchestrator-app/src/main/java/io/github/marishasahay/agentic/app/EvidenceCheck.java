package io.github.marishasahay.agentic.app;

import io.github.marishasahay.agentic.core.artifact.Artifact;
import io.github.marishasahay.agentic.core.event.EventEnvelope;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Checks a saved run's evidence: the audit chain verifies, every persisted artifact matches the
 * hash in the log, the run succeeded, the release committed on the tree final validation
 * validated and the attempt that committed was accepted on it, and the exported
 * {@code release.patch} (what {@code tools/final-service.sh} applies) is exactly the patch
 * artifact the accepted release published. Also reports where the model replies came from.
 */
final class EvidenceCheck {

  private static final String RELEASE_PATCH = "release.patch";

  private EvidenceCheck() {}

  /**
   * Result of a check.
   *
   * @param problems what is wrong; empty when the evidence holds
   * @param provenance model reply source to number of calls (live, record, replay, fixture)
   */
  record Result(List<String> problems, Map<String, Integer> provenance) {
    boolean ok() {
      return problems.isEmpty();
    }
  }

  static Result check(Path dir) {
    List<String> problems = new ArrayList<>();
    Map<String, Integer> provenance = new TreeMap<>();
    Path log = dir.resolve("events.jsonl");
    if (!Files.isRegularFile(log)) {
      return new Result(List.of("missing " + log), provenance);
    }
    List<EventEnvelope> events = EventLog.readFile(log);
    EventLog.verifyChain(events).ifPresent(seq -> problems.add("audit chain broken at " + seq));
    String finished = null;
    String validatedTree = null;
    String attemptAcceptedTree = null;
    String releaseTree = null;
    String releaseAcceptedTree = null;
    for (EventEnvelope envelope : events) {
      switch (envelope.event()) {
        case RunEvent.ArtifactProduced a -> checkArtifact(dir, a, problems);
        case RunEvent.AgentCalled c -> provenance.merge(c.mode(), 1, Integer::sum);
        case RunEvent.NodeSucceeded s when s.nodeId().equals("final-validation") ->
            validatedTree = s.treeSha();
        // An acceptance counts only for the release attempt it was given in.
        case RunEvent.NodeStarted n when n.nodeId().equals("release") ->
            attemptAcceptedTree = null;
        case RunEvent.ApprovalResolved r when r.nodeId().equals("release") && r.approved()
            && "ACCEPTANCE".equals(r.kind()) -> attemptAcceptedTree = r.treeSha();
        case RunEvent.NodeSucceeded s when s.nodeId().equals("release") -> {
          releaseTree = s.treeSha();
          releaseAcceptedTree = attemptAcceptedTree;
        }
        case RunEvent.RunFinished f -> finished = f.status();
        default -> {
          // not evidence-relevant
        }
      }
    }
    if (!"SUCCEEDED".equals(finished)) {
      problems.add("run did not finish SUCCEEDED (" + finished + ")");
    }
    if (validatedTree == null) {
      problems.add("no accepted final validation");
    } else if (releaseTree == null) {
      problems.add("no accepted release");
    } else if (!validatedTree.equals(releaseTree)) {
      problems.add("release committed on tree " + releaseTree + ", not the validated tree "
          + validatedTree);
    } else if (!validatedTree.equals(releaseAcceptedTree)) {
      problems.add("release was not accepted on the validated tree " + validatedTree);
    }
    checkExportedPatch(dir, acceptedPatch(events), problems);
    return new Result(problems, provenance);
  }

  /**
   * The patch the accepted release published: the {@code release.patch} version named by the
   * last successful release. A version persisted for an attempt that was never accepted is never
   * returned, even when it has a higher number.
   *
   * @param events the run's audit log
   * @return the accepted patch's event, empty if no release succeeded with a patch
   */
  static Optional<RunEvent.ArtifactProduced> acceptedPatch(List<EventEnvelope> events) {
    String acceptedRef = null;
    for (EventEnvelope envelope : events) {
      if (envelope.event() instanceof RunEvent.NodeSucceeded s && s.nodeId().equals("release")) {
        acceptedRef = s.outputs().get(RELEASE_PATCH);
      }
    }
    if (acceptedRef == null) {
      return Optional.empty();
    }
    String wanted = acceptedRef;
    return events.stream()
        .map(EventEnvelope::event)
        .filter(RunEvent.ArtifactProduced.class::isInstance)
        .map(RunEvent.ArtifactProduced.class::cast)
        .filter(a -> Artifact.ref(a.key(), a.version(), a.sha256()).equals(wanted))
        .findFirst();
  }

  /** The exported copy must be byte-for-byte the patch the accepted release published. */
  private static void checkExportedPatch(Path dir, Optional<RunEvent.ArtifactProduced> accepted,
      List<String> problems) {
    Path patch = dir.resolve(RELEASE_PATCH);
    String exported;
    try {
      exported = Files.isRegularFile(patch) ? Files.readString(patch, StandardCharsets.UTF_8)
          : "";
    } catch (IOException e) {
      problems.add("unreadable release.patch: " + e.getMessage());
      return;
    }
    if (exported.isBlank()) {
      problems.add("missing or empty release.patch");
    } else if (accepted.isEmpty()) {
      problems.add("no release.patch artifact published by an accepted release");
    } else if (!Hashing.sha256(exported).equals(accepted.get().sha256())) {
      RunEvent.ArtifactProduced a = accepted.get();
      problems.add("exported release.patch differs from the accepted artifact "
          + Artifact.ref(a.key(), a.version(), a.sha256()));
    }
  }

  private static void checkArtifact(Path dir, RunEvent.ArtifactProduced a,
      List<String> problems) {
    Path file = dir.resolve("artifacts").resolve(a.path());
    try {
      if (!Hashing.sha256(Files.readString(file, StandardCharsets.UTF_8)).equals(a.sha256())) {
        problems.add("artifact " + a.key() + "@v" + a.version() + " does not match its hash");
      }
    } catch (IOException e) {
      problems.add("artifact " + a.key() + "@v" + a.version() + " is missing");
    }
  }
}
