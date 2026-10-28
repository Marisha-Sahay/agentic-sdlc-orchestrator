package io.github.marishasahay.agentic.agents.gates;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.agentic.agents.AgentSupport;
import io.github.marishasahay.agentic.agents.ArtifactKeys;
import io.github.marishasahay.agentic.agents.analysis.CodebaseIndex;
import io.github.marishasahay.agentic.agents.contract.ImpactAnalysis;
import io.github.marishasahay.agentic.agents.contract.RequirementSpec;
import io.github.marishasahay.agentic.agents.contract.SecurityReview;
import io.github.marishasahay.agentic.agents.contract.TaskPlan;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.Stage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatesTest {

  private static final NodeSpec NODE = NodeSpec.builder("node", Stage.DESIGN)
      .task(ctx -> NodeResult.builder().build()).build();

  private static final String BASELINE_CONTRACT = """
      openapi: 3.1.0
      paths:
        /api/v1/links:
          post: {summary: Create a short link}
        /{code}:
          get: {summary: Redirect}
      """;

  @TempDir
  Path workspace;

  private static GateContext context(Map<String, String> outputs, Path root) {
    return new GateContext(NODE, ArtifactStore.inMemory(), outputs, root);
  }

  private static RequirementSpec spec(List<RequirementSpec.FunctionalRequirement> frs,
      List<RequirementSpec.Ambiguity> questions) {
    return new RequirementSpec("t", "p", List.of(), frs, List.of(
        new RequirementSpec.NonFunctionalRequirement("NFR-1", "performance", "fast", "")),
        questions, List.of(), List.of(), List.of());
  }

  @Test
  void requirementGateDemandsTestableIdentifiedMeasurableRequirements() {
    RequirementSpec weak = spec(List.of(
        new RequirementSpec.FunctionalRequirement("FR-1", "shorten", "MUST", List.of()),
        new RequirementSpec.FunctionalRequirement("FR-1", "redirect", "MUST", List.of("302"))),
        List.of(new RequirementSpec.Ambiguity("Q-1", "how fast?", "sizing", List.of(), "x")));

    assertThat(Validators.requirements(false).apply(weak)).containsExactlyInAnyOrder(
        "FR-1 has no acceptance criteria", "functional requirement id missing or duplicated: FR-1",
        "NFR-1 has no measurable verification method");
    assertThat(Validators.requirements(true).apply(weak))
        .anySatisfy(p -> assertThat(p).contains("open question"));
  }

  @Test
  void planGateRejectsCyclesUnknownDependenciesAndOutOfScopeFiles() {
    TaskPlan cyclic = new TaskPlan("r", List.of(
        new TaskPlan.PlannedTask("repo", "Repo", "d", List.of("FR-1"),
            List.of("src/main/java/Repo.java"), List.of("service"), List.of("compiles")),
        new TaskPlan.PlannedTask("service", "Service", "d", List.of("FR-1"),
            List.of("src/test/java/ServiceTest.java"), List.of("repo"), List.of("ok"))));

    List<String> problems = Validators.plan(8).apply(cyclic);

    assertThat(problems).anySatisfy(p -> assertThat(p).contains("cycle"));
    assertThat(problems).anySatisfy(p -> assertThat(p).contains("may only change src/main"));
    assertThat(Validators.plan(1).apply(cyclic)).anySatisfy(p -> assertThat(p)
        .contains("between 1 and 1"));
  }

  @Test
  void contractGateTurnsParseErrorsAndViolationsIntoFeedback() {
    ContractGate<TaskPlan> gate = new ContractGate<>("plan-validity", ArtifactKeys.PLAN,
        TaskPlan.class, Validators.plan(8));

    assertThat(gate.evaluate(context(Map.of(ArtifactKeys.PLAN, "{not json"), null)).evidence())
        .contains("did not match the TaskPlan JSON contract");
    assertThat(gate.evaluate(context(Map.of(), null)).evidence()).contains("was not produced");
    String valid = AgentSupport.pretty(new TaskPlan("r", List.of(new TaskPlan.PlannedTask(
        "click-repo", "t", "d", List.of("FR-1"), List.of("src/main/java/A.java"), List.of(),
        List.of("ok")))));
    assertThat(gate.evaluate(context(Map.of(ArtifactKeys.PLAN, valid), null)).passed()).isTrue();
  }

  @Test
  void impactGroundingRejectsHallucinatedFiles() {
    CodebaseIndex index = new CodebaseIndex(List.of(new CodebaseIndex.SourceType(
        "src/main/java/LinkResolver.java", "LinkResolver", "class", "component", List.of(),
        List.of(), 40)), List.of(), List.of(), List.of(), List.of());
    ImpactAnalysis impact = new ImpactAnalysis("s", List.of(
        new ImpactAnalysis.ImpactedComponent("src/main/java/LinkResolver.java", "cache",
            "MODIFY", "expiry on hit"),
        new ImpactAnalysis.ImpactedComponent("src/main/java/ClickService.java", "service",
            "MODIFY", "invented"),
        new ImpactAnalysis.ImpactedComponent("src/main/java/LinkResolver.java", "cache", "ADD",
            "dup")), List.of(), List.of(), List.of(), List.of(), "t");

    GateResult result = new ImpactGroundingGate().evaluate(context(Map.of(
        ArtifactKeys.CODEBASE_INDEX, AgentSupport.pretty(index),
        ArtifactKeys.IMPACT, AgentSupport.pretty(impact)), null));

    assertThat(result.passed()).isFalse();
    assertThat(result.evidence()).contains("ClickService.java: no such file")
        .contains("already exists");
  }

  @Test
  void apiCompatibilityAllowsAdditionsAndRejectsRemovals() {
    String additive = BASELINE_CONTRACT + """
          /api/v1/links/{code}/stats:
            get: {summary: Click statistics}
        """;
    String breaking = """
        openapi: 3.1.0
        paths:
          /api/v1/links:
            post: {summary: Create}
        """;
    ApiCompatibilityGate gate = new ApiCompatibilityGate(BASELINE_CONTRACT);

    assertThat(gate.evaluate(context(Map.of(ArtifactKeys.OPENAPI, additive), null)).passed())
        .isTrue();
    assertThat(gate.evaluate(context(Map.of(ArtifactKeys.OPENAPI, breaking), null)).evidence())
        .contains("operations removed: [GET /{code}]");
    assertThat(gate.evaluate(context(Map.of(ArtifactKeys.OPENAPI, "swagger: 2.0"), null))
        .evidence()).contains("openapi: 3.x");
    assertThat(new ApiCompatibilityGate("").evaluate(context(Map.of(ArtifactKeys.OPENAPI,
        breaking), null)).passed()).isTrue();
  }

  @Test
  void docsGateRequiresEveryPathInTheReadme() throws Exception {
    Files.writeString(workspace.resolve("README.md"), "POST /api/v1/links creates a link");
    DocsCoverageGate gate = new DocsCoverageGate();

    GateResult result = gate.evaluate(context(Map.of(ArtifactKeys.OPENAPI, BASELINE_CONTRACT),
        workspace));

    assertThat(result.passed()).isFalse();
    assertThat(result.evidence()).contains("/{code}");
    assertThat(gate.usesWorkspace()).isTrue();
  }

  @Test
  void onlyScannerHighFindingsBlockSecurity() {
    SecurityReview review = new SecurityReview("s", List.of(
        new SecurityReview.Finding("HIGH", "reviewer", "ssrf", "A.java", "maybe", "check"),
        new SecurityReview.Finding("MEDIUM", "scanner", "cors", "B.java", "wildcard", "fix")));
    SecurityReview blocking = new SecurityReview("s", List.of(
        new SecurityReview.Finding("HIGH", "scanner", "injection", "C.java", "concat", "bind")));

    assertThat(Validators.security().apply(review)).isEmpty();
    assertThat(Validators.security().apply(blocking)).singleElement().asString()
        .contains("injection in C.java");
  }
}
