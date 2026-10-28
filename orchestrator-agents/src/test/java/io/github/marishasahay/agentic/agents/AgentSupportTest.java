package io.github.marishasahay.agentic.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marishasahay.agentic.agents.contract.RequirementSpec;
import io.github.marishasahay.agentic.agents.contract.TaskPlan;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentSupportTest {

  @Test
  void parsesContractsAndToleratesMarkdownFences() {
    String fenced = """
        ```json
        {"rationale":"split by layer","tasks":[{"id":"repo","title":"Repo","files":["a"]}]}
        ```""";

    TaskPlan plan = AgentSupport.parse(fenced, TaskPlan.class);

    assertThat(plan.tasks()).singleElement().extracting(TaskPlan.PlannedTask::id)
        .isEqualTo("repo");
    assertThat(AgentSupport.list(plan.tasks().getFirst().dependsOn())).isEmpty();
  }

  @Test
  void rejectsEmptyOrMalformedRepliesWithAnExplanation() {
    assertThatThrownBy(() -> AgentSupport.parse(" ", TaskPlan.class))
        .hasMessageContaining("empty reply");
    assertThatThrownBy(() -> AgentSupport.parse("{\"tasks\": 3}", TaskPlan.class))
        .hasMessageContaining("TaskPlan JSON contract");
  }

  @Test
  void feedbackSectionCarriesRejectionEvidenceAndIsBounded() {
    assertThat(AgentSupport.feedbackSection(List.of())).isEmpty();
    String feedback = AgentSupport.feedbackSection(List.of("Attempt 1 was rejected: "
        + "LinkService.java:[42] cannot find symbol", "x".repeat(20_000)));

    assertThat(feedback).contains("Previous attempts were rejected").contains("truncated");
    assertThat(feedback.length()).isLessThan(9_000);
  }

  @Test
  void clarificationSheetPrefillsRecommendedDefaults() {
    RequirementSpec draft = new RequirementSpec("Stats", "p", List.of(), List.of(), List.of(),
        List.of(new RequirementSpec.Ambiguity("Q-1", "Peak traffic?", "sizing",
            List.of("100 rps", "2000 rps"), "500 rps")), List.of(), List.of(), List.of());

    String sheet = AgentSupport.pretty(draft);

    assertThat(AgentSupport.parse(sheet, RequirementSpec.class)).isEqualTo(draft);
  }

  @Test
  void traceabilityFlagsRequirementsNoTaskImplements() {
    RequirementSpec spec = new RequirementSpec("t", "p", List.of(), List.of(
        new RequirementSpec.FunctionalRequirement("FR-1", "stats endpoint", "MUST", List.of("x")),
        new RequirementSpec.FunctionalRequirement("FR-2", "export", "COULD", List.of("y"))),
        List.of(), List.of(), List.of(), List.of(), List.of());
    TaskPlan plan = new TaskPlan("r", List.of(new TaskPlan.PlannedTask("stats-api", "Stats", "d",
        List.of("FR-1"), List.of("src/main/java/Stats.java"), List.of(), List.of("ok"))));

    String matrix = ReleaseAgent.traceability(spec, plan, "src/main/java/Stats.java\n");

    assertThat(matrix).contains("| FR-1: stats endpoint | MUST | stats-api | "
        + "src/main/java/Stats.java |").contains("FR-2: export | COULD | **untraced**");
  }
}
