package io.github.marishasahay.agentic.agents.llm;

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import java.time.Duration;
import java.util.Map;

/**
 * Model configuration from the environment. Values are placeholders until configured; live and
 * record modes fail fast with an actionable message instead of sending a request with a bad key.
 *
 * <pre>
 * GEMINI_API_KEY          API key (never logged, never stored in cassettes)
 * AGENTIC_MODEL           primary model name
 * AGENTIC_FALLBACK_MODEL  model used by fallback strategies (defaults to the primary)
 * </pre>
 *
 * @param apiKey API key
 * @param primaryModel primary model name
 * @param fallbackModel fallback model name
 * @param timeout per-call timeout
 */
public record ModelSettings(
    String apiKey, String primaryModel, String fallbackModel, Duration timeout) {

  public static final String API_KEY_VAR = "GEMINI_API_KEY";
  public static final String MODEL_VAR = "AGENTIC_MODEL";
  public static final String FALLBACK_MODEL_VAR = "AGENTIC_FALLBACK_MODEL";

  /**
   * Reads settings from environment variables.
   *
   * @param env environment (usually {@code System.getenv()})
   * @return settings, possibly incomplete
   */
  public static ModelSettings fromEnvironment(Map<String, String> env) {
    String primary = env.getOrDefault(MODEL_VAR, "");
    String fallback = env.getOrDefault(FALLBACK_MODEL_VAR, "");
    return new ModelSettings(env.getOrDefault(API_KEY_VAR, ""), primary,
        isPlaceholder(fallback) ? primary : fallback, Duration.ofMinutes(3));
  }

  /**
   * Fails fast when live calls are impossible.
   *
   * @throws IllegalStateException naming the missing variables
   */
  public void requireLiveReady() {
    StringBuilder missing = new StringBuilder();
    if (isPlaceholder(apiKey)) {
      missing.append(' ').append(API_KEY_VAR);
    }
    if (isPlaceholder(primaryModel)) {
      missing.append(' ').append(MODEL_VAR);
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException("Live model calls need:" + missing
          + ". Set them (see .env.example) or use --llm replay.");
    }
  }

  /**
   * Builds a LangChain4j-backed client for a model.
   *
   * @param modelName model to use
   * @return client
   */
  public LlmClient client(String modelName) {
    requireLiveReady();
    GoogleAiGeminiChatModel model = GoogleAiGeminiChatModel.builder()
        .apiKey(apiKey)
        .modelName(modelName)
        .temperature(0.2)
        .timeout(timeout)
        .maxRetries(2)
        .build();
    return new LangChain4jLlmClient(model, modelName);
  }

  private static boolean isPlaceholder(String value) {
    return value == null || value.isBlank() || value.startsWith("<");
  }

  @Override
  public String toString() {
    return "ModelSettings[primary=" + primaryModel + ", fallback=" + fallbackModel
        + ", apiKey=" + (isPlaceholder(apiKey) ? "unset" : "set") + "]";
  }
}
