package io.github.marishasahay.shortener.ratelimit;

import java.time.Duration;

/**
 * A single client's token bucket with continuous refill. To keep refill exact without floating
 * point, one token is represented as {@code periodMillis} units: every elapsed millisecond adds
 * {@code capacity} units, so a full period adds exactly {@code capacity} tokens.
 */
final class TokenBucket {

  private final long capacity;
  private final long periodMillis;
  private final long maxUnits;
  private long units;
  private long lastRefillMillis;

  TokenBucket(int capacity, long periodMillis, long nowMillis) {
    this.capacity = capacity;
    this.periodMillis = periodMillis;
    this.maxUnits = capacity * periodMillis;
    this.units = maxUnits;
    this.lastRefillMillis = nowMillis;
  }

  synchronized RateLimitDecision tryConsume(long nowMillis) {
    refill(nowMillis);
    if (units >= periodMillis) {
      units -= periodMillis;
      return RateLimitDecision.allow();
    }
    long missingUnits = periodMillis - units;
    long waitMillis = Math.ceilDiv(missingUnits, capacity);
    return RateLimitDecision.reject(Duration.ofMillis(waitMillis));
  }

  private void refill(long nowMillis) {
    long elapsed = nowMillis - lastRefillMillis;
    if (elapsed <= 0) {
      return;
    }
    long boundedElapsed = Math.min(elapsed, periodMillis);
    units = Math.min(maxUnits, units + boundedElapsed * capacity);
    lastRefillMillis = nowMillis;
  }
}
