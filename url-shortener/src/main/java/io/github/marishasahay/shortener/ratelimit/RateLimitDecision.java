package io.github.marishasahay.shortener.ratelimit;

import java.time.Duration;

/**
 * Outcome of a rate-limit check.
 *
 * @param allowed whether the request may proceed
 * @param retryAfter when rejected, how long until a token is available; zero when allowed
 */
public record RateLimitDecision(boolean allowed, Duration retryAfter) {

  static RateLimitDecision allow() {
    return new RateLimitDecision(true, Duration.ZERO);
  }

  static RateLimitDecision reject(Duration retryAfter) {
    return new RateLimitDecision(false, retryAfter);
  }
}
