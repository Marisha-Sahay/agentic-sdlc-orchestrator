package io.github.marishasahay.agentic.agents.llm;

/**
 * A model reply plus the telemetry the audit log needs.
 *
 * @param text raw reply (JSON for structured calls)
 * @param model model that produced it
 * @param mode {@code live}, {@code record}, {@code replay} or {@code replay-drift}
 * @param latencyMs latency of the original call
 * @param inputTokens prompt tokens or -1
 * @param outputTokens completion tokens or -1
 */
public record LlmReply(
    String text, String model, String mode, long latencyMs, int inputTokens, int outputTokens) {}
