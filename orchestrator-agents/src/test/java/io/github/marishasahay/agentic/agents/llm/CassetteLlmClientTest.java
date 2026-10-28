package io.github.marishasahay.agentic.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CassetteLlmClientTest {

  @TempDir
  Path cassettes;

  private final AtomicInteger liveCalls = new AtomicInteger();
  private final LlmClient live = call -> {
    liveCalls.incrementAndGet();
    return new LlmReply("{\"answer\":" + liveCalls.get() + "}", "model-x", "live", 1200, 900, 80);
  };

  private static LlmCall call(String key, String prompt) {
    return new LlmCall(key, "planner", "system", prompt, Map.class);
  }

  @Test
  void recordsOnceAndReplaysDeterministicallyWithoutTheModel() {
    LlmReply recorded = new CassetteLlmClient(live, cassettes, LlmMode.RECORD)
        .complete(call("plan/attempt-1/primary/planner", "make a plan"));

    LlmReply replayed = new CassetteLlmClient(null, cassettes, LlmMode.REPLAY)
        .complete(call("plan/attempt-1/primary/planner", "make a plan"));

    assertThat(recorded.mode()).isEqualTo("record");
    assertThat(replayed.mode()).isEqualTo("replay");
    assertThat(replayed.text()).isEqualTo(recorded.text());
    assertThat(replayed.inputTokens()).isEqualTo(900);
    assertThat(liveCalls).hasValue(1);
    assertThat(cassettes.resolve("plan_attempt-1_primary_planner.json")).exists();
  }

  @Test
  void flagsPromptDriftInsteadOfFailingTheReplay() {
    new CassetteLlmClient(live, cassettes, LlmMode.RECORD).complete(call("k", "build took 3.1s"));

    LlmReply replayed = new CassetteLlmClient(null, cassettes, LlmMode.REPLAY)
        .complete(call("k", "build took 2.7s"));

    assertThat(replayed.mode()).isEqualTo("replay-drift");
  }

  @Test
  void failsClearlyWhenNoRecordingExistsAndNeverWritesTheApiKey() throws Exception {
    assertThatThrownBy(() -> new CassetteLlmClient(null, cassettes, LlmMode.REPLAY)
        .complete(call("missing", "p")))
        .hasMessageContaining("No recorded response for call missing");
    assertThatThrownBy(() -> new CassetteLlmClient(null, cassettes, LlmMode.RECORD))
        .isInstanceOf(IllegalArgumentException.class);

    new CassetteLlmClient(live, cassettes, LlmMode.RECORD).complete(call("k", "p"));
    assertThat(Files.readString(cassettes.resolve("k.json"))).doesNotContainIgnoringCase("key=");
  }

  @Test
  void handAuthoredFixturesAreLabelledAndCanSimulateOutages() throws Exception {
    Files.writeString(cassettes.resolve("docs_e1_attempt-1_primary_tech-writer.json"), """
        {"key":"docs/e1/attempt-1/primary/tech-writer","agent":"tech-writer","model":"fixture",
         "promptSha256":"*","response":null,"error":"503 UNAVAILABLE: model overloaded"}""");
    Files.writeString(cassettes.resolve("plan_e1_attempt-1_primary_planner.json"), """
        {"key":"plan/e1/attempt-1/primary/planner","agent":"planner","model":"fixture",
         "promptSha256":"*","response":"{}"}""");
    CassetteLlmClient replay = new CassetteLlmClient(null, cassettes, LlmMode.REPLAY);

    assertThatThrownBy(() -> replay.complete(call("docs/e1/attempt-1/primary/tech-writer", "p")))
        .hasMessage("503 UNAVAILABLE: model overloaded");
    assertThat(replay.complete(call("plan/e1/attempt-1/primary/planner", "anything")).mode())
        .isEqualTo("fixture");
  }

  @Test
  void modelSettingsTreatPlaceholdersAsMissing() {
    ModelSettings placeholders = ModelSettings.fromEnvironment(Map.of(
        ModelSettings.API_KEY_VAR, "<your-gemini-api-key>", ModelSettings.MODEL_VAR, "m"));

    assertThatThrownBy(placeholders::requireLiveReady).hasMessageContaining("GEMINI_API_KEY")
        .hasMessageNotContaining("AGENTIC_MODEL");
    assertThat(placeholders.fallbackModel()).isEqualTo("m");
    assertThat(placeholders.toString()).contains("apiKey=unset").doesNotContain("<your");
    assertThat(ModelSettings.fromEnvironment(Map.of(ModelSettings.API_KEY_VAR, "secret",
        ModelSettings.MODEL_VAR, "m")).toString()).doesNotContain("secret");
  }
}
