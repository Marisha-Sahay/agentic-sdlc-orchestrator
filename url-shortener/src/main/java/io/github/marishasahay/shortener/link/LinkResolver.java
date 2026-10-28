package io.github.marishasahay.shortener.link;

import com.github.benmanes.caffeine.cache.Cache;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Resolves short codes to target URLs on the redirect hot path, backed by a Caffeine cache of
 * {@code code -> target URL} in front of the database.
 */
@Component
public class LinkResolver {

  private final Cache<String, String> redirectCache;
  private final LinkRepository repository;
  private final Clock clock;

  /**
   * Creates the resolver.
   *
   * @param redirectCache the bounded redirect cache
   * @param repository link persistence
   * @param clock time source
   */
  public LinkResolver(Cache<String, String> redirectCache, LinkRepository repository,
      Clock clock) {
    this.redirectCache = redirectCache;
    this.repository = repository;
    this.clock = clock;
  }

  /**
   * Resolves a code to its target URL.
   *
   * @param code the short code
   * @return the target URL
   * @throws LinkNotFoundException if no active link exists for the code
   * @throws LinkExpiredException if the link has expired
   */
  public String resolve(String code) {
    return redirectCache.get(code, this::load);
  }

  /**
   * Removes a code from the cache, e.g. after the link was deleted.
   *
   * @param code the short code
   */
  public void evict(String code) {
    redirectCache.invalidate(code);
  }

  private String load(String code) {
    Link link = repository.findActiveByCode(code)
        .orElseThrow(() -> new LinkNotFoundException(code));
    if (link.isExpiredAt(clock.instant())) {
      throw new LinkExpiredException(code);
    }
    return link.targetUrl();
  }
}
