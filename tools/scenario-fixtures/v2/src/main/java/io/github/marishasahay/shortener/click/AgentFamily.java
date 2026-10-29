package io.github.marishasahay.shortener.click;

import java.util.List;
import java.util.Locale;

/**
 * Coarse user-agent family stored with a click instead of the user-agent string, which can
 * fingerprint a visitor.
 */
public enum AgentFamily {
  BROWSER,
  BOT,
  OTHER;

  private static final List<String> BOT_MARKERS = List.of("bot", "crawler", "spider", "slurp",
      "curl", "wget", "python-requests", "httpclient", "okhttp", "headless");
  private static final List<String> BROWSER_MARKERS = List.of("mozilla", "chrome", "safari",
      "firefox", "edg", "opera");

  /**
   * Classifies a user-agent header.
   *
   * @param userAgent the header value, possibly null
   * @return the family; bots win over browsers because most bots claim to be Mozilla
   */
  public static AgentFamily of(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return OTHER;
    }
    String ua = userAgent.toLowerCase(Locale.ROOT);
    if (BOT_MARKERS.stream().anyMatch(ua::contains)) {
      return BOT;
    }
    return BROWSER_MARKERS.stream().anyMatch(ua::contains) ? BROWSER : OTHER;
  }
}
