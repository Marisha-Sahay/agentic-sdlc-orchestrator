package io.github.marishasahay.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TargetUrlPolicyTest {

  private final TargetUrlPolicy policy = new TargetUrlPolicy("localhost");

  static Stream<Arguments> acceptedUrls() {
    return Stream.of(
        Arguments.of("https://example.com", "https://example.com"),
        Arguments.of("http://example.com/path?q=1#frag", "http://example.com/path?q=1#frag"),
        Arguments.of("HTTPS://EXAMPLE.COM/upper", "HTTPS://EXAMPLE.COM/upper"),
        Arguments.of("https://sub.example.co.uk:8443/x", "https://sub.example.co.uk:8443/x"),
        Arguments.of("http://[2001:db8::1]/ipv6", "http://[2001:db8::1]/ipv6"),
        Arguments.of("  https://example.com/trimmed  ", "https://example.com/trimmed"));
  }

  static Stream<Arguments> rejectedUrls() {
    return Stream.of(
        Arguments.of("ftp://example.com/file", "must be an absolute http or https URL"),
        Arguments.of("javascript:alert(1)", "must be an absolute http or https URL"),
        Arguments.of("mailto:someone@example.com", "must be an absolute http or https URL"),
        Arguments.of("example.com/no-scheme", "must be an absolute http or https URL"),
        Arguments.of("//example.com/scheme-relative", "must be an absolute http or https URL"),
        Arguments.of("https:///no-host", "must contain a valid host"),
        Arguments.of("https:opaque.example.com", "must contain a valid host"),
        Arguments.of("https://bank.com@evil.com", "must not contain user info"),
        Arguments.of("https://user:secret@example.com/", "must not contain user info"),
        Arguments.of("http://localhost:8080/abc1234", "must not point at this shortener"),
        Arguments.of("https://LOCALHOST/abc1234", "must not point at this shortener"),
        Arguments.of("https://exa mple.com", "is not a valid URL"),
        Arguments.of("https://example.com/" + "a".repeat(2048), "must be at most 2048 characters"));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("acceptedUrls")
  void shouldAcceptValidHttpUrls(String url, String expected) {
    assertThat(policy.validate(url)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "[{index}] {0} -> {1}")
  @MethodSource("rejectedUrls")
  void shouldRejectUrlsViolatingThePolicy(String url, String reason) {
    assertThatThrownBy(() -> policy.validate(url))
        .isInstanceOfSatisfying(InvalidLinkRequestException.class, ex -> {
          assertThat(ex.field()).isEqualTo("url");
          assertThat(ex.reason()).isEqualTo(reason);
        });
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\t"})
  void shouldRejectBlankUrls(String url) {
    assertThatThrownBy(() -> policy.validate(url))
        .isInstanceOf(InvalidLinkRequestException.class)
        .hasMessage("url must not be blank");
  }

  @Test
  void shouldAcceptUrlOfExactlyMaximumLength() {
    String prefix = "https://example.com/";
    String url = prefix + "a".repeat(TargetUrlPolicy.MAX_LENGTH - prefix.length());

    assertThat(policy.validate(url)).hasSize(TargetUrlPolicy.MAX_LENGTH);
  }

  @Test
  void shouldNotBlockAnyHostWhenOwnHostIsUnknown() {
    TargetUrlPolicy withoutOwnHost = new TargetUrlPolicy(null);

    assertThat(withoutOwnHost.validate("http://localhost:8080/x"))
        .isEqualTo("http://localhost:8080/x");
  }
}
