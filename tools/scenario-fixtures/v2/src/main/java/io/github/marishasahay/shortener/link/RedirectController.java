package io.github.marishasahay.shortener.link;

import io.github.marishasahay.shortener.click.ClickRecorder;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public redirect endpoint. Uses {@code 302 Found} with {@code Cache-Control: no-store} so
 * that browsers ask the service on every visit instead of caching the redirect permanently.
 * The path pattern only matches valid codes, so it never shadows {@code /api/**} or
 * {@code /actuator/**}. Each successful redirect is offered to the {@link ClickRecorder}, which
 * never blocks; failed lookups (404, 410) are not counted.
 */
@RestController
public class RedirectController {

  private final LinkResolver linkResolver;
  private final ClickRecorder clickRecorder;

  public RedirectController(LinkResolver linkResolver, ClickRecorder clickRecorder) {
    this.linkResolver = linkResolver;
    this.clickRecorder = clickRecorder;
  }

  /**
   * Redirects to the target URL of a code.
   *
   * @param code the short code
   * @param referer the {@code Referer} header; only its host is recorded
   * @param userAgent the {@code User-Agent} header; only its family is recorded
   * @return {@code 302 Found} with the target in the {@code Location} header
   */
  @GetMapping("/{code:[A-Za-z0-9_-]{4,32}}")
  public ResponseEntity<Void> redirect(@PathVariable String code,
      @RequestHeader(name = HttpHeaders.REFERER, required = false) String referer,
      @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent) {
    String targetUrl = linkResolver.resolve(code);
    clickRecorder.record(code, referer, userAgent);
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(targetUrl))
        .cacheControl(CacheControl.noStore())
        .build();
  }
}
