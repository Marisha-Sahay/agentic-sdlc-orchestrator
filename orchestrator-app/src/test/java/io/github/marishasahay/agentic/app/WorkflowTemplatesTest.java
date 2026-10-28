package io.github.marishasahay.agentic.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.agentic.agents.AgentSupport;
import io.github.marishasahay.agentic.core.graph.ApprovalMode;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.WorkflowGraph;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowTemplatesTest {

  private static final Path SCENARIOS = Path.of("..", "scenarios").toAbsolutePath().normalize();
  private final WorkflowTemplates templates = new WorkflowTemplates(
      new AgentSupport(call -> {
        throw new AssertionError("no model calls when building graphs");
      }),
      new AgentSupport(call -> {
        throw new AssertionError("no model calls when building graphs");
      }),
      "conventions", "", new WorkflowTemplates.BuildCommands(false));

  private WorkflowGraph graph(String scenario) {
    return templates.build(Scenario.load(SCENARIOS.resolve(scenario + ".yaml")));
  }

  @Test
  void brownfieldAnalysesTheCodebaseInParallelWithRequirementsAndJoinsBeforeImpact() {
    WorkflowGraph graph = graph("brownfield");

    assertThat(graph.node("codebase-scan").dependsOn()).containsExactly("intake");
    assertThat(graph.node("requirements").dependsOn()).containsExactly("intake");
    assertThat(graph.node("impact-analysis").dependsOn())
        .containsExactlyInAnyOrder("requirements", "codebase-scan");
    assertThat(graph.node("design").dependsOn()).containsExactly("impact-analysis");
    assertThat(graph.node("verify").dependsOn())
        .containsExactlyInAnyOrder("tests", "docs", "security-review");
  }

  @Test
  void humanCheckpointsAreMandatoryAtDesignAndRelease() {
    WorkflowGraph graph = graph("greenfield");

    assertThat(graph.nodes()).filteredOn(n -> n.approval() == ApprovalMode.ALWAYS)
        .extracting(NodeSpec::id).containsExactlyInAnyOrder("design", "release");
    assertThat(graph.contains("codebase-scan")).isFalse();
    assertThat(graph.node("design").dependsOn()).containsExactly("requirements");
  }

  @Test
  void ambiguousRequestsAreClarifiedByAHumanBeforeDesign() {
    WorkflowGraph graph = graph("ambiguous");

    assertThat(graph.node("clarify").approval()).isEqualTo(ApprovalMode.ALWAYS);
    assertThat(graph.node("refine-requirements").dependsOn()).containsExactly("clarify");
    assertThat(graph.node("impact-analysis").dependsOn())
        .containsExactlyInAnyOrder("refine-requirements", "codebase-scan");
    assertThat(graph.topologicalOrder().indexOf("clarify"))
        .isLessThan(graph.topologicalOrder().indexOf("design"));
  }

  @Test
  void everyModelBackedStageHasBoundedRetriesAndCodeStagesHaveFallbacks() {
    for (String scenario : List.of("greenfield", "brownfield", "ambiguous")) {
      for (NodeSpec node : graph(scenario).nodes()) {
        assertThat(node.retry().maxAttempts()).as(node.id()).isBetween(1, 3);
      }
      WorkflowGraph graph = graph(scenario);
      assertThat(graph.node("design").fallback()).isNotNull();
      assertThat(graph.node("tests").fallback()).isNotNull();
      assertThat(graph.node("docs").fallback()).isNotNull();
    }
  }

  @Test
  void scenariosScriptReviewerDecisionsForUnattendedRuns() {
    Scenario brownfield = Scenario.load(SCENARIOS.resolve("brownfield.yaml"));
    Scenario ambiguous = Scenario.load(SCENARIOS.resolve("ambiguous.yaml"));

    assertThat(brownfield.scriptedApprovals().get("design")).first()
        .satisfies(d -> assertThat(d.approve()).isFalse());
    assertThat(ambiguous.scriptedApprovals().get("clarify").getFirst().amendments())
        .containsKey("clarification.answers");
    assertThat(brownfield.startsFromExistingCode()).isTrue();
  }
}
