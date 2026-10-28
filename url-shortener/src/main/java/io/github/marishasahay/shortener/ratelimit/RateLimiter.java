package io.github.marishasahay.shortener.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.marishasahay.shortener.config.ShortenerProperties;
import java.time.Clock;

/**
 * In-memory, per-client token-bucket rate limiter.
 *
 * <p>Buckets live in a size-bounded Caffeine cache so memory cannot grow without limit. A bucket
 * idle for a full period would be full again anyway, so expiring it after that much inactivity
 * loses no state. The limit is per instance; a multi-instance deployment needs a shared store.
 */
public class RateLimiter {

  private final int capacity;
  private final long periodMillis;
  private final Clock clock;
  private final Cache<String, TokenBucket> buckets;

  /**
   * Creates a rate limiter.
   *
   * @param settings capacity, refill period and the maximum number of tracked clients
   * @param clock time source used for refill
   */
  public RateLimiter(ShortenerProperties.RateLimit settings, Clock clock) {
    this.capacity = settings.requestsPerPeriod();
    this.periodMillis = settings.period().toMillis();
    if (capacity < 1 || periodMillis < 1) {
      throw new IllegalArgumentException("rate limit capacity and period must be positive");
    }
    this.clock = clock;
    this.buckets = Caffeine.newBuilder()
        .maximumSize(settings.maxTrackedClients())
        .expireAfterAccess(settings.period())
        .build();
  }

  /**
   * Takes one token from the client's bucket if available.
   *
   * @param clientKey identifies the client, e.g. its IP address
   * @return whether the request is allowed and, if not, when to retry
   */
  public RateLimitDecision tryAcquire(String clientKey) {
    long now = clock.millis();
    TokenBucket bucket = buckets.get(clientKey,
        key -> new TokenBucket(capacity, periodMillis, now));
    return bucket.tryConsume(now);
  }
}
