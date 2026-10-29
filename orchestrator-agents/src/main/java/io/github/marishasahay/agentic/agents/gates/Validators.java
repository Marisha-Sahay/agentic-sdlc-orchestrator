package io.github.marishasahay.agentic.agents.gates;

import io.github.marishasahay.agentic.agents.AgentSupport;
import io.github.marishasahay.agentic.agents.contract.RequirementSpec;
import io.github.marishasahay.agentic.agents.contract.SecurityReview;
import io.github.marishasahay.agentic.agents.contract.TaskPlan;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.graph.GraphValidationException;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.graph.WorkflowGraph;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Semantic validators for agent contracts, used with {@link ContractGate}. */
public final class Validators {

  private Validators() {}

  /**
   * Requirement quality: testable, identified, measurable.
   *
   * @param requireResolved whether open questions are forbidden (after clarification)
   * @return validator
   */
  public static java.util.function.Function<RequirementSpec, List<String>> requirements(
      boolean requireResolved) {
    return spec -> {
      List<String> problems = new ArrayList<>();
      List<RequirementSpec.FunctionalRequirement> frs =
          AgentSupport.list(spec.functionalRequirements());
      if (frs.isEmpty()) {
        problems.add("no functional requirements");
      }
      Set<String> ids = new HashSet<>();
      for (RequirementSpec.FunctionalRequirement fr : frs) {
        if (fr.id() == null || !ids.add(fr.id())) {
          problems.add("functional requirement id missing or duplicated: " + fr.id());
        }
        if (AgentSupport.list(fr.acceptanceCriteria()).isEmpty()) {
          problems.add(fr.id() + " has no acceptance criteria");
        }
      }
      for (RequirementSpec.NonFunctionalRequirement nfr :
          AgentSupport.list(spec.nonFunctionalRequirements())) {
        if (nfr.measure() == null || nfr.measure().isBlank()) {
          problems.add(nfr.id() + " has no measurable verification method");
        }
      }
      if (requireResolved && !AgentSupport.list(spec.ambiguities()).isEmpty()) {
        problems.add("refined spec still has " + spec.ambiguities().size()
            + " open question(s); resolve them using the stakeholder answers");
      }
      return problems;
    };
  }

  /**
   * Plan validity: bounded size, unique kebab ids, resolvable acyclic dependencies, files in
   * scope. Cycles are detected by building the same graph the engine would build.
   *
   * @param maxTasks upper bound on tasks
   * @return validator
   */
  public static java.util.function.Function<TaskPlan, List<String>> plan(int maxTasks) {
    return plan -> {
      List<String> problems = new ArrayList<>();
      List<TaskPlan.PlannedTask> tasks = AgentSupport.list(plan.tasks());
      if (tasks.isEmpty() || tasks.size() > maxTasks) {
        problems.add("plan must have between 1 and " + maxTasks + " tasks, has " + tasks.size());
      }
      List<NodeSpec> probe = new ArrayList<>();
      for (TaskPlan.PlannedTask task : tasks) {
        if (task.id() == null || !task.id().matches("[a-z0-9][a-z0-9-]{1,40}")) {
          problems.add("task id must be short kebab-case: " + task.id());
          continue;
        }
        if (AgentSupport.list(task.files()).isEmpty()) {
          problems.add(task.id() + " lists no files");
        }
        AgentSupport.list(task.files()).stream()
            .filter(f -> !f.startsWith("src/main/") && !f.startsWith("api/")
                && !f.equals("pom.xml"))
            .forEach(f -> problems.add(task.id() + " touches " + f
                + " (implementation tasks may only change src/main/**, api/** or pom.xml)"));
        if (AgentSupport.list(task.acceptanceCriteria()).isEmpty()) {
          problems.add(task.id() + " has no acceptance criteria");
        }
        probe.add(NodeSpec.builder(task.id(), Stage.IMPLEMENTATION)
            .dependsOn(AgentSupport.list(task.dependsOn()))
            .task(ctx -> NodeResult.builder().build()).build());
      }
      try {
        WorkflowGraph.of(probe);
      } catch (GraphValidationException e) {
        problems.add("task dependencies are invalid: " + e.getMessage());
      }
      return problems;
    };
  }

  /**
   * Scanner HIGH findings block; reviewer findings are advisory.
   *
   * @return validator
   */
  public static java.util.function.Function<SecurityReview, List<String>> security() {
    return review -> AgentSupport.list(review.findings()).stream()
        .filter(f -> "scanner".equals(f.source()) && "HIGH".equalsIgnoreCase(f.severity()))
        .map(f -> "HIGH " + f.category() + " in " + f.location() + ": " + f.description())
        .toList();
  }
}
