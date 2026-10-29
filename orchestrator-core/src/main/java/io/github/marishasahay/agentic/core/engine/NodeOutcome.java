package io.github.marishasahay.agentic.core.engine;

import java.util.Map;
import java.util.Set;

/**
 * What a node runner reports back to the scheduler thread.
 *
 * @param nodeId node
 * @param kind outcome
 * @param reason failure or stop reason
 * @param inputs consumed artifact key to SHA-256 (for staleness checks)
 * @param updatedKeys artifact keys that got a new version (outputs and reviewer amendments)
 */
record NodeOutcome(
    String nodeId, Kind kind, String reason, Map<String, String> inputs, Set<String> updatedKeys) {

  enum Kind {
    SUCCEEDED,
    FAILED,
    STOPPED,
    /** Validated work was thrown away because its inputs changed while it ran; run it again. */
    DISCARDED
  }

  static NodeOutcome failed(String nodeId, String reason) {
    return new NodeOutcome(nodeId, Kind.FAILED, reason, Map.of(), Set.of());
  }

  static NodeOutcome stopped(String nodeId, String reason) {
    return new NodeOutcome(nodeId, Kind.STOPPED, reason, Map.of(), Set.of());
  }

  static NodeOutcome discarded(String nodeId, String reason) {
    return new NodeOutcome(nodeId, Kind.DISCARDED, reason, Map.of(), Set.of());
  }
}
