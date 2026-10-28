package io.github.marishasahay.agentic.app;

import io.github.marishasahay.agentic.agents.AgentSupport;
import io.github.marishasahay.agentic.agents.ArchitectAgent;
import io.github.marishasahay.agentic.agents.ArtifactKeys;
import io.github.marishasahay.agentic.agents.ClarificationTask;
import io.github.marishasahay.agentic.agents.CodebaseScanTask;
import io.github.marishasahay.agentic.agents.CoderAgent;
import io.github.marishasahay.agentic.agents.DocsAgent;
import io.github.marishasahay.agentic.agents.ImpactAnalysisAgent;
import io.github.marishasahay.agentic.agents.IntegrationTask;
import io.github.marishasahay.agentic.agents.PlannerAgent;
import io.github.marishasahay.agentic.agents.RefineRequirementsAgent;
import io.github.marishasahay.agentic.agents.ReleaseAgent;
import io.github.marishasahay.agentic.agents.RequirementsAgent;
import io.github.marishasahay.agentic.agents.SecurityReviewAgent;
import io.github.marishasahay.agentic.agents.TemplateDocsWriter;
import io.github.marishasahay.agentic.agents.TestAuthorAgent;
import io.github.marishasahay.agentic.agents.VerifyAndRepairAgent;
import io.github.marishasahay.agentic.agents.contract.RequirementSpec;
import io.github.marishasahay.agentic.agents.contract.SecurityReview;
import io.github.marishasahay.agentic.agents.contract.TaskPlan;
import io.github.marishasahay.agentic.agents.gates.ApiCompatibilityGate;
import io.github.marishasahay.agentic.agents.gates.ContractGate;
import io.github.marishasahay.agentic.agents.gates.DocsCoverageGate;
import io.github.marishasahay.agentic.agents.gates.ImpactGroundingGate;
import io.github.marishasahay.agentic.agents.gates.Validators;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.gate.CommandGate;
import io.github.marishasahay.agentic.core.gate.RequiredArtifactsGate;
import io.github.marishasahay.agentic.core.graph.ApprovalMode;
import io.github.marishasahay.agentic.core.graph.GraphExpansion;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.RetryPolicy;
import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.graph.WorkflowGraph;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The SDLC workflow as a graph. One template covers all three scenarios; the scenario kind only
 * adds stages (codebase analysis and impact for brownfield, clarification for ambiguous):
 *
 * <pre>
 * intake -+-> requirements --------------------------+
 *         |   (ambiguous: draft -> clarify* -> refine)|
 *         +-> codebase-scan (brownfield/ambiguous) ---+-> impact-analysis -> design* -> plan
 * plan ==expands==> impl-&lt;task&gt;... -> integrate -+-> tests ----------+
 *                                               +-> docs -----------+-> verify -> release*
 *                                               +-> security-review +
 * (* = human approval checkpoint)
 * </pre>
 */
public final class WorkflowTemplates {

  static final int MAX_TASKS = 8;
  private static final RetryPolicy AGENT_RETRY = new RetryPolicy(3, Duration.ofSeconds(1), 2.0,
      Duration.ofSeconds(8));

  private final AgentSupport primary;
  private final AgentSupport fallback;
  private final String conventions;
  private final String baselineContract;
  private final BuildCommands build;

  /**
   * Creates the template factory.
   *
   * @param primary agents backed by the primary model
   * @param fallback agents backed by the fallback model (fallback strategies)
   * @param conventions coding standards for code-writing agents
   * @param baselineContract the baseline OpenAPI contract, empty for greenfield
   * @param build build commands used by gates
   */
  public WorkflowTemplates(AgentSupport primary, AgentSupport fallback, String conventions,
      String baselineContract, BuildCommands build) {
    this.primary = primary;
    this.fallback = fallback;
    this.conventions = conventions;
    this.baselineContract = baselineContract;
    this.build = build;
  }

