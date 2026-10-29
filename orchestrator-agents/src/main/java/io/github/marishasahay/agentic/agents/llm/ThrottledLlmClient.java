package io.github.marishasahay.agentic.agents.llm;

import dev.langchain4j.exception.RetriableException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Protects the provider quota. Caps concurrent model calls with a semaphore shared by every
 * client using the same API key, and waits out rate limits and transient provider errors with
 * exponential backoff instead of failing the attempt.
 *
 * <p>A quota error says nothing about the quality of an agent's work, so it must not consume the
 * node's retry budget: the first live recording failed exactly that way, with six rate-limited
 * calls inside fifteen seconds exhausting a node's primary and fallback attempts.
 */
public final class ThrottledLlmClient implements LlmClient {

  /** Sleep hook so tests do not wait. */
  @FunctionalInterface
  public interface Sleeper {
    /**
     * Sleeps.
     *
     * @param duration how long
     * @throws InterruptedException if interrupted
     */
    void sleep(Duration duration) throws InterruptedException;
  }

  private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
  /** A provider asking us to wait longer than this has a quota we cannot wait out in a run. */
  private static final Duration MAX_HONOURED_RETRY_AFTER = Duration.ofMinutes(2);
  private static final Pattern RETRY_IN = Pattern.compile(
      "retry in (?:(\\d+)h)?(?:(\\d+)m)?(?:([\\d.]+)s)?", Pattern.CASE_INSENSITIVE);

  private final LlmClient delegate;
  private final Semaphore permits;
  private final int maxRetries;
  private final Duration baseBackoff;
  private final Sleeper sleeper;

  /**
   * Creates the decorator.
   *
   * @param delegate live client
   * @param permits concurrency limit shared by all clients on the same key
   * @param maxRetries retries after the first call for retriable provider errors
   * @param baseBackoff first wait; doubles each retry, capped at 60 seconds
   * @param sleeper sleep implementation
   */
  public ThrottledLlmClient(LlmClient delegate, Semaphore permits, int maxRetries,
      Duration baseBackoff, Sleeper sleeper) {
    this.delegate = delegate;
    this.permits = permits;
    this.maxRetries = maxRetries;
    this.baseBackoff = baseBackoff;
    this.sleeper = sleeper;
  }

  /**
   * Production defaults: 5 retries waiting 5, 10, 20, 40 and 60 seconds.
   *
   * @param delegate live client
   * @param permits shared concurrency limit
   * @return throttled client
   */
  public static ThrottledLlmClient withDefaults(LlmClient delegate, Semaphore permits) {
    return new ThrottledLlmClient(delegate, permits, 5, Duration.ofSeconds(5), Thread::sleep);
  }

  @Override
  public LlmReply complete(LlmCall call) {
    try {
      permits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for a model-call slot", e);
    }
    try {
      for (int retry = 0; ; retry++) {
        try {
          return delegate.complete(call);
        } catch (RetriableException e) {
          Optional<Duration> retryAfter = retryAfter(e.getMessage());
          if (retryAfter.isPresent() && retryAfter.get().compareTo(MAX_HONOURED_RETRY_AFTER) > 0) {
            throw new IllegalStateException("Provider quota exhausted; it asks to retry in "
                + retryAfter.get().toMinutes() + " min. Use a key with a higher quota, another "
                + "model, or replay mode. Provider said: " + firstLine(e.getMessage()), e);
          }
          if (retry >= maxRetries) {
            throw e;
          }
          Duration wait = backoff(retry);
          if (retryAfter.isPresent() && retryAfter.get().compareTo(wait) > 0) {
            wait = retryAfter.get();
          }
          sleeper.sleep(wait);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while backing off from the provider", e);
    } finally {
      permits.release();
    }
  }

  /**
   * Reads a provider retry hint such as "Please retry in 4h41m17.4s" or "retry in 12.5s".
   *
   * @param message provider error message
   * @return the requested wait, if the message has one
   */
  static Optional<Duration> retryAfter(String message) {
    if (message == null) {
      return Optional.empty();
    }
    Matcher m = RETRY_IN.matcher(message);
    if (!m.find() || (m.group(1) == null && m.group(2) == null && m.group(3) == null)) {
      return Optional.empty();
    }
    long millis = 0;
    if (m.group(1) != null) {
      millis += Long.parseLong(m.group(1)) * 3_600_000L;
    }
    if (m.group(2) != null) {
      millis += Long.parseLong(m.group(2)) * 60_000L;
    }
    if (m.group(3) != null) {
      millis += (long) (Double.parseDouble(m.group(3)) * 1000);
    }
    return Optional.of(Duration.ofMillis(millis));
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "";
    }
    int i = message.indexOf("Quota exceeded");
    String text = i >= 0 ? message.substring(i) : message;
    return text.lines().findFirst().orElse("").trim();
  }

  Duration backoff(int retry) {
    long millis = baseBackoff.toMillis() << Math.min(retry, 20);
    return Duration.ofMillis(Math.min(millis, MAX_BACKOFF.toMillis()));
  }
}
