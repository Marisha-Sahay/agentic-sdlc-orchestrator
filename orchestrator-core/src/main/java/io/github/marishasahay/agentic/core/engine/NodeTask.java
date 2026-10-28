package io.github.marishasahay.agentic.core.engine;

/**
 * The work a node does: usually an agent call, sometimes deterministic code. A task reads inputs
 * through the context (which records lineage) and returns a proposal; it never writes the
 * workspace itself.
 */
@FunctionalInterface
public interface NodeTask {

  /**
   * Produces a proposal.
   *
   * @param context inputs, retry feedback and telemetry for this attempt
   * @return outputs, file changes and decisions to be gated
   * @throws Exception any failure; treated as a failed attempt
   */
  NodeResult execute(NodeContext context) throws Exception;
}
