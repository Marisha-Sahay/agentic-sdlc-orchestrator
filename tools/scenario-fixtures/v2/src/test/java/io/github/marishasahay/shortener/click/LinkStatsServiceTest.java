package io.github.marishasahay.shortener.click;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.marishasahay.shortener.config.ShortenerProperties;
import io.github.marishasahay.shortener.link.InvalidLinkRequestException;
import io.github.marishasahay.shortener.link.Link;
import io.github.marishasahay.shortener.link.LinkNotFoundException;
import io.github.marishasahay.shortener.link.LinkRepository;
import io.github.marishasahay.shortener.support.MutableClock;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinkStatsServiceTest {

  private static final Instant NOW = Instant.parse("2026-01-07T15:00:00Z");

  private final LinkRepository links = mock(LinkRepository.class);
  private final ClickRepository clicks = mock(ClickRepository.class);
  private final LinkStatsService service = new LinkStatsService(links, clicks,
      new ShortenerProperties(URI.create("http://localhost:8080"), 7,
          new ShortenerProperties.RedirectCache(Duration.ofMinutes(10), 100),
          new ShortenerProperties.RateLimit(20, Duration.ofMinutes(1), 100), false,
          new ShortenerProperties.Clicks(100, 10, Duration.ofDays(90))),
      new MutableClock(NOW));

  @Test
  void buildsAZeroFilledWindowOfWholeUtcDaysEndingToday() {
    when(links.findActiveByCode("abcd")).thenReturn(Optional.of(
        new Link(1, "abcd", "https://example.com", NOW.minus(Duration.ofDays(30)), null)));
    when(clicks.dailyCounts("abcd", Instant.parse("2026-01-05T00:00:00Z"))).thenReturn(Map.of(
        LocalDate.parse("2026-01-05"), 2L, LocalDate.parse("2026-01-07"), 3L));
    when(clicks.totals("abcd")).thenReturn(new ClickRepository.Totals(9,
        Instant.parse("2026-01-07T14:59:00Z")));

    LinkStats stats = service.stats("abcd", 3);

    assertThat(stats.from()).isEqualTo(LocalDate.parse("2026-01-05"));
    assertThat(stats.to()).isEqualTo(LocalDate.parse("2026-01-07"));
    assertThat(stats.daily()).extracting(LinkStats.DailyClicks::clicks).containsExactly(2L, 0L,
        3L);
    assertThat(stats.totalClicks()).isEqualTo(9);
    assertThat(stats.lastClickAt()).isEqualTo(Instant.parse("2026-01-07T14:59:00Z"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 91})
  void rejectsWindowsOutsideTheRetentionPeriodBeforeQuerying(int days) {
    assertThatThrownBy(() -> service.stats("abcd", days))
        .isInstanceOf(InvalidLinkRequestException.class)
        .hasMessage("days must be between 1 and 90");
    verifyNoInteractions(links, clicks);
  }

  @Test
  void unknownOrDeletedLinksHaveNoStatistics() {
    when(links.findActiveByCode("gone")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.stats("gone", 7))
        .isInstanceOf(LinkNotFoundException.class);
    verifyNoInteractions(clicks);
  }
}
