package io.github.marishasahay.agentic.core.graph;

import java.time.Duration;

/**
 * Bounded retry with exponential backoff. Every retry receives the failure evidence of the
 * previous attempts, so a retry is a corrected attempt rather than a blind repeat.
 *
 * @param maxAttempts total attempts for one strategy, including the first
 * @param initialBackoff delay before the second attempt
 * @param multiplier growth factor between successive delays
 * @param maxBackoff upper bound for any single delay
 */
public record RetryPolicy(
    int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {

  public RetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    if (multiplier < 1.0) {
      throw new IllegalArgumentException("multiplier must be >= 1.0");
    }
  }

  public static RetryPolicy none() {
    return new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO);
  }

  public static RetryPolicy standard() {
    return new RetryPolicy(3, Duration.ofMillis(200), 2.0, Duration.ofSeconds(5));
  }

  public static RetryPolicy attempts(int maxAttempts) {
    return new RetryPolicy(maxAttempts, Duration.ofMillis(200), 2.0, Duration.ofSeconds(5));
  }

  /**
   * Delay to wait after the given failed attempt.
   *
   * @param failedAttempt 1-based number of the attempt that just failed
   * @return the backoff before the next attempt
   */
  public Duration backoffAfter(int failedAttempt) {
    double millis = initialBackoff.toMillis() * Math.pow(multiplier, failedAttempt - 1);
    return Duration.ofMillis((long) Math.min(millis, maxBackoff.toMillis()));
  }
}
