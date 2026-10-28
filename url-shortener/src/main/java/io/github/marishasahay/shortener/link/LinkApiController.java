package io.github.marishasahay.shortener.link;

import io.github.marishasahay.shortener.config.ShortenerProperties;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management API for short links.
 */
@RestController
@RequestMapping(LinkApiController.BASE_PATH)
public class LinkApiController {

  public static final String BASE_PATH = "/api/v1/links";

  private final LinkService linkService;
  private final String baseUrl;

  public LinkApiController(LinkService linkService, ShortenerProperties properties) {
    this.linkService = linkService;
    this.baseUrl = stripTrailingSlash(properties.baseUrl().toString());
  }

  /**
   * Creates a short link.
   *
   * @param request the link to create
   * @return {@code 201 Created} with the link and a {@code Location} header for its metadata
   */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
    Link link = linkService.create(request.url(), request.customAlias(), request.expiresAt());
    URI location = URI.create(baseUrl + BASE_PATH + "/" + link.code());
    return ResponseEntity.created(location).body(toResponse(link));
  }

  /**
   * Returns a link's metadata.
   *
   * @param code the short code
   * @return the link
   */
  @GetMapping(path = "/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
  public LinkResponse get(@PathVariable String code) {
    return toResponse(linkService.get(code));
  }

  /**
   * Soft-deletes a link.
   *
   * @param code the short code
   * @return {@code 204 No Content}
   */
  @DeleteMapping("/{code}")
  public ResponseEntity<Void> delete(@PathVariable String code) {
    linkService.delete(code);
    return ResponseEntity.noContent().build();
  }

  private LinkResponse toResponse(Link link) {
    return LinkResponse.of(link, baseUrl + "/" + link.code());
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
