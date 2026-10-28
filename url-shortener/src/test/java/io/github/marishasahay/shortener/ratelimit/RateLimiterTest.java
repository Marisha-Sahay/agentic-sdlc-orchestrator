package io.github.marishasahay.shortener.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.marishasahay.shortener.config.ShortenerProperties;
import io.github.marishasahay.shortener.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

  private static final String CLIENT = "203.0.113.7";

  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
  private final RateLimiter limiter = new RateLimiter(
      new ShortenerProperties.RateLimit(20, Duration.ofMinutes(1), 1_000), clock);

  @Test
  void shouldAllowRequestsUpToCapacityThenReject() {
    assertThat(acquire(20)).isEqualTo(20);

    assertThat(limiter.tryAcquire(CLIENT).allowed()).isFalse();
  }

  @Test
  void shouldReportTimeUntilTheNextTokenWhenRejected() {
    acquire(20);

    RateLimitDecision decision = limiter.tryAcquire(CLIENT);

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.retryAfter()).isEqualTo(Duration.ofSeconds(3));
  }

  @Test
  void shouldShortenRetryAfterAsTimePasses() {
    acquire(20);
    clock.advance(Duration.ofMillis(1_000));

    assertThat(limiter.tryAcquire(CLIENT).retryAfter()).isEqualTo(Duration.ofMillis(2_000));
  }

  @Test
  void shouldRefillContinuouslyAtCapacityPerPeriod() {
    acquire(20);

    clock.advance(Duration.ofSeconds(3));
    assertThat(limiter.tryAcquire(CLIENT).allowed()).isTrue();
    assertThat(limiter.tryAcquire(CLIENT).allowed()).isFalse();

    clock.advance(Duration.ofSeconds(30));
    assertThat(acquire(20)).isEqualTo(10);
  }

  @Test
  void shouldNeverHoldMoreThanCapacityAfterLongIdlePeriod() {
    acquire(20);
    clock.advance(Duration.ofDays(30));

    assertThat(acquire(25)).isEqualTo(20);
  }

  @Test
  void shouldTrackEachClientIndependently() {
    acquire(20);

    assertThat(limiter.tryAcquire("198.51.100.1").allowed()).isTrue();
    assertThat(limiter.tryAcquire(CLIENT).allowed()).isFalse();
  }

  @Test
  void shouldNotRefillWhenClockMovesBackwards() {
    acquire(20);
    clock.advance(Duration.ofSeconds(-30));

    assertThat(limiter.tryAcquire(CLIENT).allowed()).isFalse();
  }

  @Test
  void shouldRejectNonPositiveSettings() {
    assertThatIllegalArgumentException().isThrownBy(() -> new RateLimiter(
        new ShortenerProperties.RateLimit(0, Duration.ofMinutes(1), 10), clock));
    assertThatIllegalArgumentException().isThrownBy(() -> new RateLimiter(
        new ShortenerProperties.RateLimit(5, Duration.ZERO, 10), clock));
  }

  private long acquire(int attempts) {
    return IntStream.range(0, attempts)
        .filter(i -> limiter.tryAcquire(CLIENT).allowed())
        .count();
  }
}
