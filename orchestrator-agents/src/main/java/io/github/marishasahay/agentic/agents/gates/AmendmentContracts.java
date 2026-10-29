package io.github.marishasahay.agentic.agents.gates;

import io.github.marishasahay.agentic.agents.AgentSupport;
import io.github.marishasahay.agentic.agents.ArtifactKeys;
import io.github.marishasahay.agentic.agents.contract.DesignSpec;
import io.github.marishasahay.agentic.agents.contract.RequirementSpec;
import io.github.marishasahay.agentic.agents.contract.TaskPlan;
import io.github.marishasahay.agentic.core.approval.AmendmentValidator;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Reviewer amendments must satisfy the same contract as the artifact they replace: a spec must
 * still be a testable spec, a plan a valid plan, a contract a backward-compatible contract, and
 * clarification answers must answer every open question. Free-text artifacts are not checked
 * beyond policy (secrets).
 *
 * @param artifacts the run's artifacts (to know which questions were asked)
 * @param baselineContract baseline API contract, empty for greenfield
 * @param requireResolvedRequirements whether a final spec may still contain open questions
 * @param maxTasks plan size limit
 */
public record AmendmentContracts(ArtifactStore artifacts, String baselineContract,
    boolean requireResolvedRequirements, int maxTasks) implements AmendmentValidator {

  @Override
  public List<String> validate(String key, String content) {
    return switch (key) {
      case ArtifactKeys.REQUIREMENTS -> check(content, RequirementSpec.class,
          Validators.requirements(requireResolvedRequirements));
      case ArtifactKeys.REQUIREMENTS_DRAFT -> check(content, RequirementSpec.class,
          Validators.requirements(false));
      case ArtifactKeys.PLAN -> check(content, TaskPlan.class, Validators.plan(maxTasks));
      case ArtifactKeys.DESIGN -> check(content, DesignSpec.class, d -> List.of());
      case ArtifactKeys.OPENAPI -> contract(content);
      case ArtifactKeys.CLARIFICATION_ANSWERS -> answers(content);
      default -> List.of();
    };
  }

  private List<String> contract(String content) {
    try {
      OpenApiDocument proposed = OpenApiDocument.parse(content);
      return baselineContract == null || baselineContract.isBlank() ? List.of()
          : ApiCompatibilityGate.breakingChanges(OpenApiDocument.parse(baselineContract),
              proposed);
    } catch (IllegalArgumentException e) {
      return List.of(e.getMessage());
    }
  }

  private List<String> answers(String content) {
    List<String> problems = new ArrayList<>();
    if (content == null || content.isBlank()) {
      problems.add("answers are empty");
      return problems;
    }
    artifacts.latest(ArtifactKeys.REQUIREMENTS_DRAFT).ifPresent(draft -> AgentSupport.list(
            AgentSupport.parse(draft.content(), RequirementSpec.class).ambiguities())
        .forEach(q -> {
          if (!content.contains(q.id() + ":")) {
            problems.add("no answer for " + q.id() + " (" + q.question() + ")");
          }
        }));
    return problems;
  }

  private static <T> List<String> check(String content, Class<T> type,
      Function<T, List<String>> validator) {
    try {
      return validator.apply(AgentSupport.parse(content, type));
    } catch (IllegalStateException e) {
      return List.of(e.getMessage());
    }
  }
}
