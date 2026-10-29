package io.github.marishasahay.shortener.click;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.marishasahay.shortener.config.ShortenerProperties;
import io.github.marishasahay.shortener.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClickRecorderTest {

  private final ClickRepository repository = mock(ClickRepository.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

  private ClickRecorder recorder(int capacity, int batchSize) {
    ShortenerProperties properties = new ShortenerProperties(URI.create("http://localhost:8080"),
        7, new ShortenerProperties.RedirectCache(Duration.ofMinutes(10), 100),
        new ShortenerProperties.RateLimit(20, Duration.ofMinutes(1), 100), false,
        new ShortenerProperties.Clicks(capacity, batchSize, Duration.ofDays(90)));
    return new ClickRecorder(properties, repository, clock, meters);
  }

  private double counter(String name) {
    return meters.counter(name).count();
  }

  @Test
  void aFullQueueDropsAndCountsClicksInsteadOfBlockingTheRedirect() {
    ClickRecorder recorder = recorder(2, 10);

    assertThat(recorder.record("abcd", null, null)).isTrue();
    assertThat(recorder.record("abcd", null, null)).isTrue();
    assertThat(recorder.record("abcd", null, null)).isFalse();

    assertThat(recorder.queued()).isEqualTo(2);
    assertThat(counter("shortener.clicks.dropped")).isEqualTo(1);
    verify(repository, never()).insertBatch(anyList());
  }

  @Test
  @SuppressWarnings("unchecked")
  void flushWritesQueuedClicksInBatchesWithTheTimeOfTheRedirect() {
    ClickRecorder recorder = recorder(100, 2);
    List<List<ClickEvent>> batches = new ArrayList<>();
    org.mockito.Mockito.doAnswer(call -> batches.add(List.copyOf(call.getArgument(0))))
        .when(repository).insertBatch(anyList());
    recorder.record("abcd", "https://t.co/xyz", "Mozilla/5.0 Chrome/120");
    clock.advance(Duration.ofSeconds(5));
    recorder.record("abcd", null, "curl/8");
    recorder.record("efgh", null, null);

    assertThat(recorder.flush()).isEqualTo(3);

    assertThat(batches).extracting(List::size).containsExactly(2, 1);
    assertThat(batches.getFirst()).containsExactly(
        new ClickEvent("abcd", Instant.parse("2026-01-01T00:00:00Z"), "t.co",
            AgentFamily.BROWSER),
        new ClickEvent("abcd", Instant.parse("2026-01-01T00:00:05Z"), null, AgentFamily.BOT));
    assertThat(counter("shortener.clicks.recorded")).isEqualTo(3);
    assertThat(recorder.flush()).isZero();
  }

  @Test
  void aFailedBatchIsCountedAsLostAndLaterBatchesStillFlush() {
    ClickRecorder recorder = recorder(100, 1);
    doThrow(new IllegalStateException("database down")).doNothing()
        .when(repository).insertBatch(anyList());
    recorder.record("abcd", null, null);
    recorder.record("efgh", null, null);

    assertThat(recorder.flush()).isEqualTo(1);

    assertThat(counter("shortener.clicks.failed")).isEqualTo(1);
    assertThat(counter("shortener.clicks.recorded")).isEqualTo(1);
    ArgumentCaptor<List<ClickEvent>> captor = ArgumentCaptor.captor();
    verify(repository, org.mockito.Mockito.times(2)).insertBatch(captor.capture());
    recorder.flushOnShutdown();
  }
}
