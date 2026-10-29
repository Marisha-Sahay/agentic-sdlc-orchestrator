package io.github.marishasahay.shortener.click;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class ClickEventTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(nullValues = "null", value = {
      "https://News.Example.com/a/b?user=alice#x, news.example.com",
      "http://example.com, example.com",
      "android-app://com.example, null",
      "ftp://example.com/file, null",
      "not a url, null",
      "/relative/path, null"})
  void keepsOnlyTheReferrerHost(String referer, String host) {
    assertThat(ClickEvent.referrerHost(referer)).isEqualTo(host);
  }

  @ParameterizedTest
  @NullAndEmptySource
  void noReferrerMeansNoHost(String referer) {
    assertThat(ClickEvent.referrerHost(referer)).isNull();
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(nullValues = "null", value = {
      "Mozilla/5.0 (Macintosh) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15, BROWSER",
      "Mozilla/5.0 (Windows NT 10.0) Chrome/120.0 Safari/537.36 Edg/120.0, BROWSER",
      "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html), BOT",
      "curl/8.4.0, BOT",
      "python-requests/2.31, BOT",
      "MyCustomClient/1.0, OTHER",
      "null, OTHER",
      "'', OTHER"})
  void reducesTheUserAgentToAFamily(String userAgent, AgentFamily family) {
    assertThat(AgentFamily.of(userAgent)).isEqualTo(family);
  }

  @org.junit.jupiter.api.Test
  void anEventCarriesNoRawHeaders() {
    ClickEvent event = ClickEvent.of("abcd", Instant.EPOCH,
        "https://mail.example.com/inbox?id=42", "Mozilla/5.0 Firefox/121.0");

    assertThat(event).isEqualTo(new ClickEvent("abcd", Instant.EPOCH, "mail.example.com",
        AgentFamily.BROWSER));
  }
}
