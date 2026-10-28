package io.github.marishasahay.shortener.link;

import java.time.Instant;

/**
 * A persisted short link.
 *
 * @param id surrogate key
 * @param code the short code (generated or custom alias), unique forever
 * @param targetUrl where the short code redirects to
 * @param createdAt creation instant
 * @param expiresAt optional expiry instant; {@code null} means the link never expires
 */
public record Link(long id, String code, String targetUrl, Instant createdAt, Instant expiresAt) {

  /**
   * Whether the link has expired at the given instant.
   *
   * @param now the instant to evaluate against
   * @return {@code true} if an expiry is set and {@code now} is at or after it
   */
  public boolean isExpiredAt(Instant now) {
    return expiresAt != null && !now.isBefore(expiresAt);
  }
}