  /**
   * Builds the graph for a scenario.
   *
   * @param scenario scenario
   * @return validated graph
   */
  public WorkflowGraph build(Scenario scenario) {
    List<NodeSpec> nodes = new ArrayList<>();
    boolean existing = scenario.startsFromExistingCode();
    boolean ambiguous = scenario.kind() == Scenario.Kind.AMBIGUOUS;
    String context = existing
        ? "Extend the existing URL shortener service (Spring Boot 4, PostgreSQL) in place."
        : "Greenfield: build a new Spring Boot 4 / Java 21 service on PostgreSQL.";

    nodes.add(NodeSpec.builder("intake", Stage.REQUIREMENTS)
        .description("Capture the stakeholder request verbatim")
        .retry(RetryPolicy.none())
        .task(ctx -> NodeResult.builder()
            .output(ArtifactKeys.REQUIREMENT_RAW, scenario.requirement())
            .summary("Captured request for scenario " + scenario.name()).build())
        .build());

    String requirementsNode = ambiguous ? "refine-requirements" : "requirements";
    nodes.add(NodeSpec.builder("requirements", Stage.REQUIREMENTS)
        .description("Normalise the request into testable requirements")
        .dependsOn("intake").retry(AGENT_RETRY)
        .task(new RequirementsAgent(primary,
            ambiguous ? ArtifactKeys.REQUIREMENTS_DRAFT : ArtifactKeys.REQUIREMENTS, context))
        .exitGate(new ContractGate<>("requirements-quality",
            ambiguous ? ArtifactKeys.REQUIREMENTS_DRAFT : ArtifactKeys.REQUIREMENTS,
            RequirementSpec.class, Validators.requirements(false)))
        .build());
    if (ambiguous) {
      nodes.add(NodeSpec.builder("clarify", Stage.CLARIFICATION)
          .description("Ask the stakeholder the open questions; reviewer answers at the gate")
          .dependsOn("requirements").retry(RetryPolicy.none())
          .approval(ApprovalMode.ALWAYS)
          .task(new ClarificationTask())
          .build());
      nodes.add(NodeSpec.builder("refine-requirements", Stage.REQUIREMENTS)
          .description("Fold the answers into an unambiguous specification")
          .dependsOn("clarify").retry(AGENT_RETRY)
          .task(new RefineRequirementsAgent(primary))
          .exitGate(new ContractGate<>("requirements-resolved", ArtifactKeys.REQUIREMENTS,
              RequirementSpec.class, Validators.requirements(true)))
          .build());
    }

    String designInput = requirementsNode;
    if (existing) {
      nodes.add(NodeSpec.builder("codebase-scan", Stage.CODEBASE_ANALYSIS)
          .description("Index modules, endpoints, migrations and wiring (JavaParser)")
          .dependsOn("intake").retry(RetryPolicy.none())
          .task(new CodebaseScanTask())
          .exitGate(RequiredArtifactsGate.of(ArtifactKeys.CODEBASE_INDEX))
          .build());
      nodes.add(NodeSpec.builder("impact-analysis", Stage.CODEBASE_ANALYSIS)
          .description("Identify impacted modules, APIs, data and flows")
          .dependsOn(requirementsNode, "codebase-scan").retry(AGENT_RETRY)
          .task(new ImpactAnalysisAgent(primary))
          .exitGate(new ImpactGroundingGate())
          .build());
      designInput = "impact-analysis";
    }

    nodes.add(NodeSpec.builder("design", Stage.DESIGN)
        .description("Architecture, data model, decisions and the OpenAPI contract")
        .dependsOn(designInput).retry(AGENT_RETRY)
        .approval(ApprovalMode.ALWAYS)
        .entryGate(RequiredArtifactsGate.of(ArtifactKeys.REQUIREMENTS))
        .task(new ArchitectAgent(primary, conventions))
        .fallback(new ArchitectAgent(fallback, conventions))
        .exitGate(RequiredArtifactsGate.of(ArtifactKeys.DESIGN_DOC, ArtifactKeys.OPENAPI))
        .exitGate(new ApiCompatibilityGate(baselineContract))
        .build());

    nodes.add(NodeSpec.builder("plan", Stage.PLANNING)
        .description("Decompose the design into dependent implementation tasks")
        .dependsOn("design").retry(AGENT_RETRY)
        .task(new PlannerAgent(primary, MAX_TASKS))
        .exitGate(new ContractGate<>("plan-validity", ArtifactKeys.PLAN, TaskPlan.class,
            Validators.plan(MAX_TASKS)))
        .expansion(implementationTasks())
        .build());

    nodes.add(NodeSpec.builder("integrate", Stage.IMPLEMENTATION)
        .description("Join all implementation tasks; record the change set")
        .dependsOn("plan").retry(RetryPolicy.none())
        .task(new IntegrationTask())
        .build());

    nodes.add(NodeSpec.builder("tests", Stage.TESTING)
        .description("Unit and integration tests for the change")
        .dependsOn("integrate").retry(AGENT_RETRY).timeout(Duration.ofMinutes(15))
        .task(new TestAuthorAgent(primary, conventions))
        .fallback(new TestAuthorAgent(fallback, conventions))
        .exitGate(build.unitTests())
        .build());
    nodes.add(NodeSpec.builder("docs", Stage.DOCUMENTATION)
        .description("README and changelog")
        .dependsOn("integrate").retry(RetryPolicy.attempts(2))
        .task(new DocsAgent(primary))
        .fallback(new TemplateDocsWriter())
        .exitGate(new DocsCoverageGate())
        .build());
    nodes.add(NodeSpec.builder("security-review", Stage.SECURITY_REVIEW)
        .description("Deterministic scanner plus model review of the diff")
        .dependsOn("integrate").retry(AGENT_RETRY)
        .task(new SecurityReviewAgent(primary))
        .exitGate(new ContractGate<>("security-gate", ArtifactKeys.SECURITY,
            SecurityReview.class, Validators.security()))
        .build());

    nodes.add(NodeSpec.builder("verify", Stage.VERIFICATION)
        .description("Full build: tests, integration tests, Checkstyle, coverage (self-repair)")
        .dependsOn("tests", "docs", "security-review")
        .retry(new RetryPolicy(3, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(1)))
        .timeout(Duration.ofMinutes(20))
        .task(new VerifyAndRepairAgent(primary, conventions))
        .exitGate(build.fullVerify())
        .build());

    nodes.add(NodeSpec.builder("release", Stage.RELEASE_READINESS)
        .description("Patch, traceability, release notes; human sign-off")
        .dependsOn("verify").retry(AGENT_RETRY)
        .approval(ApprovalMode.ALWAYS)
        .task(new ReleaseAgent(primary))
        .exitGate(RequiredArtifactsGate.of(ArtifactKeys.RELEASE_PATCH,
            ArtifactKeys.RELEASE_NOTES, ArtifactKeys.TRACEABILITY))
        .build());
    return WorkflowGraph.of(nodes);
  }

