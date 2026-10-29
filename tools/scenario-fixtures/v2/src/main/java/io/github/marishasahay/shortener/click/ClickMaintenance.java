package io.github.marishasahay.shortener.click;

import io.github.marishasahay.shortener.config.ShortenerProperties;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background work for click analytics: flushing the queue and enforcing retention. Disabled
 * with {@code shortener.clicks.scheduling-enabled=false} (tests flush explicitly).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "shortener.clicks.scheduling-enabled", havingValue = "true",
    matchIfMissing = true)
public class ClickMaintenance {

  private static final Logger LOG = LoggerFactory.getLogger(ClickMaintenance.class);

  private final ClickRecorder recorder;
  private final ClickRepository repository;
  private final Duration retention;
  private final Clock clock;

  /**
   * Creates the scheduler.
   *
   * @param recorder the recorder to flush
   * @param repository click persistence
   * @param properties retention settings
   * @param clock time source
   */
  public ClickMaintenance(ClickRecorder recorder, ClickRepository repository,
      ShortenerProperties properties, Clock clock) {
    this.recorder = recorder;
    this.repository = repository;
    this.retention = properties.clicks().retention();
    this.clock = clock;
  }

  /** Writes queued clicks; statistics lag real time by about this interval. */
  @Scheduled(fixedDelayString = "${shortener.clicks.flush-interval:1s}")
  public void flush() {
    recorder.flush();
  }

  /** Deletes clicks older than the retention period. */
  @Scheduled(fixedDelayString = "${shortener.clicks.purge-interval:1h}")
  public void purge() {
    int deleted = repository.deleteOlderThan(clock.instant().minus(retention));
    if (deleted > 0) {
      LOG.info("Deleted {} clicks older than {}", deleted, retention);
    }
  }
}
