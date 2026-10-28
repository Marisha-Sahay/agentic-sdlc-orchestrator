package io.github.marishasahay.agentic.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marishasahay.agentic.core.engine.NodeResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowGraphTest {

  private static NodeSpec node(String id, String... deps) {
    return NodeSpec.builder(id, Stage.IMPLEMENTATION)
        .dependsOn(deps)
        .task(ctx -> NodeResult.builder().build())
        .build();
  }

  @Test
  void ordersNodesTopologicallyWithInsertionOrderTieBreak() {
    WorkflowGraph graph = WorkflowGraph.of(List.of(
        node("req"), node("design", "req"), node("docs", "design"), node("impl", "design"),
        node("release", "docs", "impl")));

    assertThat(graph.topologicalOrder()).containsExactly("req", "design", "docs", "impl",
        "release");
  }

  @Test
  void rejectsCyclesUnknownDependenciesAndDuplicates() {
    assertThatThrownBy(() -> WorkflowGraph.of(List.of(node("a", "b"), node("b", "a"))))
        .isInstanceOf(GraphValidationException.class).hasMessageContaining("cycle");
    assertThatThrownBy(() -> WorkflowGraph.of(List.of(node("a", "ghost"))))
        .isInstanceOf(GraphValidationException.class).hasMessageContaining("unknown node ghost");
    assertThatThrownBy(() -> WorkflowGraph.of(List.of(node("a"), node("a"))))
        .isInstanceOf(GraphValidationException.class).hasMessageContaining("Duplicate");
    assertThatThrownBy(() -> WorkflowGraph.of(List.of(node("a", "a"))))
        .isInstanceOf(GraphValidationException.class).hasMessageContaining("itself");
  }

  @Test
  void insertsExpansionBetweenParentAndItsDependents() {
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("plan"), node("integrate", "plan")));

    graph.insertAfter("plan", List.of(node("task-a"), node("task-b", "task-a")));

    assertThat(graph.node("task-a").dependsOn()).containsExactly("plan");
    assertThat(graph.node("task-b").dependsOn()).containsExactly("task-a", "plan");
    assertThat(graph.node("task-b").origin()).isEqualTo("plan");
    assertThat(graph.node("integrate").dependsOn()).containsExactly("plan", "task-a", "task-b");
    assertThat(graph.topologicalOrder()).containsExactly("plan", "task-a", "task-b", "integrate");
  }

  @Test
  void removesSpawnedNodesAndUnhooksThemWhenParentIsReplanned() {
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("plan"), node("integrate", "plan")));
    graph.insertAfter("plan", List.of(node("task-a")));

    assertThat(graph.removeSpawnedBy("plan")).containsExactly("task-a");
    assertThat(graph.contains("task-a")).isFalse();
    assertThat(graph.node("integrate").dependsOn()).containsExactly("plan");
  }

  @Test
  void rejectsExpansionThatWouldCreateACycle() {
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("plan"), node("integrate", "plan")));

    assertThatThrownBy(() -> graph.insertAfter("plan", List.of(node("task", "integrate"))))
        .isInstanceOf(GraphValidationException.class);
    assertThat(graph.contains("task")).isFalse();
  }

  @Test
  void computesTransitiveDependentsAndMermaid() {
    WorkflowGraph graph = WorkflowGraph.of(List.of(node("a"), node("b", "a"), node("c", "b")));

    assertThat(graph.transitiveDependentsOf("a")).containsExactly("b", "c");
    assertThat(graph.toMermaid(id -> id.equals("a") ? "succeeded" : null))
        .contains("a[\"a<br/>").contains(":::succeeded").contains("a --> b").contains("b --> c");
  }

  @Test
  void validatesNodeIdsAndRetryPolicy() {
    assertThatThrownBy(() -> node("Not Kebab")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetryPolicy.attempts(0)).isInstanceOf(IllegalArgumentException.class);
    assertThat(RetryPolicy.standard().backoffAfter(1).toMillis()).isEqualTo(200);
    assertThat(RetryPolicy.standard().backoffAfter(2).toMillis()).isEqualTo(400);
    assertThat(RetryPolicy.standard().backoffAfter(20).toSeconds()).isEqualTo(5);
  }
}
