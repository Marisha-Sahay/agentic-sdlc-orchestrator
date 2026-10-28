package io.github.marishasahay.shortener.link;

import java.time.Instant;

/**
 * Representation of a link returned by the API.
 *
 * @param code the short code
 * @param shortUrl the full short URL that redirects to the target
 * @param targetUrl the target URL
 * @param createdAt creation instant
 * @param expiresAt expiry instant, or {@code null} if the link never expires
 */
public record LinkResponse(String code, String shortUrl, String targetUrl, Instant createdAt,
    Instant expiresAt) {

  static LinkResponse of(Link link, String shortUrl) {
    return new LinkResponse(link.code(), shortUrl, link.targetUrl(), link.createdAt(),
        link.expiresAt());
  }
}
