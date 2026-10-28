package io.github.marishasahay.shortener.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientKeyResolverTest {

  private static final String REMOTE_ADDR = "10.0.0.5";

  @Test
  void shouldIgnoreForwardedHeaderUnlessTrusted() {
    MockHttpServletRequest request = request("203.0.113.7");

    assertThat(new ClientKeyResolver(false).resolve(request)).isEqualTo(REMOTE_ADDR);
  }

  @Test
  void shouldUseFirstForwardedHopWhenTrusted() {
    MockHttpServletRequest request = request(" 203.0.113.7 , 198.51.100.2, 10.0.0.1");

    assertThat(new ClientKeyResolver(true).resolve(request)).isEqualTo("203.0.113.7");
  }

  @Test
  void shouldFallBackToRemoteAddressWhenTrustedHeaderIsAbsent() {
    MockHttpServletRequest request = request(null);

    assertThat(new ClientKeyResolver(true).resolve(request)).isEqualTo(REMOTE_ADDR);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", " , 203.0.113.7"})
  void shouldFallBackToRemoteAddressWhenFirstForwardedHopIsEmpty(String header) {
    MockHttpServletRequest request = request(header);

    assertThat(new ClientKeyResolver(true).resolve(request)).isEqualTo(REMOTE_ADDR);
  }

  private static MockHttpServletRequest request(String forwardedFor) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/links");
    request.setRemoteAddr(REMOTE_ADDR);
    if (forwardedFor != null) {
      request.addHeader(ClientKeyResolver.FORWARDED_FOR, forwardedFor);
    }
    return request;
  }
}
