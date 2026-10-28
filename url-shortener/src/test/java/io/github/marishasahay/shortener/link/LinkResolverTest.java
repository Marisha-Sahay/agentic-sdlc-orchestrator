package io.github.marishasahay.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.marishasahay.shortener.support.MutableClock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LinkResolverTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final String TARGET = "https://example.com/";

  private final LinkRepository repository = mock(LinkRepository.class);
  private final MutableClock clock = new MutableClock(NOW);
  private final LinkResolver resolver =
      new LinkResolver(Caffeine.newBuilder().build(), repository, clock);

  @Test
  void shouldLoadTargetFromRepositoryOnCacheMiss() {
    when(repository.findActiveByCode("abcdefg")).thenReturn(Optional.of(link(null)));

    assertThat(resolver.resolve("abcdefg")).isEqualTo(TARGET);
  }

  @Test
  void shouldServeRepeatedLookupsFromCache() {
    when(repository.findActiveByCode("abcdefg")).thenReturn(Optional.of(link(null)));

    resolver.resolve("abcdefg");
    resolver.resolve("abcdefg");

    verify(repository, times(1)).findActiveByCode("abcdefg");
  }

  @Test
  void shouldThrowNotFoundAndNotCacheUnknownCodes() {
    when(repository.findActiveByCode("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.resolve("missing"))
        .isInstanceOf(LinkNotFoundException.class);
    assertThatThrownBy(() -> resolver.resolve("missing"))
        .isInstanceOf(LinkNotFoundException.class);
    verify(repository, times(2)).findActiveByCode("missing");
  }

  @Test
  void shouldThrowGoneWhenLinkLoadedFromDatabaseHasExpired() {
    when(repository.findActiveByCode("abcdefg"))
        .thenReturn(Optional.of(link(NOW.minusSeconds(1))));

    assertThatThrownBy(() -> resolver.resolve("abcdefg"))
        .isInstanceOf(LinkExpiredException.class);
  }

  @Test
  void shouldTreatExpiryInstantItselfAsExpired() {
    when(repository.findActiveByCode("abcdefg")).thenReturn(Optional.of(link(NOW)));

    assertThatThrownBy(() -> resolver.resolve("abcdefg"))
        .isInstanceOf(LinkExpiredException.class);
  }

  @Test
  void shouldReloadFromRepositoryAfterEviction() {
    when(repository.findActiveByCode("abcdefg")).thenReturn(Optional.of(link(null)));
    resolver.resolve("abcdefg");

    resolver.evict("abcdefg");
    when(repository.findActiveByCode("abcdefg")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.resolve("abcdefg"))
        .isInstanceOf(LinkNotFoundException.class);
  }

  private static Link link(Instant expiresAt) {
    return new Link(1L, "abcdefg", TARGET, NOW.minusSeconds(3600), expiresAt);
  }
}