  private GraphExpansion implementationTasks() {
    return artifacts -> {
      TaskPlan plan = AgentSupport.parse(artifacts.content(ArtifactKeys.PLAN), TaskPlan.class);
      return AgentSupport.list(plan.tasks()).stream()
          .map(task -> NodeSpec.builder("impl-" + task.id(), Stage.IMPLEMENTATION)
              .description(task.title())
              .dependsOn(AgentSupport.list(task.dependsOn()).stream().map(d -> "impl-" + d)
                  .toList())
              .retry(AGENT_RETRY).timeout(Duration.ofMinutes(10))
              .task(new CoderAgent(primary, task.id(), conventions))
              .fallback(new CoderAgent(fallback, task.id(), conventions))
              .exitGate(build.compile())
              .build())
          .toList();
    };
  }

  /**
   * Build commands for gates. Integration tests need Docker; when it is absent the full
   * verification degrades to unit tests and the degradation is recorded in the audit log.
   *
   * @param dockerAvailable whether Testcontainers can run
   */
  public record BuildCommands(boolean dockerAvailable) {

    CommandGate compile() {
      return CommandGate.of("maven-compile", Duration.ofMinutes(5),
          "mvn", "-q", "-B", "-ntp", "compile");
    }

    CommandGate unitTests() {
      return CommandGate.of("maven-unit-tests", Duration.ofMinutes(10),
          "mvn", "-q", "-B", "-ntp", "test");
    }

    CommandGate fullVerify() {
      return dockerAvailable
          ? CommandGate.of("maven-verify", Duration.ofMinutes(15), "mvn", "-q", "-B", "-ntp",
              "verify")
          : CommandGate.of("maven-verify-no-its", Duration.ofMinutes(15), "mvn", "-q", "-B",
              "-ntp", "-DskipITs", "verify");
    }

    public String profile() {
      return dockerAvailable ? "full (unit + Testcontainers ITs)"
          : "DEGRADED: integration tests skipped (Docker unavailable)";
    }
  }
}
