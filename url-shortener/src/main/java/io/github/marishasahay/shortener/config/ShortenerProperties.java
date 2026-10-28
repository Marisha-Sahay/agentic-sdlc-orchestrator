package io.github.marishasahay.shortener.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the shortener, bound from the {@code shortener.*} namespace.
 *
 * @param baseUrl public base URL used to build short URLs; its host is also blocked as a target
 * @param codeLength length of generated short codes
 * @param redirectCache redirect cache settings
 * @param rateLimit rate limit settings for link creation
 * @param trustForwardedHeaders whether to key rate limiting on the first X-Forwarded-For hop
 */
@Validated
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
    @DefaultValue("http://localhost:8080") @NotNull URI baseUrl,
    @DefaultValue("7") @Min(4) @Max(32) int codeLength,
    @DefaultValue @Valid RedirectCache redirectCache,
    @DefaultValue @Valid RateLimit rateLimit,
    @DefaultValue("false") boolean trustForwardedHeaders) {

  /**
   * Settings for the in-memory {@code code -> target URL} cache.
   *
   * @param ttl how long an entry stays cached after it is loaded
   * @param maxSize maximum number of cached entries
   */
  public record RedirectCache(
      @DefaultValue("10m") @NotNull Duration ttl,
      @DefaultValue("10000") @Positive long maxSize) {}

  /**
   * Token-bucket settings: {@code requestsPerPeriod} tokens, refilled continuously over
   * {@code period}.
   *
   * @param requestsPerPeriod bucket capacity and refill amount per period
   * @param period refill period
   * @param maxTrackedClients upper bound on the number of client buckets kept in memory
   */
  public record RateLimit(
      @DefaultValue("20") @Positive int requestsPerPeriod,
      @DefaultValue("1m") @NotNull Duration period,
      @DefaultValue("100000") @Positive long maxTrackedClients) {}
}
