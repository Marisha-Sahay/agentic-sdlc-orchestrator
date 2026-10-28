package io.github.marishasahay.shortener.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateLimitExceededExceptionTest {

  @ParameterizedTest(name = "{0} ms -> Retry-After {1}")
  @CsvSource({"0, 1", "1, 1", "999, 1", "1000, 1", "1001, 2", "2500, 3", "60000, 60"})
  void shouldRoundRetryAfterUpToWholeSecondsWithMinimumOfOne(long millis, long seconds) {
    RateLimitExceededException ex = new RateLimitExceededException(Duration.ofMillis(millis));

    assertThat(ex.retryAfterSeconds()).isEqualTo(seconds);
  }
}
