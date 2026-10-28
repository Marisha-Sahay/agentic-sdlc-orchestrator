package io.github.marishasahay.agentic.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.marishasahay.agentic.agents.llm.LlmCall;
import io.github.marishasahay.agentic.agents.llm.LlmClient;
import io.github.marishasahay.agentic.agents.llm.LlmReply;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.event.Json;
import java.util.List;

/**
 * Shared plumbing for model-backed agents: prompt assembly, retry feedback, telemetry and
 * contract parsing. A reply that does not parse into the contract throws, which the engine turns
 * into a failed attempt whose parse error is shown to the next attempt.
 */
public final class AgentSupport {

  private static final String OUTPUT_RULES = """
      Output contract: respond with ONE JSON object that matches the response schema exactly.
      No Markdown fences, no commentary outside the JSON. Use empty arrays rather than null.
      Never include secrets, API keys or credentials in any output.""";
  private static final int MAX_FEEDBACK_CHARS = 8_000;

  private final LlmClient client;

  public AgentSupport(LlmClient client) {
    this.client = client;
  }

  /**
   * Calls the model with a structured contract.
   *
   * @param ctx node context (for the replay key, feedback and telemetry)
   * @param agent agent role
   * @param system role and rules
   * @param user task context
   * @param type contract record
   * @param <T> contract type
   * @return the parsed reply
   */
  public <T> T ask(NodeContext ctx, String agent, String system, String user, Class<T> type) {
    String key = ctx.node().id() + "/attempt-" + ctx.attempt() + "/" + ctx.strategy() + "/"
        + agent;
    LlmReply reply = client.complete(new LlmCall(key, agent, system + "\n\n" + OUTPUT_RULES,
        user + feedbackSection(ctx.feedback()), type));
    ctx.recordAgentCall(agent, reply.model(), reply.mode(), reply.latencyMs(),
        reply.inputTokens(), reply.outputTokens());
    return parse(reply.text(), type);
  }

  /**
   * Parses a reply, tolerating Markdown fences some models add despite instructions.
   *
   * @param text raw reply
   * @param type contract
   * @param <T> contract type
   * @return parsed value
   */
  public static <T> T parse(String text, Class<T> type) {
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("Model returned an empty reply for " + type.getSimpleName());
    }
    String json = text.strip();
    if (json.startsWith("```")) {
      json = json.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "");
    }
    try {
      return Json.read(json, type);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Reply did not match the " + type.getSimpleName()
          + " JSON contract: " + e.getMessage(), e);
    }
  }

  /**
   * Pretty JSON for artifacts humans read.
   *
   * @param value value
   * @return indented JSON
   */
  public static String pretty(Object value) {
    try {
      return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Null-safe list access for model output.
   *
   * @param list possibly null list
   * @param <T> element type
   * @return the list or an empty one
   */
  public static <T> List<T> list(List<T> list) {
    return list == null ? List.of() : list;
  }

  static String feedbackSection(List<String> feedback) {
    if (feedback.isEmpty()) {
      return "";
    }
    String joined = String.join("\n\n", feedback);
    if (joined.length() > MAX_FEEDBACK_CHARS) {
      joined = "...(older feedback truncated)\n" + joined.substring(joined.length()
          - MAX_FEEDBACK_CHARS);
    }
    return "\n\n## Previous attempts were rejected\n"
        + "Fix every problem below. Do not repeat a rejected approach.\n\n" + joined;
  }
}
