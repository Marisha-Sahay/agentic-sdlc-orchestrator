package io.github.marishasahay.agentic.agents;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.agentic.agents.analysis.CodebaseIndex;
import io.github.marishasahay.agentic.agents.contract.CodeProposal;
import io.github.marishasahay.agentic.agents.contract.DesignDecision;
import io.github.marishasahay.agentic.agents.contract.DesignSpec;
import io.github.marishasahay.agentic.agents.contract.ImpactAnalysis;
import io.github.marishasahay.agentic.agents.contract.ReleaseNotes;
import io.github.marishasahay.agentic.agents.contract.RequirementSpec;
import io.github.marishasahay.agentic.agents.contract.SecurityReview;
import io.github.marishasahay.agentic.agents.contract.TaskPlan;
import io.github.marishasahay.agentic.agents.gates.ContractGate;
import io.github.marishasahay.agentic.agents.gates.DocsCoverageGate;
import io.github.marishasahay.agentic.agents.gates.Validators;
import io.github.marishasahay.agentic.core.engine.RunOutcome;
import io.github.marishasahay.agentic.core.engine.RunStatus;
import io.github.marishasahay.agentic.core.event.EventEnvelope;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.gate.Gate;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;
import io.github.marishasahay.agentic.core.workspace.ChangeSet;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentsTest {

  private static final String CONTRACT = """
      openapi: 3.1.0
      paths:
        /api/v1/links:
          post: {summary: Create a short link}
        /{code}:
          get: {summary: Redirect}
      """;
  private static final RequirementSpec SPEC = new RequirementSpec("Click analytics", "p",
      List.of("see clicks"), List.of(
          new RequirementSpec.FunctionalRequirement("FR-1", "record clicks", "MUST",
              List.of("Given a redirect, a click is stored")),
          new RequirementSpec.FunctionalRequirement("FR-2", "stats endpoint", "MUST",
              List.of("GET stats returns totals"))),
      List.of(new RequirementSpec.NonFunctionalRequirement("NFR-1", "performance",
          "redirect stays fast", "p99 < 50 ms")),
      List.of(new RequirementSpec.Ambiguity("Q-1", "Retention?", "storage growth",
          List.of("30 days", "90 days"), "90 days")),
      List.of("No raw IPs are stored"), List.of("geo"), List.of());
  private static final TaskPlan PLAN = new TaskPlan("by layer", List.of(
      new TaskPlan.PlannedTask("click-store", "Store clicks", "d", List.of("FR-1"),
          List.of("src/main/java/app/ClickStore.java"), List.of(), List.of("compiles"))));

  @TempDir
  Path tmp;

  private int harnesses;

  private AgentHarness harness() {
    return new AgentHarness(tmp.resolve("h" + harnesses++), Map.of("README.md", "# Service\n",
        "api/openapi.yaml", CONTRACT,
        "src/main/java/app/LinkService.java", "package app;\n\npublic class LinkService {}\n"));
  }

  private static <T extends RunEvent> List<T> events(AgentHarness h, Class<T> type) {
    return h.log.events().stream().map(EventEnvelope::event).filter(type::isInstance)
        .map(type::cast).toList();
  }

  @Test
  void requirementsAgentPublishesAGatedSpecAndRecordsAssumptionsAsDecisions() {
    AgentHarness h = harness().reply("requirements-analyst", AgentSupport.pretty(SPEC));

    RunOutcome outcome = h.run(new RequirementsAgent(h.llm(), ArtifactKeys.REQUIREMENTS, "ctx"),
        Map.of(ArtifactKeys.REQUIREMENT_RAW, "add analytics"),
        new ContractGate<>("quality", ArtifactKeys.REQUIREMENTS, RequirementSpec.class,
            Validators.requirements(false)));

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(h.artifacts.content(ArtifactKeys.REQUIREMENTS)).contains("FR-2");
    assertThat(events(h, RunEvent.DecisionRecorded.class)).singleElement()
        .satisfies(d -> assertThat(d.choice()).isEqualTo("No raw IPs are stored"))
        .satisfies(d -> assertThat(d.basedOn()).singleElement().asString()
            .startsWith("requirement.raw@v1"));
    assertThat(events(h, RunEvent.AgentCalled.class)).singleElement()
        .satisfies(c -> assertThat(c.inputTokens()).isEqualTo(100));
    assertThat(h.calls.getFirst().key()).isEqualTo("agent/e1/attempt-1/primary/requirements-analyst");
  }

  @Test
  void malformedModelOutputIsRetriedWithTheParseErrorAsFeedback() {
    AgentHarness h = harness().reply("requirements-analyst", "{\"functionalRequirements\": 7}",
        AgentSupport.pretty(SPEC));

    RunOutcome outcome = h.run(new RequirementsAgent(h.llm(), ArtifactKeys.REQUIREMENTS, "ctx"),
        Map.of(ArtifactKeys.REQUIREMENT_RAW, "add analytics"));

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(h.calls).hasSize(2);
    assertThat(h.calls.get(1).userPrompt()).contains("Previous attempts were rejected")
        .contains("RequirementSpec JSON contract");
  }

  @Test
  void clarificationAndRefinementTurnQuestionsIntoAResolvedSpec() {
    AgentHarness h = harness();
    RunOutcome clarified = h.run(new ClarificationTask(), Map.of(
        ArtifactKeys.REQUIREMENTS_DRAFT, AgentSupport.pretty(SPEC)));

    assertThat(clarified.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(h.artifacts.content(ArtifactKeys.CLARIFICATION_QUESTIONS))
        .contains("Q-1: Retention?").contains("Recommended default: **90 days**");
    assertThat(h.artifacts.content(ArtifactKeys.CLARIFICATION_ANSWERS)).isEqualTo(
        "Q-1: 90 days\n");

    RequirementSpec resolved = new RequirementSpec(SPEC.title(), SPEC.problemStatement(),
        SPEC.goals(), SPEC.functionalRequirements(), SPEC.nonFunctionalRequirements(), List.of(),
        List.of("Q-1: 30 days"), SPEC.outOfScope(), SPEC.risks());
    AgentHarness refine = new AgentHarness(tmp.resolve("r"), Map.of("README.md", "x"))
        .reply("requirements-refiner", AgentSupport.pretty(resolved));
    RunOutcome refined = refine.run(new RefineRequirementsAgent(refine.llm()), Map.of(
        ArtifactKeys.REQUIREMENT_RAW, "make it fast", ArtifactKeys.REQUIREMENTS_DRAFT,
        AgentSupport.pretty(SPEC), ArtifactKeys.CLARIFICATION_ANSWERS, "Q-1: 30 days"),
        new ContractGate<>("resolved", ArtifactKeys.REQUIREMENTS, RequirementSpec.class,
            Validators.requirements(true)));

    assertThat(refined.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(refine.calls.getFirst().userPrompt()).contains("Q-1: 30 days");
  }

  @Test
  void codebaseScanAndImpactAnalysisAreGroundedInTheWorkspace() {
    AgentHarness h = harness();

    RunOutcome scanned = h.run(new CodebaseScanTask(), Map.of());
    CodebaseIndex index = AgentSupport.parse(h.artifacts.content(ArtifactKeys.CODEBASE_INDEX),
        CodebaseIndex.class);

    assertThat(scanned.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(index.contains("src/main/java/app/LinkService.java")).isTrue();

    AgentHarness impact = new AgentHarness(tmp.resolve("i"), Map.of("README.md", "x"))
        .reply("impact-analyst", AgentSupport.pretty(new ImpactAnalysis("adds analytics",
            List.of(new ImpactAnalysis.ImpactedComponent("src/main/java/app/LinkService.java",
                "service", "MODIFY", "publish click")),
            List.of(), List.of(), List.of(), List.of(), "IT")));
    RunOutcome analysed = impact.run(new ImpactAnalysisAgent(impact.llm()), Map.of(
        ArtifactKeys.REQUIREMENTS, AgentSupport.pretty(SPEC),
        ArtifactKeys.CODEBASE_INDEX, AgentSupport.pretty(index)),
        new io.github.marishasahay.agentic.agents.gates.ImpactGroundingGate());

    assertThat(analysed.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(impact.calls.getFirst().userPrompt()).contains("### Endpoints");
  }

  @Test
  void architectWritesTheContractToTheWorkspaceAndRendersDecisions() {
    DesignSpec design = new DesignSpec("Async click recording", List.of(
        new DesignSpec.Component("ClickRecorder", "buffer clicks", List.of("ClickRecorder.java"))),
        CONTRACT + "  /api/v1/links/{code}/stats:\n    get: {summary: Stats}\n",
        "clicks(link_id, at)", List.of(new DesignDecision("Recording", "bounded async queue",
            "keeps redirects fast", List.of("synchronous insert"))), List.of("drops under load"));
    AgentHarness h = harness().reply("architect", AgentSupport.pretty(design));

    RunOutcome outcome = h.run(new ArchitectAgent(h.llm(), "conventions"), Map.of(
        ArtifactKeys.REQUIREMENTS, AgentSupport.pretty(SPEC)));

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(h.file(ArchitectAgent.CONTRACT_PATH)).contains("/api/v1/links/{code}/stats");
    assertThat(h.artifacts.content(ArtifactKeys.DESIGN_DOC)).contains("| ClickRecorder |")
        .contains("**Decision:** bounded async queue").contains("- drops under load");
    assertThat(h.calls.getFirst().userPrompt()).contains("must stay backward compatible");
    assertThat(events(h, RunEvent.CheckpointCreated.class)).hasSize(1);
  }

  @Test
  void plannerAndCoderTurnTheDesignIntoCommittedCode() {
    AgentHarness h = harness().reply("planner", AgentSupport.pretty(PLAN));
    RunOutcome planned = h.run(new PlannerAgent(h.llm(), 8), Map.of(
        ArtifactKeys.REQUIREMENTS, "spec", ArtifactKeys.DESIGN_DOC, "# Design"));

    assertThat(planned.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(h.calls.getFirst().systemPrompt()).contains("At most 8 tasks");

    AgentHarness coder = harness().reply("coder", AgentSupport.pretty(new CodeProposal(
        "added ClickStore", List.of(new CodeProposal.ProposedFile(
            "src/main/java/app/ClickStore.java", "package app;\n\nclass ClickStore {}\n")),
        List.of(new DesignDecision("Storage", "JdbcClient", "consistent", List.of("JPA"))))));
    RunOutcome coded = coder.run(new CoderAgent(coder.llm(), "click-store", "conventions"),
        Map.of(ArtifactKeys.PLAN, AgentSupport.pretty(PLAN), ArtifactKeys.DESIGN_DOC, "# D",
            ArtifactKeys.OPENAPI, CONTRACT));

    assertThat(coded.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(coder.file("src/main/java/app/ClickStore.java")).contains("class ClickStore");
    assertThat(coder.artifacts.content(ArtifactKeys.taskResult("click-store")))
        .contains("ClickStore.java");
    assertThat(coder.calls.getFirst().userPrompt()).contains("=== src/main/java/app/LinkService");
  }

  @Test
  void downstreamStagesWorkFromTheChangeSetSinceBaseline() {
    AgentHarness h = harness();
    h.workspace.apply(ChangeSet.of(FileChange.write("src/main/java/app/Clicks.java",
        "package app;\n\nclass Clicks {\n  String q = \"SELECT * FROM clicks WHERE c = '\" + c;\n}\n")));
    h.workspace.checkpoint("impl");
    h.reply("test-author", AgentSupport.pretty(new CodeProposal("tests", List.of(
            new CodeProposal.ProposedFile("src/test/java/app/ClicksTest.java", "class T {}")),
            List.of())))
        .reply("tech-writer", AgentSupport.pretty(new CodeProposal("docs", List.of(
            new CodeProposal.ProposedFile("README.md", "POST /api/v1/links\nGET /{code}\n")),
            List.of())))
        .reply("security-reviewer", AgentSupport.pretty(new SecurityReview("one issue",
            List.of(new SecurityReview.Finding("LOW", "model", "privacy", "Clicks", "d", "r")))));

    RunOutcome integrated = h.run(new IntegrationTask(), Map.of(ArtifactKeys.PLAN, "{}"));
    String changed = h.artifacts.content(ArtifactKeys.CHANGED_FILES);
    Map<String, String> inputs = Map.of(ArtifactKeys.CHANGED_FILES, changed,
        ArtifactKeys.REQUIREMENTS, "spec", ArtifactKeys.PLAN, AgentSupport.pretty(PLAN),
        ArtifactKeys.DESIGN_DOC, "# D", ArtifactKeys.OPENAPI, CONTRACT);

    assertThat(integrated.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(changed).isEqualTo("src/main/java/app/Clicks.java");
    assertThat(h.run(new TestAuthorAgent(h.llm(), "c"), inputs).status())
        .isEqualTo(RunStatus.SUCCEEDED);
    assertThat(h.run(new DocsAgent(h.llm()), inputs, new DocsCoverageGate()).status())
        .isEqualTo(RunStatus.SUCCEEDED);
    RunOutcome security = h.run(new SecurityReviewAgent(h.llm()), inputs, new ContractGate<>(
        "security-gate", ArtifactKeys.SECURITY, SecurityReview.class, Validators.security()));

    assertThat(security.status()).isEqualTo(RunStatus.FAILED);
    assertThat(h.artifacts.latest(ArtifactKeys.SECURITY)).isEmpty();
    assertThat(events(h, RunEvent.GateEvaluated.class)).filteredOn(g -> !g.passed())
        .allSatisfy(g -> assertThat(g.evidence()).contains("HIGH injection in "
            + "src/main/java/app/Clicks.java"));
  }

  @Test
  void templateFallbackDocumentsEveryContractPathIdempotently() {
    AgentHarness h = harness();

    h.run(new TemplateDocsWriter(), Map.of(ArtifactKeys.OPENAPI, CONTRACT),
        new DocsCoverageGate());
    String once = h.file("README.md");
    h.run(new TemplateDocsWriter(), Map.of(ArtifactKeys.OPENAPI, CONTRACT));

    assertThat(once).contains("| POST | `/api/v1/links` | Create a short link |")
        .contains("`/{code}`");
    assertThat(h.file("README.md")).isEqualTo(once);
  }

  @Test
  void verifyRunsTheBuildFirstAndOnlyAsksTheModelToRepairAfterAFailure() {
    AtomicInteger builds = new AtomicInteger();
    Gate flakyBuild = new Gate() {
      @Override
      public String name() {
        return "build";
      }

      @Override
      public GateResult evaluate(GateContext context) {
        return builds.incrementAndGet() == 1
            ? GateResult.fail("build", "LinkServiceTest > shouldExpire FAILED")
            : GateResult.pass("build", "ok");
      }
    };
    AgentHarness h = harness().reply("build-fixer", AgentSupport.pretty(new CodeProposal(
        "fixed expiry check", List.of(new CodeProposal.ProposedFile(
            "src/main/java/app/LinkService.java", "package app;\n\npublic class LinkService {\n}"
                + "\n")), List.of())));

    RunOutcome outcome = h.run(new VerifyAndRepairAgent(h.llm(), "conventions"), Map.of(
        ArtifactKeys.CHANGED_FILES, "src/main/java/app/LinkService.java"), flakyBuild);

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(h.calls).singleElement().satisfies(c -> assertThat(c.userPrompt())
        .contains("shouldExpire FAILED"));
    assertThat(h.artifacts.content(ArtifactKeys.VERIFICATION)).contains("automated repair");
  }

  @Test
  void releaseAgentPackagesPatchTraceabilityAndNotes() {
    AgentHarness h = harness().reply("release-manager", AgentSupport.pretty(new ReleaseNotes(
        "Click analytics", "ships stats", List.of("stats endpoint"), List.of("table growth"),
        "revert commit; V2 is additive", "unit + IT", List.of("dashboards"))));
    h.workspace.apply(ChangeSet.of(FileChange.write("src/main/java/app/ClickStore.java", "x")));
    h.workspace.checkpoint("impl");

    RunOutcome outcome = h.run(new ReleaseAgent(h.llm()), Map.of(
        ArtifactKeys.REQUIREMENTS, AgentSupport.pretty(SPEC), ArtifactKeys.PLAN,
        AgentSupport.pretty(PLAN), ArtifactKeys.CHANGED_FILES, "src/main/java/app/ClickStore.java",
        ArtifactKeys.SECURITY, "{}", ArtifactKeys.VERIFICATION, "green",
        ArtifactKeys.TEST_REPORT, "tests", ArtifactKeys.DOCS_SUMMARY, "docs"));

    assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(h.artifacts.content(ArtifactKeys.RELEASE_PATCH)).contains("+x");
    assertThat(h.artifacts.content(ArtifactKeys.TRACEABILITY)).contains("FR-2: stats endpoint")
        .contains("**untraced**");
    assertThat(h.artifacts.content(ArtifactKeys.RELEASE_NOTES)).contains("## Rollback plan")
        .contains("V2 is additive");
  }
}
