package io.github.marishasahay.shortener.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.marishasahay.shortener.link.CachedLink;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the redirect cache ({@code code -> target URL and expiry}) and publishes its statistics
 * as Micrometer metrics under {@code cache.*} with tag {@code cache=redirects}.
 */
@Configuration(proxyBeanMethods = false)
public class CacheConfiguration {

  static final String REDIRECT_CACHE_NAME = "redirects";

  @Bean
  public Cache<String, CachedLink> redirectCache(ShortenerProperties properties,
      MeterRegistry meterRegistry) {
    ShortenerProperties.RedirectCache settings = properties.redirectCache();
    Cache<String, CachedLink> cache = Caffeine.newBuilder()
        .expireAfterWrite(settings.ttl())
        .maximumSize(settings.maxSize())
        .recordStats()
        .build();
    return CaffeineCacheMetrics.monitor(meterRegistry, cache, REDIRECT_CACHE_NAME);
  }
}
