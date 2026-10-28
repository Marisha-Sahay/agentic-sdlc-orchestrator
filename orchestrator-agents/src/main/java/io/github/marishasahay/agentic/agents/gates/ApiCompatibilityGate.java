package io.github.marishasahay.agentic.agents.gates;

import io.github.marishasahay.agentic.agents.ArtifactKeys;
import io.github.marishasahay.agentic.core.gate.Gate;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The proposed contract must be a valid OpenAPI 3 document and, for an existing service, keep
 * every operation of the baseline contract (removal is the breaking change most likely to take
 * consumers down). The baseline is read from the run's baseline snapshot, not the working tree,
 * so a proposal can't "pass" by first overwriting the file it is compared to.
 *
 * @param baselineContract baseline contract text, or empty for a greenfield service
 */
public record ApiCompatibilityGate(String baselineContract) implements Gate {

  @Override
  public String name() {
    return "api-compatibility";
  }

  @Override
  public GateResult evaluate(GateContext context) {
    OpenApiDocument proposed;
    try {
      proposed = OpenApiDocument.parse(context.output(ArtifactKeys.OPENAPI));
    } catch (IllegalArgumentException e) {
      return GateResult.fail(name(), e.getMessage());
    }
    if (baselineContract == null || baselineContract.isBlank()) {
      return GateResult.pass(name(), "valid OpenAPI " + proposed.version() + " with "
          + proposed.operations().size() + " operations (no baseline to compare)");
    }
    Set<String> now = proposed.operations().stream().map(OpenApiDocument.Operation::signature)
        .collect(Collectors.toSet());
    List<String> removed = OpenApiDocument.parse(baselineContract).operations().stream()
        .map(OpenApiDocument.Operation::signature).filter(s -> !now.contains(s)).toList();
    return removed.isEmpty()
        ? GateResult.pass(name(), "backward compatible: all baseline operations kept, "
            + proposed.operations().size() + " total")
        : GateResult.fail(name(), "breaking change, operations removed: " + removed);
  }
}
