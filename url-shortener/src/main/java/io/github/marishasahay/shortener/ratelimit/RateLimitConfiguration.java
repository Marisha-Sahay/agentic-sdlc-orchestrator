package io.github.marishasahay.shortener.ratelimit;

import io.github.marishasahay.shortener.config.ShortenerProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the application-wide {@link RateLimiter}.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfiguration {

  @Bean
  public RateLimiter rateLimiter(ShortenerProperties properties, Clock clock) {
    return new RateLimiter(properties.rateLimit(), clock);
  }
}
