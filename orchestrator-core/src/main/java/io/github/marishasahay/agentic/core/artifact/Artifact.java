package io.github.marishasahay.agentic.core.artifact;

import java.time.Instant;

/**
 * One immutable version of a named output (requirement spec, design, plan, test report...).
 * The content hash is what makes cross-stage lineage and change detection possible.
 *
 * @param key logical name, e.g. {@code design.openapi}
 * @param version 1-based version number within the key
 * @param sha256 hex SHA-256 of the content
 * @param producedBy node id, or {@code human:<name>} for reviewer amendments
 * @param createdAt when the version was stored
 * @param content the artifact body (text or JSON)
 */
public record Artifact(
    String key, int version, String sha256, String producedBy, Instant createdAt, String content) {

  /**
   * Short, human-readable reference used in lineage records.
   *
   * @return e.g. {@code design.openapi@v2#1a2b3c4d5e6f}
   */
  public String ref() {
    return key + "@v" + version + "#" + sha256.substring(0, 12);
  }
}
