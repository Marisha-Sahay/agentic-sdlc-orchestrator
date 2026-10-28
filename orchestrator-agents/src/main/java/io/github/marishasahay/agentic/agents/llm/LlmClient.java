package io.github.marishasahay.agentic.agents.llm;

/**
 * The only way agents reach a model. Keeping this seam narrow is what makes record/replay,
 * model fallback and offline tests possible without touching agent code.
 */
@FunctionalInterface
public interface LlmClient {

  /**
   * Sends a call and waits for the reply.
   *
   * @param call request
   * @return reply with telemetry
   */
  LlmReply complete(LlmCall call);
}
