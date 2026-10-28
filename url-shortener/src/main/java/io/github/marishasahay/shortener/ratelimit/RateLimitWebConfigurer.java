package io.github.marishasahay.shortener.ratelimit;

import io.github.marishasahay.shortener.config.ShortenerProperties;
import io.github.marishasahay.shortener.link.LinkApiController;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the rate-limit interceptor for link creation only ({@code POST /api/v1/links}).
 */
@Component
public class RateLimitWebConfigurer implements WebMvcConfigurer {

  private final RateLimiter rateLimiter;
  private final boolean trustForwardedHeaders;

  public RateLimitWebConfigurer(RateLimiter rateLimiter, ShortenerProperties properties) {
    this.rateLimiter = rateLimiter;
    this.trustForwardedHeaders = properties.trustForwardedHeaders();
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    ClientKeyResolver clientKeyResolver = new ClientKeyResolver(trustForwardedHeaders);
    registry.addInterceptor(new RateLimitInterceptor(rateLimiter, clientKeyResolver))
        .addPathPatterns(LinkApiController.BASE_PATH);
  }
}
