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
 * Adapter from {@link LlmClient} to any LangChain4j {@link ChatModel}. Structured calls send a
 * JSON schema derived from the response record, so the provider constrains decoding to the
 * contract instead of the agent hoping the model follows instructions.
 */
public final class LangChain4jLlmClient implements LlmClient {

  private final ChatModel model;
  private final String modelName;

  public LangChain4jLlmClient(ChatModel model, String modelName) {
    this.model = model;
    this.modelName = modelName;
  }

  @Override
  public LlmReply complete(LlmCall call) {
    ChatRequest.Builder request = ChatRequest.builder()
        .messages(SystemMessage.from(call.systemPrompt()), UserMessage.from(call.userPrompt()));
    if (call.responseType() != null && call.responseType() != String.class) {
      JsonSchema schema = JsonSchemas.jsonSchemaFrom(call.responseType()).orElse(null);
      ResponseFormat.Builder format = ResponseFormat.builder().type(ResponseFormatType.JSON);
      if (schema != null) {
        format.jsonSchema(schema);
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
