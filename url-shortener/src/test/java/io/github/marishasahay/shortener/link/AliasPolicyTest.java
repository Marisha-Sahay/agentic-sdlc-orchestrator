package io.github.marishasahay.shortener.link;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AliasPolicyTest {

  private final AliasPolicy policy = new AliasPolicy();

  @ParameterizedTest
  @ValueSource(strings = {"abcd", "my-link", "My_Link_2026", "a-b_c-d",
      "abcdefghijklmnopqrstuvwxyz012345"})
  void shouldAcceptWellFormedAliases(String alias) {
    assertThatCode(() -> policy.validate(alias)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"abc", "abcdefghijklmnopqrstuvwxyz0123456", "has space", "dot.ted",
      "slash/es", "query?x", "percent%20", "ümlaut"})
  void shouldRejectMalformedAliases(String alias) {
    assertThatThrownBy(() -> policy.validate(alias))
        .isInstanceOf(InvalidLinkRequestException.class)
        .hasMessage("customAlias must be 4-32 characters of letters, digits, '_' or '-'");
  }

  @ParameterizedTest
  @ValueSource(strings = {"admin", "ADMIN", "actuator", "health", "static", "Metrics", "login"})
  void shouldRejectReservedWordsCaseInsensitively(String alias) {
    assertThatThrownBy(() -> policy.validate(alias))
        .isInstanceOf(InvalidLinkRequestException.class)
        .hasMessage("customAlias is a reserved word");
  }
}
