package io.github.marishasahay.agentic.core.graph;

import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import java.util.List;

/**
 * Turns a node's accepted outputs into new nodes (e.g. a planner's task list into implementation
 * nodes). Expansion is a pure function of stored artifacts, so the same subgraph is rebuilt
 * deterministically on resume without calling an agent again.
 */
@FunctionalInterface
public interface GraphExpansion {

  /**
   * Builds the nodes to insert after the expanding node.
   *
   * @param artifacts the run's artifact store, already containing the node's outputs
   * @return nodes whose dependencies refer to each other or to existing nodes
   */
  List<NodeSpec> expand(ArtifactStore artifacts);
}
