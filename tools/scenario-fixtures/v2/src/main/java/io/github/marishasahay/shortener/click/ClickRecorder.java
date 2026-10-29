package io.github.marishasahay.shortener.click;

import io.github.marishasahay.shortener.config.ShortenerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records clicks off the redirect path, best effort. The redirect only offers the click to a
 * bounded in-memory queue (never blocks, never touches the database); a scheduled flusher writes
 * queued clicks in batches. Clicks are lost, and counted, when the queue is full, when a batch
 * insert fails, or when the process dies before a flush; a graceful shutdown flushes first.
 * Counters: {@code shortener.clicks.recorded}, {@code .dropped} and {@code .failed}.
 */
@Component
public class ClickRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(ClickRecorder.class);

  private final BlockingQueue<ClickEvent> queue;
  private final int batchSize;
  private final ClickRepository repository;
  private final Clock clock;
  private final Counter recorded;
  private final Counter dropped;
  private final Counter failed;

  /**
   * Creates the recorder.
   *
   * @param properties queue capacity and batch size
   * @param repository click persistence
   * @param clock time source
   * @param meterRegistry where the counters are published
   */
  public ClickRecorder(ShortenerProperties properties, ClickRepository repository, Clock clock,
      MeterRegistry meterRegistry) {
    ShortenerProperties.Clicks settings = properties.clicks();
    this.queue = new ArrayBlockingQueue<>(settings.queueCapacity());
    this.batchSize = settings.batchSize();
    this.repository = repository;
    this.clock = clock;
    this.recorded = meterRegistry.counter("shortener.clicks.recorded");
    this.dropped = meterRegistry.counter("shortener.clicks.dropped");
    this.failed = meterRegistry.counter("shortener.clicks.failed");
    meterRegistry.gaugeCollectionSize("shortener.clicks.queued", List.of(), queue);
  }

  /**
   * Offers a click for recording. Constant time; never blocks the caller.
   *
   * @param code the short code that was followed
   * @param referer the {@code Referer} header, possibly null
   * @param userAgent the {@code User-Agent} header, possibly null
   * @return whether the click was queued (false: queue full, click dropped and counted)
   */
  public boolean record(String code, String referer, String userAgent) {
    if (queue.offer(ClickEvent.of(code, clock.instant(), referer, userAgent))) {
      return true;
    }
    dropped.increment();
    return false;
  }

  /**
   * Writes every queued click, in batches. Called on a fixed delay and at shutdown.
   *
   * @return number of clicks written
   */
  public synchronized int flush() {
    int written = 0;
    List<ClickEvent> batch = new ArrayList<>(batchSize);
    while (queue.drainTo(batch, batchSize) > 0) {
      try {
        repository.insertBatch(batch);
        recorded.increment(batch.size());
        written += batch.size();
      } catch (RuntimeException e) {
        failed.increment(batch.size());
        LOG.warn("Lost {} clicks: batch insert failed ({})", batch.size(), e.getMessage());
      }
      batch.clear();
    }
    return written;
  }

  /** Flushes what is queued before the application stops. */
  @PreDestroy
  public void flushOnShutdown() {
    flush();
  }

  int queued() {
    return queue.size();
  }
}
