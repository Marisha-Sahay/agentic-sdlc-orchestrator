package io.github.marishasahay.agentic.app;

import io.github.marishasahay.agentic.agents.AgentSupport;
import io.github.marishasahay.agentic.agents.ArchitectAgent;
import io.github.marishasahay.agentic.agents.ArtifactKeys;
import io.github.marishasahay.agentic.agents.ClarificationTask;
import io.github.marishasahay.agentic.agents.CodebaseScanTask;
import io.github.marishasahay.agentic.agents.CoderAgent;
import io.github.marishasahay.agentic.agents.DocsAgent;
import io.github.marishasahay.agentic.agents.FinalValidationTask;
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
import io.github.marishasahay.agentic.agents.contract.ValidationEvidence;
import io.github.marishasahay.agentic.agents.gates.ApiCompatibilityGate;
import io.github.marishasahay.agentic.agents.gates.ContractGate;
import io.github.marishasahay.agentic.agents.gates.DocsCoverageGate;
import io.github.marishasahay.agentic.agents.gates.ImpactGroundingGate;
import io.github.marishasahay.agentic.agents.gates.SecurityScanGate;
import io.github.marishasahay.agentic.agents.gates.ValidatedTreeGate;
import io.github.marishasahay.agentic.agents.gates.Validators;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.gate.CommandGate;
import io.github.marishasahay.agentic.core.gate.CommandSandbox;
import io.github.marishasahay.agentic.core.gate.RequiredArtifactsGate;
import io.github.marishasahay.agentic.core.graph.ApprovalMode;
import io.github.marishasahay.agentic.core.graph.GraphExpansion;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.RetryPolicy;
import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.graph.WorkflowGraph;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SDLC workflow as a graph. One template covers all three scenarios; the scenario kind only
 * adds stages (codebase analysis and impact for brownfield, clarification for ambiguous):
 *
 * <pre>
 * intake -+-> requirements --------------------------+
 *         |   (ambiguous: draft -> clarify* -> refine)|
 *         +-> codebase-scan (brownfield/ambiguous) ---+-> impact-analysis -> design* -> plan
 * plan ==expands==> impl-&lt;task&gt;... -> integrate -+-> tests -> verify -> security-review -+
 *                                               +-> docs ------------------------------+
 *                                                    -> final-validation -> release*
 * (* = human approval checkpoint)
 * </pre>
 *
 * <p>Order matters for evidence: {@code verify} is the last stage that may change code (its
 * bounded repair), the security review reads the repaired code, documentation may only change
 * documentation (stage write scopes), and {@code final-validation} re-runs every deterministic
 * check on the one tree that release readiness is then bound to.
 */
public final class WorkflowTemplates {

  static final int MAX_TASKS = 8;
  /** Files that may change after a review without invalidating it. */
  static final List<String> DOCUMENTATION = List.of("README.md", "CHANGELOG.md", "API.md",
      "docs/**");
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
    nodes.add(NodeSpec.builder("verify", Stage.VERIFICATION)
        .description("Full build: tests, integration tests, Checkstyle, coverage (self-repair)")
        .dependsOn("tests")
        .retry(new RetryPolicy(3, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(1)))
        .timeout(Duration.ofMinutes(20))
        .task(new VerifyAndRepairAgent(primary, conventions, build.profile()))
        .exitGate(build.fullVerify())
        .build());
    nodes.add(NodeSpec.builder("security-review", Stage.SECURITY_REVIEW)
        .description("Deterministic scanner plus model review of the final code")
        .dependsOn("verify").retry(AGENT_RETRY)
        .task(new SecurityReviewAgent(primary))
        .exitGate(new ContractGate<>("security-gate", ArtifactKeys.SECURITY,
            SecurityReview.class, Validators.security()))
        .build());
    nodes.add(NodeSpec.builder("docs", Stage.DOCUMENTATION)
        .description("README and changelog")
        .dependsOn("integrate").retry(RetryPolicy.attempts(2))
        .task(new DocsAgent(primary))
        .fallback(new TemplateDocsWriter())
        .exitGate(new DocsCoverageGate())
        .build());

    CommandGate finalBuild = build.fullVerify();
    nodes.add(NodeSpec.builder("final-validation", Stage.VERIFICATION)
        .description("Every deterministic check, once, on the exact tree to be released")
        .dependsOn("security-review", "docs").retry(RetryPolicy.none())
        .timeout(Duration.ofMinutes(20))
        .task(new FinalValidationTask(build.dockerAvailable() ? "FULL" : "DEGRADED",
            List.of(finalBuild.name(), "security-scan", "final-api-compatibility",
                "final-docs-coverage"), build.skippedChecks(), build.sandbox().describe(),
            Map.of("security-review", DOCUMENTATION)))
        .exitGate(new ContractGate<>("evidence-freshness", ArtifactKeys.VALIDATION,
            ValidationEvidence.class, ValidationEvidence::staleEvidence))
        .exitGate(finalBuild)
        .exitGate(new SecurityScanGate())
        .exitGate(new ApiCompatibilityGate(baselineContract,
            ApiCompatibilityGate.Source.WORKSPACE_FILE))
        .exitGate(new DocsCoverageGate(true))
        .build());

