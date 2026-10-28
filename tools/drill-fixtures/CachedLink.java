package io.github.marishasahay.shortener.link;

import java.time.Instant;

/**
 * What the redirect cache holds for a code: the target and the expiry it was loaded with, so the
 * expiry can be re-checked on every cache hit (issue #1).
 *
 * @param targetUrl where the code redirects to
 * @param expiresAt expiry instant, or {@code null} if the link never expires
 */
public record CachedLink(String targetUrl, Instant expiresAt) {

  static CachedLink of(Link link) {
    return new CachedLink(link.targetUrl(), link.expiresAt());
  }

  /**
   * Whether the cached link has expired at the given instant.
   *
   * @param now the instant to evaluate against
   * @return {@code true} if an expiry is set and {@code now} is at or after it
   */
  public boolean isExpiredAt(Instant now) {
    return expiresAt != null && !now.isBefore(expiresAt);
  }
}
