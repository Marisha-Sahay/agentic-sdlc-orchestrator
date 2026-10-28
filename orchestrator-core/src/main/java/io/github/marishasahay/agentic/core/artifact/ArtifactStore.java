package io.github.marishasahay.agentic.core.artifact;

import io.github.marishasahay.agentic.core.util.Hashing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versioned, content-addressed store for everything the workflow produces. Each run has its own
 * store; when a directory is given, every version is also written to disk so a stopped run can
 * be resumed and a reviewer can read the outputs directly.
 */
public final class ArtifactStore {

  private final Map<String, List<Artifact>> versions = new ConcurrentHashMap<>();
  private final Path directory;

  /**
   * Creates a store.
   *
   * @param directory where versions are persisted, or null for memory only
   */
  public ArtifactStore(Path directory) {
    this.directory = directory;
  }

  public static ArtifactStore inMemory() {
    return new ArtifactStore(null);
  }

  /**
   * Stores a new version. Storing content identical to the latest version is a no-op, which
   * keeps re-runs from producing spurious "changed upstream" signals.
   *
   * @param key artifact key
   * @param content body
   * @param producedBy node id or reviewer
   * @param at timestamp
   * @return the stored (or unchanged latest) version
   */
  public synchronized Artifact put(String key, String content, String producedBy, Instant at) {
    String hash = Hashing.sha256(content);
    List<Artifact> history = versions.computeIfAbsent(key, k -> new ArrayList<>());
    if (!history.isEmpty() && history.getLast().sha256().equals(hash)) {
      return history.getLast();
    }
    Artifact artifact = new Artifact(key, history.size() + 1, hash, producedBy, at, content);
    history.add(artifact);
    persist(artifact);
    return artifact;
  }

  /**
   * Re-registers a version recovered from the event log (resume path).
   *
   * @param artifact version to restore
   */
  public synchronized void restore(Artifact artifact) {
    List<Artifact> history = versions.computeIfAbsent(artifact.key(), k -> new ArrayList<>());
    history.removeIf(a -> a.version() == artifact.version());
    history.add(artifact);
    history.sort((a, b) -> Integer.compare(a.version(), b.version()));
  }

  public Optional<Artifact> latest(String key) {
    List<Artifact> history = versions.get(key);
    return history == null || history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
  }

  public String content(String key) {
    return latest(key).map(Artifact::content)
        .orElseThrow(() -> new IllegalStateException("Missing artifact: " + key));
  }

  public List<Artifact> history(String key) {
    return List.copyOf(versions.getOrDefault(key, List.of()));
  }

  public Set<String> keys() {
    return new TreeSet<>(versions.keySet());
  }

  /**
   * Path of a persisted version, relative to the store directory.
   *
   * @param artifact the version
   * @return relative file path
   */
  public static String relativePath(Artifact artifact) {
    return artifact.key().replaceAll("[^A-Za-z0-9._-]", "_") + "/v" + artifact.version() + ".txt";
  }

  /**
   * Reads a persisted version back from disk.
   *
   * @param relativePath path returned by {@link #relativePath(Artifact)}
   * @return the content
   */
  public String readPersisted(String relativePath) {
    try {
      return Files.readString(directory.resolve(relativePath), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void persist(Artifact artifact) {
    if (directory == null) {
      return;
    }
    Path file = directory.resolve(relativePath(artifact));
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, artifact.content(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot persist artifact " + artifact.ref(), e);
    }
  }
}
