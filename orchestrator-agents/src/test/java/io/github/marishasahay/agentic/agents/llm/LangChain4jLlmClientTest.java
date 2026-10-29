package io.github.marishasahay.agentic.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.output.JsonSchemas;
import io.github.marishasahay.agentic.agents.contract.CodeProposal;
import io.github.marishasahay.agentic.agents.contract.DesignSpec;
import io.github.marishasahay.agentic.agents.contract.ImpactAnalysis;
import io.github.marishasahay.agentic.agents.contract.ReleaseNotes;
import io.github.marishasahay.agentic.agents.contract.RequirementSpec;
import io.github.marishasahay.agentic.agents.contract.SecurityReview;
import io.github.marishasahay.agentic.agents.contract.TaskPlan;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Offline checks of the LangChain4j integration: schemas, request shape, telemetry mapping. */
class LangChain4jLlmClientTest {

  @ParameterizedTest
  @ValueSource(classes = {RequirementSpec.class, ImpactAnalysis.class, DesignSpec.class,
      TaskPlan.class, CodeProposal.class, SecurityReview.class, ReleaseNotes.class})
  void everyAgentContractBecomesAJsonSchemaForStructuredOutput(Class<?> contract) {
    assertThat(JsonSchemas.jsonSchemaFrom(contract)).isPresent()
        .get().satisfies(schema -> assertThat(schema.rootElement())
            .isInstanceOf(JsonObjectSchema.class));
  }

  @Test
  void rendersEveryContractAsAPromptShapeWithItsNestedFields() {
    String shape = JsonShape.of(TaskPlan.class);

    assertThat(shape).isEqualTo("{\"rationale\": \"string\", \"tasks\": [{\"id\": \"string\", "
        + "\"title\": \"string\", \"description\": \"string\", \"requirementIds\": [\"string\"], "
        + "\"files\": [\"string\"], \"dependsOn\": [\"string\"], "
        + "\"acceptanceCriteria\": [\"string\"]}]}");
    assertThat(JsonShape.of(RequirementSpec.class)).contains("\"functionalRequirements\": [{")
        .contains("\"acceptanceCriteria\": [\"string\"]");
    assertThat(JsonShape.of(Integer.class)).isEqualTo("0");
    assertThat(JsonShape.of(Boolean.class)).isEqualTo("true");
    assertThat(JsonShape.of(java.time.DayOfWeek.class)).startsWith("\"MONDAY|TUESDAY");
  }

  @Test
  void sendsSystemAndUserMessagesInJsonModeAndMapsTokenUsage() {
    AtomicReference<ChatRequest> sent = new AtomicReference<>();
    ChatModel model = new ChatModel() {
      @Override
      public ChatResponse doChat(ChatRequest request) {
        sent.set(request);
        return ChatResponse.builder().aiMessage(AiMessage.from("{\"tasks\":[]}"))
            .modelName("gemini-test").tokenUsage(new TokenUsage(120, 30)).build();
      }
    };

    LlmReply reply = new LangChain4jLlmClient(model, "configured-model")
        .complete(new LlmCall("plan/e1/attempt-1/primary/planner", "planner", "system rules",
            "user task", TaskPlan.class));

    assertThat(sent.get().messages()).hasSize(2);
    assertThat(sent.get().responseFormat().type()).isEqualTo(ResponseFormatType.JSON);
    assertThat(sent.get().responseFormat().jsonSchema()).isNull();
    assertThat(reply.text()).isEqualTo("{\"tasks\":[]}");
    assertThat(reply.model()).isEqualTo("gemini-test");
    assertThat(reply.mode()).isEqualTo("live");
    assertThat(reply.inputTokens()).isEqualTo(120);
    assertThat(reply.outputTokens()).isEqualTo(30);
  }

  @Test
  void canStillSendTheProviderSchemaWhenEnabled() {
    AtomicReference<ChatRequest> sent = new AtomicReference<>();
    ChatModel model = new ChatModel() {
      @Override
      public ChatResponse doChat(ChatRequest request) {
        sent.set(request);
        return ChatResponse.builder().aiMessage(AiMessage.from("{}")).build();
      }
    };

    new LangChain4jLlmClient(model, "m", true).complete(new LlmCall("k", "planner", "s", "u",
        TaskPlan.class));

    assertThat(sent.get().responseFormat().jsonSchema()).isNotNull();
  }

  @Test
  void buildsTheGeminiClientFromSettingsWithoutCallingTheNetwork() {
    ModelSettings settings = ModelSettings.fromEnvironment(Map.of(
        ModelSettings.API_KEY_VAR, "test-key-not-real", ModelSettings.MODEL_VAR, "gemini-x",
        ModelSettings.FALLBACK_MODEL_VAR, "gemini-y"));

    assertThat(settings.client(settings.primaryModel())).isInstanceOf(LangChain4jLlmClient.class);
    assertThat(settings.fallbackModel()).isEqualTo("gemini-y");
    assertThat(List.of(settings.toString())).allSatisfy(s ->
        assertThat(s).doesNotContain("test-key-not-real"));
  }
}
