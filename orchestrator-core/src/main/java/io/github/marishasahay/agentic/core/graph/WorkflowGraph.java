package io.github.marishasahay.agentic.core.graph;

import io.github.marishasahay.agentic.core.util.Hashing;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Mutable dependency graph of {@link NodeSpec}s. Every mutation is validated, so the graph is a
 * DAG with resolvable dependencies at all times.
 *
 * <p>Only the engine's scheduler thread mutates a running graph, so the class is not
 * thread-safe by design.
 */
public final class WorkflowGraph {

  private final Map<String, NodeSpec> nodes = new LinkedHashMap<>();

  private WorkflowGraph() {}

  /**
   * Creates and validates a graph.
   *
   * @param specs node definitions
   * @return the validated graph
   */
  public static WorkflowGraph of(Collection<NodeSpec> specs) {
    WorkflowGraph graph = new WorkflowGraph();
    Map<String, NodeSpec> candidate = new LinkedHashMap<>();
    for (NodeSpec spec : specs) {
      if (candidate.putIfAbsent(spec.id(), spec) != null) {
        throw new GraphValidationException("Duplicate node id: " + spec.id());
      }
    }
    validate(candidate);
    graph.nodes.putAll(candidate);
    return graph;
  }

  public NodeSpec node(String id) {
    NodeSpec spec = nodes.get(id);
    if (spec == null) {
      throw new IllegalArgumentException("Unknown node: " + id);
    }
    return spec;
  }

  public boolean contains(String id) {
    return nodes.containsKey(id);
  }

  public Collection<NodeSpec> nodes() {
    return List.copyOf(nodes.values());
  }

  public int size() {
    return nodes.size();
  }

  /**
   * Inserts the nodes produced by {@code parentId}'s expansion between the parent and its
   * dependents: each spawned node depends on the parent, and every existing dependent of the
   * parent additionally waits for all spawned nodes. This is how a static template ("plan, then
   * integrate") accommodates a task list that is only known at run time.
   *
   * @param parentId the expanding node
   * @param spawned nodes to insert; their dependencies may reference each other
   */
  public void insertAfter(String parentId, List<NodeSpec> spawned) {
    node(parentId);
    if (spawned.isEmpty()) {
      return;
    }
    Map<String, NodeSpec> candidate = new LinkedHashMap<>(nodes);
    List<String> spawnedIds = new ArrayList<>();
    for (NodeSpec spec : spawned) {
      List<String> deps = new ArrayList<>(spec.dependsOn());
      deps.add(parentId);
      NodeSpec placed = spec.withDependsOn(deps).withOrigin(parentId);
      if (candidate.putIfAbsent(placed.id(), placed) != null) {
        throw new GraphValidationException("Expansion of " + parentId + " reuses id " + spec.id());
      }
      spawnedIds.add(placed.id());
    }
    for (NodeSpec dependent : dependentsIn(candidate, parentId)) {
      if (spawnedIds.contains(dependent.id())) {
        continue;
      }
      List<String> deps = new ArrayList<>(dependent.dependsOn());
      deps.addAll(spawnedIds);
      candidate.put(dependent.id(), dependent.withDependsOn(deps));
    }
    validate(candidate);
    nodes.clear();
    nodes.putAll(candidate);
  }

  /**
   * Removes every node spawned (directly or transitively) by {@code parentId} and unhooks them
   * from the remaining nodes' dependency lists. Used when the parent is re-planned.
   *
   * @param parentId the expanding node
   * @return ids of the removed nodes
   */
  public Set<String> removeSpawnedBy(String parentId) {
    Set<String> removed = new LinkedHashSet<>();
    Deque<String> queue = new ArrayDeque<>(List.of(parentId));
    while (!queue.isEmpty()) {
      String current = queue.pop();
      for (NodeSpec spec : nodes.values()) {
        if (current.equals(spec.origin()) && removed.add(spec.id())) {
          queue.push(spec.id());
        }
      }
    }
    removed.forEach(nodes::remove);
    for (NodeSpec spec : List.copyOf(nodes.values())) {
      if (spec.dependsOn().stream().anyMatch(removed::contains)) {
        List<String> deps = spec.dependsOn().stream().filter(d -> !removed.contains(d)).toList();
        nodes.put(spec.id(), spec.withDependsOn(deps));
      }
    }
    return removed;
  }

  /**
   * Direct dependents of a node.
   *
   * @param id node id
   * @return nodes that list {@code id} as a dependency
   */
  public List<NodeSpec> dependentsOf(String id) {
    return dependentsIn(nodes, id);
  }

