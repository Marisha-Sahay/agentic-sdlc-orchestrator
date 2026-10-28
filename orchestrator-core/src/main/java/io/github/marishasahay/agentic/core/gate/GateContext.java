package io.github.marishasahay.agentic.core.gate;

import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import java.nio.file.Path;
import java.util.Map;

/**
 * What a gate can see.
 *
 * @param node node being gated
 * @param artifacts accepted artifacts so far
 * @param proposedOutputs the attempt's outputs (empty for entry gates)
 * @param workspaceRoot workspace with the attempt's changes applied, or null if none
 */
public record GateContext(
    NodeSpec node,
    ArtifactStore artifacts,
    Map<String, String> proposedOutputs,
    Path workspaceRoot) {

  /**
   * Looks up an output of the current attempt, falling back to the accepted store.
   *
   * @param key artifact key
   * @return the content, or null if neither has it
   */
  public String output(String key) {
    String proposed = proposedOutputs.get(key);
    return proposed != null ? proposed : artifacts.latest(key).map(a -> a.content()).orElse(null);
  }
}
