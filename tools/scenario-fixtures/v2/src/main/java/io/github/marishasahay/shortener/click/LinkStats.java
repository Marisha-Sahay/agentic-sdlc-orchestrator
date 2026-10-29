package io.github.marishasahay.shortener.click;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Click statistics of one link.
 *
 * @param code the short code
 * @param totalClicks clicks recorded within the retention period
 * @param lastClickAt most recent recorded click, or null
 * @param from first day of the daily window (UTC)
 * @param to last day of the daily window (UTC, today)
 * @param daily clicks per UTC day, oldest first, zero-filled
 */
public record LinkStats(String code, long totalClicks, Instant lastClickAt, LocalDate from,
    LocalDate to, List<DailyClicks> daily) {

  /**
   * Clicks on one UTC day.
   *
   * @param date the day
   * @param clicks number of clicks
   */
  public record DailyClicks(LocalDate date, long clicks) {}
}
