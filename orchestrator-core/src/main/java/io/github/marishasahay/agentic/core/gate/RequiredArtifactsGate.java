package io.github.marishasahay.agentic.core.gate;

import java.util.List;

/**
 * Passes when every listed artifact exists and is non-blank. As an entry gate it enforces the
 * contract between stages; as an exit gate it checks a node produced what it promised.
 *
 * @param keys required artifact keys
 */
public record RequiredArtifactsGate(List<String> keys) implements Gate {

  public RequiredArtifactsGate {
    keys = List.copyOf(keys);
  }

  public static RequiredArtifactsGate of(String... keys) {
    return new RequiredArtifactsGate(List.of(keys));
  }

  @Override
  public String name() {
    return "required-artifacts";
  }

  @Override
  public GateResult evaluate(GateContext context) {
    List<String> missing = keys.stream()
        .filter(k -> {
          String value = context.output(k);
          return value == null || value.isBlank();
        })
        .toList();
    return missing.isEmpty()
        ? GateResult.pass(name(), "present: " + keys)
        : GateResult.fail(name(), "missing or empty artifacts: " + missing);
  }
}
