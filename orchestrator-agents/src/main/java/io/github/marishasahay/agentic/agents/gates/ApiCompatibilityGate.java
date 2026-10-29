package io.github.marishasahay.agentic.agents.gates;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.marishasahay.agentic.agents.ArchitectAgent;
import io.github.marishasahay.agentic.agents.ArtifactKeys;
import io.github.marishasahay.agentic.agents.analysis.SourceContext;
import io.github.marishasahay.agentic.core.gate.Gate;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Structural backward compatibility of the API contract against the baseline. For every
 * baseline operation it checks that the operation still exists, keeps its documented success
 * status codes, does not start requiring request properties it did not require, still accepts
 * every request property it accepted, and still returns every response property it guaranteed
 * (required). It does not check types, formats, enums, parameters or security schemes, and
 * cannot detect behavioural breaks; contract tests in the service cover behaviour.
 *
 * <p>The baseline is the run's baseline snapshot, not the working tree, so a proposal cannot
 * pass by overwriting the file it is compared with. The gate can check the design's proposed
 * contract or, at final validation, the contract file actually in the workspace.
 *
 * @param baselineContract baseline contract text, or empty for a greenfield service
 * @param source which contract to check
 */
public record ApiCompatibilityGate(String baselineContract, Source source) implements Gate {

  /** Which contract is checked. */
  public enum Source {
    /** The {@code design.openapi} artifact proposed by the design node. */
    DESIGN_ARTIFACT,
    /** {@code api/openapi.yaml} in the workspace (final validation). */
    WORKSPACE_FILE
  }

  public ApiCompatibilityGate(String baselineContract) {
    this(baselineContract, Source.DESIGN_ARTIFACT);
  }

  @Override
  public String name() {
    return source == Source.DESIGN_ARTIFACT ? "api-compatibility" : "final-api-compatibility";
  }

  @Override
  public boolean usesWorkspace() {
    return source == Source.WORKSPACE_FILE;
  }

  @Override
  public GateResult evaluate(GateContext context) {
    String text = source == Source.DESIGN_ARTIFACT ? context.output(ArtifactKeys.OPENAPI)
        : SourceContext.readIfExists(context.workspaceRoot(), ArchitectAgent.CONTRACT_PATH);
    OpenApiDocument proposed;
    try {
      proposed = OpenApiDocument.parse(text);
    } catch (IllegalArgumentException e) {
      return GateResult.fail(name(), e.getMessage());
    }
    if (baselineContract == null || baselineContract.isBlank()) {
      return GateResult.pass(name(), "valid OpenAPI " + proposed.version() + " with "
          + proposed.operations().size() + " operations (no baseline to compare)");
    }
    OpenApiDocument baseline = OpenApiDocument.parse(baselineContract);
    List<String> problems = breakingChanges(baseline, proposed);
    return problems.isEmpty()
        ? GateResult.pass(name(), "structurally backward compatible: " + baseline.operations()
            .size() + " baseline operations kept with their success statuses, request and "
            + "required response properties (" + proposed.operations().size() + " total); "
            + "types, formats and behaviour are not checked by this gate")
        : GateResult.fail(name(), "breaking changes:\n- " + String.join("\n- ", problems));
  }

  /**
   * Structural breaking changes from a baseline to a proposed contract.
   *
   * @param baseline baseline contract
   * @param proposed proposed contract
   * @return human-readable problems, empty if compatible
   */
  static List<String> breakingChanges(OpenApiDocument baseline, OpenApiDocument proposed) {
    List<String> problems = new ArrayList<>();
    for (OpenApiDocument.Operation old : baseline.operations()) {
      OpenApiDocument.Operation now = proposed.operation(old.signature());
      if (now == null) {
        problems.add("operation removed: " + old.signature());
        continue;
      }
      for (String status : baseline.successStatuses(old)) {
        if (!proposed.successStatuses(now).contains(status)) {
          problems.add(old.signature() + ": success response " + status + " removed");
          continue;
        }
        JsonNode oldBody = baseline.responseSchema(old, status);
        Set<String> nowProperties = OpenApiDocument.properties(
            proposed.responseSchema(now, status));
        Set<String> nowRequired = OpenApiDocument.required(proposed.responseSchema(now, status));
        for (String property : OpenApiDocument.required(oldBody)) {
          if (!nowProperties.contains(property) || !nowRequired.contains(property)) {
            problems.add(old.signature() + " " + status + ": response property '" + property
                + "' is no longer guaranteed");
          }
        }
      }
      JsonNode oldRequest = baseline.requestSchema(old);
      JsonNode nowRequest = proposed.requestSchema(now);
      Set<String> oldRequired = OpenApiDocument.required(oldRequest);
      for (String property : OpenApiDocument.required(nowRequest)) {
        if (!oldRequired.contains(property)) {
          problems.add(old.signature() + ": request property '" + property
              + "' is newly required");
        }
      }
      Set<String> nowAccepted = OpenApiDocument.properties(nowRequest);
      for (String property : OpenApiDocument.properties(oldRequest)) {
        if (!nowAccepted.contains(property)) {
          problems.add(old.signature() + ": request property '" + property
              + "' is no longer accepted");
        }
      }
    }
    return problems;
  }
}
