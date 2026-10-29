package io.github.marishasahay.agentic.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.RateLimitException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ThrottledLlmClientTest {

  private static final LlmCall CALL = new LlmCall("k", "coder", "s", "u", String.class);
  private static final LlmReply OK = new LlmReply("{}", "m", "live", 1, 1, 1);

  private final List<Duration> waits = new CopyOnWriteArrayList<>();

  @Test
  void waitsOutRateLimitsAndServerErrorsWithGrowingBackoff() {
    AtomicInteger calls = new AtomicInteger();
    LlmClient flaky = call -> {
      int n = calls.incrementAndGet();
      if (n == 1) {
        throw new RateLimitException("429 quota");
      }
      if (n == 2) {
        throw new InternalServerException("500");
      }
      return OK;
    };

    LlmReply reply = new ThrottledLlmClient(flaky, new Semaphore(1), 5, Duration.ofSeconds(5),
        waits::add).complete(CALL);

    assertThat(reply).isEqualTo(OK);
    assertThat(waits).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10));
  }

  @Test
  void givesUpAfterMaxRetriesAndCapsTheBackoff() {
    LlmClient alwaysLimited = call -> {
      throw new RateLimitException("429");
    };
    ThrottledLlmClient client = new ThrottledLlmClient(alwaysLimited, new Semaphore(1), 5,
        Duration.ofSeconds(5), waits::add);

    assertThatThrownBy(() -> client.complete(CALL)).isInstanceOf(RateLimitException.class);
    assertThat(waits).extracting(Duration::toSeconds).containsExactly(5L, 10L, 20L, 40L, 60L);
  }

  @Test
  void passesNonRetriableErrorsThroughImmediately() {
    LlmClient broken = call -> {
      throw new IllegalArgumentException("bad request");
    };

    assertThatThrownBy(() -> new ThrottledLlmClient(broken, new Semaphore(1), 5,
        Duration.ofSeconds(5), waits::add).complete(CALL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(waits).isEmpty();
  }

  @Test
  void neverRunsMoreCallsAtOnceThanThePermitsAllow() throws Exception {
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    LlmClient slow = call -> {
      peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      inFlight.decrementAndGet();
      return OK;
    };
    ThrottledLlmClient client = ThrottledLlmClient.withDefaults(slow, new Semaphore(2));

    List<Thread> threads = List.of(Thread.ofVirtual().start(() -> client.complete(CALL)),
        Thread.ofVirtual().start(() -> client.complete(CALL)),
        Thread.ofVirtual().start(() -> client.complete(CALL)),
        Thread.ofVirtual().start(() -> client.complete(CALL)));
    Thread.sleep(300);
    release.countDown();
    for (Thread t : threads) {
      t.join();
    }

    assertThat(peak).hasValue(2);
  }
}
