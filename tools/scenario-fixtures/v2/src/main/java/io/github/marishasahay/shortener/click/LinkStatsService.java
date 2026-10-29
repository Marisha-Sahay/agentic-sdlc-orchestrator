package io.github.marishasahay.shortener.click;

import io.github.marishasahay.shortener.config.ShortenerProperties;
import io.github.marishasahay.shortener.link.InvalidLinkRequestException;
import io.github.marishasahay.shortener.link.LinkNotFoundException;
import io.github.marishasahay.shortener.link.LinkRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Builds a link's click statistics for a window of whole UTC days ending today. */
@Service
public class LinkStatsService {

  static final String DAYS_PARAMETER = "days";

  private final LinkRepository links;
  private final ClickRepository clicks;
  private final Clock clock;
  private final int maxDays;

  /**
   * Creates the service.
   *
   * @param links link persistence (the link must exist and not be deleted)
   * @param clicks click persistence
   * @param properties retention, which bounds the window
   * @param clock time source
   */
  public LinkStatsService(LinkRepository links, ClickRepository clicks,
      ShortenerProperties properties, Clock clock) {
    this.links = links;
    this.clicks = clicks;
    this.clock = clock;
    this.maxDays = (int) properties.clicks().retention().toDays();
  }

  /**
   * Statistics for the last {@code days} UTC days, including today.
   *
   * @param code the short code
   * @param days window length, 1 to the retention period in days
   * @return the statistics
   * @throws InvalidLinkRequestException if {@code days} is out of range
   * @throws LinkNotFoundException if no active link exists for the code
   */
  public LinkStats stats(String code, int days) {
    if (days < 1 || days > maxDays) {
      throw new InvalidLinkRequestException(DAYS_PARAMETER,
          "must be between 1 and " + maxDays);
    }
    links.findActiveByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    LocalDate to = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    LocalDate from = to.minusDays(days - 1L);
    Map<LocalDate, Long> counts = clicks.dailyCounts(code,
        from.atStartOfDay(ZoneOffset.UTC).toInstant());
    List<LinkStats.DailyClicks> daily = new ArrayList<>();
    for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
      daily.add(new LinkStats.DailyClicks(day, counts.getOrDefault(day, 0L)));
    }
    ClickRepository.Totals totals = clicks.totals(code);
    return new LinkStats(code, totals.total(), totals.lastClickAt(), from, to, daily);
  }
}
