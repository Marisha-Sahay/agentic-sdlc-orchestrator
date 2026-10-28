package io.github.marishasahay.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LinkServiceTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00.123456789Z");
  private static final Instant NOW_MICROS = Instant.parse("2026-01-01T12:00:00.123456Z");
  private static final String TARGET = "https://example.com/page";

  private final LinkRepository repository = mock(LinkRepository.class);
  private final CodeGenerator codeGenerator = mock(CodeGenerator.class);
  private final LinkResolver linkResolver = mock(LinkResolver.class);
  private final LinkService service = new LinkService(repository, codeGenerator,
      new TargetUrlPolicy("sho.rt"), new AliasPolicy(), linkResolver,
      Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void shouldCreateLinkWithGeneratedCode() {
    when(codeGenerator.generate()).thenReturn("aB3dE5g");
    when(repository.insertIfAbsent("aB3dE5g", TARGET, NOW_MICROS, null))
        .thenReturn(Optional.of(link("aB3dE5g", null)));

    Link created = service.create(TARGET, null, null);

    assertThat(created.code()).isEqualTo("aB3dE5g");
    assertThat(created.targetUrl()).isEqualTo(TARGET);
  }

  @Test
  void shouldRetryWithNewCodeWhenGeneratedCodeCollides() {
    when(codeGenerator.generate()).thenReturn("taken01", "taken02", "free003");
    when(repository.insertIfAbsent(eq("taken01"), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(repository.insertIfAbsent(eq("taken02"), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(repository.insertIfAbsent(eq("free003"), any(), any(), any()))
        .thenReturn(Optional.of(link("free003", null)));

    Link created = service.create(TARGET, null, null);

    assertThat(created.code()).isEqualTo("free003");
    verify(codeGenerator, times(3)).generate();
  }

  @Test
  void shouldFailWithClearErrorAfterFiveCollisions() {
    when(codeGenerator.generate()).thenReturn("collide");
    when(repository.insertIfAbsent(anyString(), any(), any(), any()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(TARGET, null, null))
        .isInstanceOf(CodeGenerationException.class)
        .hasMessage("Could not generate a unique short code after 5 attempts");
    verify(repository, times(LinkService.MAX_CODE_ATTEMPTS))
        .insertIfAbsent(anyString(), any(), any(), any());
  }

  @Test
  void shouldUseCustomAliasAsCodeWithoutGenerating() {
    when(repository.insertIfAbsent("my-alias", TARGET, NOW_MICROS, null))
        .thenReturn(Optional.of(link("my-alias", null)));

    Link created = service.create(TARGET, "my-alias", null);

    assertThat(created.code()).isEqualTo("my-alias");
    verifyNoInteractions(codeGenerator);
  }

  @Test
  void shouldRejectCustomAliasThatIsAlreadyTakenWithoutRetrying() {
    when(repository.insertIfAbsent(eq("my-alias"), any(), any(), any()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(TARGET, "my-alias", null))
        .isInstanceOf(AliasAlreadyExistsException.class)
        .hasMessageContaining("my-alias");
    verify(repository, times(1)).insertIfAbsent(anyString(), any(), any(), any());
    verifyNoInteractions(codeGenerator);
  }

  @Test
  void shouldRejectInvalidAliasBeforeTouchingTheDatabase() {
    assertThatThrownBy(() -> service.create(TARGET, "api", null))
        .isInstanceOf(InvalidLinkRequestException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void shouldRejectInvalidTargetUrlBeforeTouchingTheDatabase() {
    assertThatThrownBy(() -> service.create("https://sho.rt/abc1234", null, null))
        .isInstanceOf(InvalidLinkRequestException.class)
        .hasMessage("url must not point at this shortener");
    verifyNoInteractions(repository);
  }

  @Test
  void shouldRejectExpiryThatIsNotInTheFuture() {
    assertThatThrownBy(() -> service.create(TARGET, null, NOW.minusSeconds(1)))
        .isInstanceOfSatisfying(InvalidLinkRequestException.class,
            ex -> assertThat(ex.field()).isEqualTo("expiresAt"));
    assertThatThrownBy(() -> service.create(TARGET, null, NOW))
        .isInstanceOf(InvalidLinkRequestException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void shouldStoreTimestampsAtDatabasePrecision() {
    Instant expiry = NOW.plus(Duration.ofDays(1));
    Instant expiryMicros = NOW_MICROS.plus(Duration.ofDays(1));
    when(codeGenerator.generate()).thenReturn("abcdefg");
    when(repository.insertIfAbsent("abcdefg", TARGET, NOW_MICROS, expiryMicros))
        .thenReturn(Optional.of(link("abcdefg", expiryMicros)));

    Link created = service.create(TARGET, null, expiry);

    assertThat(created.createdAt()).isEqualTo(NOW_MICROS);
    assertThat(created.expiresAt()).isEqualTo(expiryMicros);
  }

  @Test
  void shouldReturnActiveLinkMetadata() {
    when(repository.findActiveByCode("abcdefg")).thenReturn(Optional.of(link("abcdefg", null)));

    assertThat(service.get("abcdefg").targetUrl()).isEqualTo(TARGET);
  }

  @Test
  void shouldThrowNotFoundForUnknownCode() {
    when(repository.findActiveByCode("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get("missing")).isInstanceOf(LinkNotFoundException.class);
  }

  @Test
  void shouldSoftDeleteAndEvictFromRedirectCache() {
    when(repository.softDelete("abcdefg", NOW_MICROS)).thenReturn(true);

    service.delete("abcdefg");

    verify(repository).softDelete("abcdefg", NOW_MICROS);
    verify(linkResolver).evict("abcdefg");
  }

  @Test
  void shouldThrowNotFoundWhenDeletingUnknownOrAlreadyDeletedLink() {
    when(repository.softDelete(eq("missing"), any())).thenReturn(false);

    assertThatThrownBy(() -> service.delete("missing")).isInstanceOf(LinkNotFoundException.class);
  }

  private static Link link(String code, Instant expiresAt) {
    return new Link(1L, code, TARGET, NOW_MICROS, expiresAt);
  }
}
