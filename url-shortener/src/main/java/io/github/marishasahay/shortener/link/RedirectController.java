package io.github.marishasahay.shortener.link;

import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public redirect endpoint. Uses {@code 302 Found} with {@code Cache-Control: no-store} so
 * that browsers ask the service on every visit instead of caching the redirect permanently.
 * The path pattern only matches valid codes, so it never shadows {@code /api/**} or
 * {@code /actuator/**}.
 */
@RestController
public class RedirectController {

  private final LinkResolver linkResolver;

  public RedirectController(LinkResolver linkResolver) {
    this.linkResolver = linkResolver;
  }

  /**
   * Redirects to the target URL of a code.
   *
   * @param code the short code
   * @return {@code 302 Found} with the target in the {@code Location} header
   */
  @GetMapping("/{code:[A-Za-z0-9_-]{4,32}}")
  public ResponseEntity<Void> redirect(@PathVariable String code) {
    String targetUrl = linkResolver.resolve(code);
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(targetUrl))
        .cacheControl(CacheControl.noStore())
        .build();
  }
}
