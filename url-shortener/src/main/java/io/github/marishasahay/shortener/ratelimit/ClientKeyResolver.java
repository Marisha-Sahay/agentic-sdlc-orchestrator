package io.github.marishasahay.shortener.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Derives the rate-limit key (client IP) from a request. {@code X-Forwarded-For} is only honoured
 * when explicitly trusted, because any client can set it to evade the limit.
 *
 * @param trustForwardedHeaders whether to use the first {@code X-Forwarded-For} hop
 */
public record ClientKeyResolver(boolean trustForwardedHeaders) {

  static final String FORWARDED_FOR = "X-Forwarded-For";

  /**
   * Resolves the client key.
   *
   * @param request the incoming request
   * @return the first forwarded hop if trusted and present, otherwise the remote address
   */
  public String resolve(HttpServletRequest request) {
    if (trustForwardedHeaders) {
      String forwardedFor = request.getHeader(FORWARDED_FOR);
      if (forwardedFor != null) {
        String firstHop = forwardedFor.split(",", 2)[0].strip();
        if (!firstHop.isEmpty()) {
          return firstHop;
        }
      }
    }
    return request.getRemoteAddr();
  }
}
