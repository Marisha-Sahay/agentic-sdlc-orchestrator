package io.github.marishasahay.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.shortener.IntegrationTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LinkRepositoryIT extends IntegrationTestSupport {

  private static final Instant CREATED = Instant.parse("2026-03-01T10:15:30.123456Z");
  private static final Instant EXPIRES = Instant.parse("2026-04-01T00:00:00Z");

  @Autowired
  private LinkRepository repository;

  @Test
  void shouldInsertAndReadBackLinkWithTimestamps() {
    String code = uniqueCode();

    Link inserted = repository.insertIfAbsent(code, "https://example.com", CREATED, EXPIRES)
        .orElseThrow();

    assertThat(inserted.id()).isPositive();
    assertThat(repository.findActiveByCode(code)).contains(inserted);
  }

  @Test
  void shouldStoreNullExpiry() {
    String code = uniqueCode();
    repository.insertIfAbsent(code, "https://example.com", CREATED, null);

    assertThat(repository.findActiveByCode(code)).hasValueSatisfying(
        link -> assertThat(link.expiresAt()).isNull());
  }

  @Test
  void shouldReturnEmptyInsteadOfFailingWhenCodeExists() {
    String code = uniqueCode();
    repository.insertIfAbsent(code, "https://example.com/first", CREATED, null);

    assertThat(repository.insertIfAbsent(code, "https://example.com/second", CREATED, null))
        .isEmpty();
    assertThat(repository.findActiveByCode(code)).hasValueSatisfying(
        link -> assertThat(link.targetUrl()).isEqualTo("https://example.com/first"));
  }

  @Test
  void shouldHideSoftDeletedLinksButKeepTheirCodeReserved() {
    String code = uniqueCode();
    repository.insertIfAbsent(code, "https://example.com", CREATED, null);

    assertThat(repository.softDelete(code, CREATED.plusSeconds(60))).isTrue();

    assertThat(repository.findActiveByCode(code)).isEmpty();
    assertThat(repository.softDelete(code, CREATED.plusSeconds(120))).isFalse();
    assertThat(repository.insertIfAbsent(code, "https://example.com", CREATED, null)).isEmpty();
  }

  @Test
  void shouldReportNothingDeletedForUnknownCode() {
    assertThat(repository.softDelete(uniqueCode(), CREATED)).isFalse();
  }

  private static String uniqueCode() {
    return "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }
}
