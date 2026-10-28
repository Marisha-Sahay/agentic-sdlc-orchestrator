package io.github.marishasahay.shortener.ratelimit;

import java.time.Duration;

/**
 * The client exhausted its rate limit. Mapped to {@code 429 Too Many Requests} with a
 * {@code Retry-After} header.
 */
public class RateLimitExceededException extends RuntimeException {

  private final Duration retryAfter;

  public RateLimitExceededException(Duration retryAfter) {
    super("Rate limit exceeded");
    this.retryAfter = retryAfter;
  }

  /**
   * The wait before retrying, rounded up to whole seconds as required by {@code Retry-After}.
   *
   * @return seconds to wait, at least 1
   */
  public long retryAfterSeconds() {
    return Math.max(1, Math.ceilDiv(retryAfter.toMillis(), 1000L));
  }
}
