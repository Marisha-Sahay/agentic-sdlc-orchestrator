package io.github.marishasahay.agentic.agents.llm;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.output.JsonSchemas;

/**
 * Adapter from {@link LlmClient} to any LangChain4j {@link ChatModel}. Structured calls request
 * JSON mode; the expected shape is in the prompt ({@link JsonShape}) and the contract is enforced
 * by parsing and gates. Provider-side schema enforcement is off by default because nested
 * schemas made Gemini 2.5 Flash hang for minutes (ADR-0010); it can be switched back on per
 * client for providers where it performs well.
 */
public final class LangChain4jLlmClient implements LlmClient {

  private final ChatModel model;
  private final String modelName;
  private final boolean providerSchema;

  public LangChain4jLlmClient(ChatModel model, String modelName) {
    this(model, modelName, false);
  }

  /**
   * Creates the adapter.
   *
   * @param model LangChain4j chat model
   * @param modelName model name for telemetry
   * @param providerSchema whether to send the JSON schema for provider-side enforcement
   */
  public LangChain4jLlmClient(ChatModel model, String modelName, boolean providerSchema) {
    this.model = model;
    this.modelName = modelName;
    this.providerSchema = providerSchema;
  }

  @Override
  public LlmReply complete(LlmCall call) {
    ChatRequest.Builder request = ChatRequest.builder()
        .messages(SystemMessage.from(call.systemPrompt()), UserMessage.from(call.userPrompt()));
    if (call.responseType() != null && call.responseType() != String.class) {
      ResponseFormat.Builder format = ResponseFormat.builder().type(ResponseFormatType.JSON);
      if (providerSchema) {
        JsonSchema schema = JsonSchemas.jsonSchemaFrom(call.responseType()).orElse(null);
        if (schema != null) {
          format.jsonSchema(schema);
        }
      }
      request.responseFormat(format.build());
    }
    long start = System.nanoTime();
    ChatResponse response = model.chat(request.build());
    long latencyMs = (System.nanoTime() - start) / 1_000_000;
    TokenUsage usage = response.tokenUsage();
    return new LlmReply(response.aiMessage().text(),
        response.modelName() == null ? modelName : response.modelName(), "live", latencyMs,
        usage == null || usage.inputTokenCount() == null ? -1 : usage.inputTokenCount(),
        usage == null || usage.outputTokenCount() == null ? -1 : usage.outputTokenCount());
  }
}
