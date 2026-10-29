package io.github.marishasahay.agentic.agents.llm;

import dev.langchain4j.exception.RetriableException;
import java.time.Duration;
import java.util.concurrent.Semaphore;

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
          if (retry >= maxRetries) {
            throw e;
          }
          sleeper.sleep(backoff(retry));
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while backing off from the provider", e);
    } finally {
      permits.release();
    }
  }

  Duration backoff(int retry) {
    long millis = baseBackoff.toMillis() << Math.min(retry, 20);
    return Duration.ofMillis(Math.min(millis, MAX_BACKOFF.toMillis()));
  }
}