  /**
   * All nodes reachable downstream of {@code id}, excluding {@code id} itself.
   *
   * @param id node id
   * @return transitive dependents in discovery order
   */
  public Set<String> transitiveDependentsOf(String id) {
    Set<String> result = new LinkedHashSet<>();
    Deque<String> queue = new ArrayDeque<>(List.of(id));
    while (!queue.isEmpty()) {
      for (NodeSpec dependent : dependentsOf(queue.pop())) {
        if (result.add(dependent.id())) {
          queue.add(dependent.id());
        }
      }
    }
    return result;
  }

  /**
   * Deterministic topological order (Kahn's algorithm, ties broken by insertion order).
   *
   * @return node ids, dependencies first
   */
  public List<String> topologicalOrder() {
    return kahn(nodes);
  }

  /**
   * Identity of the workflow definition: nodes, stages, dependencies, approvals, retries,
   * strategies and gates. A run may only be resumed with the workflow it started with.
   *
   * @return SHA-256 of a canonical description
   */
  public String fingerprint() {
    StringBuilder canonical = new StringBuilder();
    for (String id : topologicalOrder()) {
      NodeSpec n = nodes.get(id);
      canonical.append(id).append('|').append(n.stage()).append('|').append(n.dependsOn())
          .append('|').append(n.approval()).append('|').append(n.retry().maxAttempts())
          .append('|').append(n.fallback() != null).append('|').append(n.expansion() != null)
          .append('|').append(n.entryGates().stream().map(g -> g.name()).toList())
          .append('|').append(n.exitGates().stream().map(g -> g.name()).toList()).append('\n');
    }
    return Hashing.sha256(canonical.toString());
  }

  /**
   * Renders the graph as a Mermaid flowchart.
   *
   * @param cssClassOf maps a node id to a Mermaid class name (e.g. its state), or null
   * @return Mermaid source
   */
  public String toMermaid(Function<String, String> cssClassOf) {
    StringBuilder out = new StringBuilder("flowchart LR\n");
    for (String id : topologicalOrder()) {
      NodeSpec spec = nodes.get(id);
      out.append("  ").append(mermaidId(id)).append("[\"").append(id).append("<br/><small>")
          .append(spec.stage().name().toLowerCase().replace('_', ' ')).append("</small>\"]");
      String cssClass = cssClassOf == null ? null : cssClassOf.apply(id);
      if (cssClass != null) {
        out.append(":::").append(cssClass);
      }
      out.append('\n');
      for (String dep : spec.dependsOn()) {
        out.append("  ").append(mermaidId(dep)).append(" --> ").append(mermaidId(id)).append('\n');
      }
    }
    return out.toString();
  }

  private static String mermaidId(String id) {
    return id.replaceAll("[^A-Za-z0-9]", "_");
  }

  private static List<NodeSpec> dependentsIn(Map<String, NodeSpec> graph, String id) {
    return graph.values().stream().filter(n -> n.dependsOn().contains(id)).toList();
  }

  private static void validate(Map<String, NodeSpec> graph) {
    for (NodeSpec spec : graph.values()) {
      for (String dep : spec.dependsOn()) {
        if (dep.equals(spec.id())) {
          throw new GraphValidationException("Node depends on itself: " + spec.id());
        }
        if (!graph.containsKey(dep)) {
          throw new GraphValidationException(
              "Node " + spec.id() + " depends on unknown node " + dep);
        }
      }
    }
    kahn(graph);
  }

  private static List<String> kahn(Map<String, NodeSpec> graph) {
    Map<String, Integer> inDegree = new HashMap<>();
    graph.values().forEach(n -> inDegree.put(n.id(), n.dependsOn().size()));
    Deque<String> ready = new ArrayDeque<>();
    graph.keySet().stream().filter(id -> inDegree.get(id) == 0).forEach(ready::add);
    List<String> order = new ArrayList<>();
    while (!ready.isEmpty()) {
      String id = ready.poll();
      order.add(id);
      for (NodeSpec dependent : dependentsIn(graph, id)) {
        if (inDegree.merge(dependent.id(), -1, Integer::sum) == 0) {
          ready.add(dependent.id());
        }
      }
    }
    if (order.size() != graph.size()) {
      List<String> cyclic = graph.keySet().stream().filter(id -> !order.contains(id)).toList();
      throw new GraphValidationException("Dependency cycle among nodes: " + cyclic);
    }
    return order;
  }
}