    nodes.add(NodeSpec.builder("release", Stage.RELEASE_READINESS)
        .description("Patch, traceability, release notes; human sign-off on the validated tree")
        .dependsOn("final-validation").retry(AGENT_RETRY)
        .approval(ApprovalMode.ALWAYS)
        .entryGate(new ValidatedTreeGate())
        .task(new ReleaseAgent(primary, "final-validation"))
        .exitGate(RequiredArtifactsGate.of(ArtifactKeys.RELEASE_PATCH,
            ArtifactKeys.RELEASE_NOTES, ArtifactKeys.TRACEABILITY))
        .build());
    return WorkflowGraph.of(nodes);
  }

  private GraphExpansion implementationTasks() {
    return artifacts -> {
      TaskPlan plan = AgentSupport.parse(artifacts.content(ArtifactKeys.PLAN), TaskPlan.class);
      Map<String, List<String>> dependencies = serializeOverlaps(AgentSupport.list(plan.tasks()));
      return AgentSupport.list(plan.tasks()).stream()
          .map(task -> NodeSpec.builder("impl-" + task.id(), Stage.IMPLEMENTATION)
              .description(task.title())
              .dependsOn(dependencies.get(task.id()).stream().map(d -> "impl-" + d).toList())
              .retry(AGENT_RETRY).timeout(Duration.ofMinutes(10))
              .task(new CoderAgent(primary, task.id(), conventions))
              .fallback(new CoderAgent(fallback, task.id(), conventions))
              .exitGate(build.compile())
              .build())
          .toList();
    };
  }

  /**
   * Tasks the planner left independent but that write the same file are serialised (earlier in
   * plan order first), so they never race; disjoint tasks stay parallel. Stale proposals are
   * still caught at apply time; this just avoids producing them.
   *
   * @param tasks tasks in plan order (the plan gate guarantees declared dependencies are acyclic)
   * @return task id to its dependencies, declared plus serialising ones
   */
  static Map<String, List<String>> serializeOverlaps(List<TaskPlan.PlannedTask> tasks) {
    Map<String, List<String>> deps = new LinkedHashMap<>();
    for (TaskPlan.PlannedTask task : tasks) {
      deps.put(task.id(), new ArrayList<>(AgentSupport.list(task.dependsOn())));
    }
    for (int j = 0; j < tasks.size(); j++) {
      for (int i = 0; i < j; i++) {
        TaskPlan.PlannedTask earlier = tasks.get(i);
        TaskPlan.PlannedTask later = tasks.get(j);
        Set<String> shared = new HashSet<>(AgentSupport.list(earlier.files()));
        shared.retainAll(AgentSupport.list(later.files()));
        if (!shared.isEmpty() && !reaches(deps, earlier.id(), later.id())
            && !reaches(deps, later.id(), earlier.id())) {
          deps.get(later.id()).add(earlier.id());
        }
      }
    }
    return deps;
  }

  /** Whether {@code from} (transitively) depends on {@code to}. */
  private static boolean reaches(Map<String, List<String>> deps, String from, String to) {
    for (String dep : deps.getOrDefault(from, List.of())) {
      if (dep.equals(to) || reaches(deps, dep, to)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Build commands for gates, and where they execute. Integration tests need a database: with
   * Docker available they run (Testcontainers on the host, or a sidecar inside the Docker
   * sandbox); without it the build degrades to unit tests, final validation records the
   * profile as DEGRADED and release requires an explicit waiver.
   *
   * @param dockerAvailable whether integration tests can run
   * @param sandbox where build and test commands execute
   */
  public record BuildCommands(boolean dockerAvailable, CommandSandbox sandbox) {

    public BuildCommands(boolean dockerAvailable) {
      this(dockerAvailable, CommandSandbox.host());
    }

    CommandGate compile() {
      return CommandGate.of("maven-compile", Duration.ofMinutes(5),
          "mvn", "-q", "-B", "-ntp", "compile").in(sandbox);
    }

    CommandGate unitTests() {
      return CommandGate.of("maven-unit-tests", Duration.ofMinutes(10),
          "mvn", "-q", "-B", "-ntp", "test").in(sandbox);
    }

    CommandGate fullVerify() {
      return (dockerAvailable
          ? CommandGate.of("maven-verify", Duration.ofMinutes(15), "mvn", "-q", "-B", "-ntp",
              "verify")
          : CommandGate.of("maven-verify-DEGRADED-no-integration-tests", Duration.ofMinutes(15),
              "mvn", "-q", "-B", "-ntp", "-DskipITs", "verify")).in(sandbox);
    }

    List<String> skippedChecks() {
      return dockerAvailable ? List.of()
          : List.of("integration tests (Docker unavailable)", "coverage gate (defined on unit + "
              + "integration coverage)");
    }

    public String profile() {
      return dockerAvailable ? "FULL: unit and integration tests, Checkstyle, coverage gate"
          : "DEGRADED: unit tests and Checkstyle only; integration tests and the coverage gate "
              + "were skipped (Docker unavailable)";
    }
  }
}
