package io.github.marishasahay.shortener.click;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

/**
 * One successful redirect, reduced to what analytics needs. No IP address, no user-agent string
 * and no referrer path or query (which can carry personal data) are kept.
 *
 * @param code the short code that was followed
 * @param clickedAt when the redirect was served
 * @param referrerHost host of the {@code Referer} header, or null
 * @param agentFamily coarse user-agent family
 */
public record ClickEvent(String code, Instant clickedAt, String referrerHost,
    AgentFamily agentFamily) {

  static final int MAX_HOST_LENGTH = 255;

  /**
   * Builds an event from raw request headers.
   *
   * @param code the short code
   * @param clickedAt when the redirect was served
   * @param referer the {@code Referer} header, possibly null
   * @param userAgent the {@code User-Agent} header, possibly null
   * @return the reduced event
   */
  public static ClickEvent of(String code, Instant clickedAt, String referer, String userAgent) {
    return new ClickEvent(code, clickedAt, referrerHost(referer), AgentFamily.of(userAgent));
  }

  /**
   * Host part of a referrer URL.
   *
   * @param referer header value
   * @return lower-case host, or null if absent or not an absolute http(s) URL
   */
  static String referrerHost(String referer) {
    if (referer == null || referer.isBlank()) {
      return null;
    }
    try {
      URI uri = URI.create(referer.strip());
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (host == null || scheme == null
          || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
        return null;
      }
      String normalised = host.toLowerCase(Locale.ROOT);
      return normalised.length() > MAX_HOST_LENGTH ? null : normalised;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
