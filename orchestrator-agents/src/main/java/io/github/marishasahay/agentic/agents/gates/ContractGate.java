package io.github.marishasahay.agentic.agents.gates;

import io.github.marishasahay.agentic.agents.AgentSupport;
import io.github.marishasahay.agentic.core.gate.Gate;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;
import java.util.List;
import java.util.function.Function;

/**
 * Validates a structured artifact: it must parse into its contract type, and a semantic
 * validator must report no problems. The problems become the agent's feedback on retry.
 *
 * @param name gate name
 * @param key artifact to check
 * @param type contract type
 * @param validator semantic checks returning human-readable problems
 * @param <T> contract type
 */
public record ContractGate<T>(
    String name, String key, Class<T> type, Function<T, List<String>> validator)
    implements Gate {

  @Override
  public GateResult evaluate(GateContext context) {
    String content = context.output(key);
    if (content == null || content.isBlank()) {
      return GateResult.fail(name, "artifact " + key + " was not produced");
    }
    T value;
    try {
      value = AgentSupport.parse(content, type);
    } catch (IllegalStateException e) {
      return GateResult.fail(name, e.getMessage());
    }
    List<String> problems = validator.apply(value);
    return problems.isEmpty()
        ? GateResult.pass(name, key + " satisfies the " + type.getSimpleName() + " contract")
        : GateResult.fail(name, "contract violations in " + key + ":\n- "
            + String.join("\n- ", problems));
  }
}
