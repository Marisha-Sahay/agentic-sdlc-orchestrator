package io.github.marishasahay.agentic.agents.llm;

import io.github.marishasahay.agentic.core.event.Json;
import io.github.marishasahay.agentic.core.util.Hashing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Record/replay decorator. Cassettes are keyed by the call's position in the run, not by a hash
 * of the prompt, because prompts legitimately contain non-deterministic text (build output with
 * timings). The prompt hash is still stored, and a replay whose prompt differs from the recording
 * is reported as {@code replay-drift} so reviewers can see when a replay no longer matches.
 *
 * <p>Hand-authored fixtures (used by the governance drill) carry {@code promptSha256 = "*"} and
 * are reported as {@code fixture}; a fixture may also carry an {@code error} to simulate a model
 * outage.
 */
public final class CassetteLlmClient implements LlmClient {

  /**
   * On-disk form of one exchange.
   *
   * @param key call position
   * @param agent agent role
   * @param model model that answered
   * @param promptSha256 hash of system + user prompt at record time
   * @param response raw reply
   * @param latencyMs original latency
   * @param inputTokens prompt tokens
   * @param outputTokens completion tokens
   * @param recordedAt when it was recorded
   * @param error simulated failure message (fixtures only), or null
   */
  public record Cassette(
      String key, String agent, String model, String promptSha256, String response,
      long latencyMs, int inputTokens, int outputTokens, Instant recordedAt, String error) {}

  static final String FIXTURE = "*";

  private final LlmClient delegate;
  private final Path directory;
  private final LlmMode mode;

  /**
   * Creates the decorator.
   *
   * @param delegate live client (may be null in replay mode)
   * @param directory cassette directory for the scenario
   * @param mode live, record or replay
   */
  public CassetteLlmClient(LlmClient delegate, Path directory, LlmMode mode) {
    if (mode != LlmMode.REPLAY && delegate == null) {
      throw new IllegalArgumentException(mode + " mode needs a live model client");
    }
    this.delegate = delegate;
    this.directory = directory;
    this.mode = mode;
  }

  @Override
  public LlmReply complete(LlmCall call) {
    String promptSha = Hashing.sha256(call.systemPrompt() + "\n---\n" + call.userPrompt());
    Path file = directory.resolve(call.key().replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    return switch (mode) {
      case LIVE -> delegate.complete(call);
      case RECORD -> record(call, promptSha, file);
      case REPLAY -> replay(call, promptSha, file);
    };
  }

  private LlmReply record(LlmCall call, String promptSha, Path file) {
    LlmReply reply = delegate.complete(call);
    Cassette cassette = new Cassette(call.key(), call.agent(), reply.model(), promptSha,
        reply.text(), reply.latencyMs(), reply.inputTokens(), reply.outputTokens(),
        Instant.now(), null);
    try {
      Files.createDirectories(directory);
      Files.writeString(file, Json.MAPPER.writerWithDefaultPrettyPrinter()
          .writeValueAsString(cassette), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write cassette " + file, e);
    }
    return new LlmReply(reply.text(), reply.model(), "record", reply.latencyMs(),
        reply.inputTokens(), reply.outputTokens());
  }

  private LlmReply replay(LlmCall call, String promptSha, Path file) {
    if (!Files.exists(file)) {
      throw new IllegalStateException("No recorded response for call " + call.key()
          + " (" + file + "). Re-record with --llm record, or run live.");
    }
    try {
      Cassette cassette = Json.read(Files.readString(file, StandardCharsets.UTF_8),
          Cassette.class);
      if (cassette.error() != null) {
        throw new IllegalStateException(cassette.error());
      }
      String replayMode = FIXTURE.equals(cassette.promptSha256()) ? "fixture"
          : cassette.promptSha256().equals(promptSha) ? "replay" : "replay-drift";
      return new LlmReply(cassette.response(), cassette.model(), replayMode,
          cassette.latencyMs(), cassette.inputTokens(), cassette.outputTokens());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
