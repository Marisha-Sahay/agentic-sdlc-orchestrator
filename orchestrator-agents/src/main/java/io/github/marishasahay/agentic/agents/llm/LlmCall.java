package io.github.marishasahay.agentic.agents.llm;

/**
 * One structured request to a model.
 *
 * @param key stable position of the call in the run, the replay key:
 *     {@code node/e<execution>/attempt-<n>/<strategy>/<agent>}
 * @param agent agent role, for telemetry
 * @param systemPrompt role, rules and output contract
 * @param userPrompt task-specific context
 * @param responseType record type the reply must deserialize into (drives the JSON schema)
 */
public record LlmCall(
    String key, String agent, String systemPrompt, String userPrompt, Class<?> responseType) {}
