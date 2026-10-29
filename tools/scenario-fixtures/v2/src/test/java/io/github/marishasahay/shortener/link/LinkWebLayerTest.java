package io.github.marishasahay.shortener.link;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.marishasahay.shortener.click.ClickRecorder;
import io.github.marishasahay.shortener.config.ShortenerProperties;
import io.github.marishasahay.shortener.ratelimit.RateLimitDecision;
import io.github.marishasahay.shortener.ratelimit.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer tests (no database): routing, status codes, headers, JSON shape and the
 * {@code ProblemDetail} mapping of every error.
 */
@WebMvcTest(controllers = {LinkApiController.class, RedirectController.class})
class LinkWebLayerTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final String TARGET = "https://example.com/page";

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private LinkService linkService;

  @MockitoBean
  private LinkResolver linkResolver;

  @MockitoBean
  private RateLimiter rateLimiter;

  @MockitoBean
  private ClickRecorder clickRecorder;

  @BeforeEach
  void allowAllRequests() {
    when(rateLimiter.tryAcquire(anyString()))
        .thenReturn(new RateLimitDecision(true, Duration.ZERO));
  }

  @Test
  void shouldCreateLinkAndReturn201WithLocationAndBody() throws Exception {
    Instant expiry = Instant.parse("2026-02-01T00:00:00Z");
    when(linkService.create(TARGET, "my-alias", expiry))
        .thenReturn(new Link(1L, "my-alias", TARGET, CREATED, expiry));

    mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"url": "%s", "customAlias": "my-alias", "expiresAt": "2026-02-01T00:00:00Z"}
                """.formatted(TARGET)))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost:8080/api/v1/links/my-alias"))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code").value("my-alias"))
        .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/my-alias"))
        .andExpect(jsonPath("$.targetUrl").value(TARGET))
        .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.expiresAt").value("2026-02-01T00:00:00Z"));
  }

  @Test
  void shouldRenderMissingExpiryAsNull() throws Exception {
    when(linkService.create(TARGET, null, null))
        .thenReturn(new Link(1L, "abcdefg", TARGET, CREATED, null));

    mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\": \"" + TARGET + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.expiresAt").isEmpty());
  }

  @Test
  void shouldReturn400ProblemWithFieldErrorsWhenUrlIsMissing() throws Exception {
    mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
            .content("{\"customAlias\": \"my-alias\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Invalid request"))
        .andExpect(jsonPath("$.instance").value("/api/v1/links"))
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(jsonPath("$.errors[0].field").value("url"))
        .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));
    verifyNoInteractions(linkService);
  }

  @Test
  void shouldReturn400ProblemForPolicyViolations() throws Exception {
    when(linkService.create(eq("ftp://example.com"), isNull(), isNull()))
        .thenThrow(new InvalidLinkRequestException("url", "must be an absolute http or https URL"));

    mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\": \"ftp://example.com\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("url must be an absolute http or https URL"))
        .andExpect(jsonPath("$.errors[0].field").value("url"))
        .andExpect(jsonPath("$.errors[0].message").value("must be an absolute http or https URL"));
  }

  @Test
  void shouldReturn400ProblemForMalformedJson() throws Exception {
    mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\": "))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void shouldReturn400ProblemForUnparseableExpiry() throws Exception {
    mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\": \"" + TARGET + "\", \"expiresAt\": \"tomorrow\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void shouldReturn415ProblemForNonJsonBody() throws Exception {
    mockMvc.perform(post("/api/v1/links").contentType(MediaType.TEXT_PLAIN).content(TARGET))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void shouldReturn409ProblemWhenAliasIsTaken() throws Exception {
    when(linkService.create(any(), eq("taken"), any()))
        .thenThrow(new AliasAlreadyExistsException("taken"));

    mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\": \"" + TARGET + "\", \"customAlias\": \"taken\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Alias already in use"));
  }

  @Test
  void shouldReturn500ProblemWhenNoUniqueCodeCanBeGenerated() throws Exception {
    when(linkService.create(any(), any(), any())).thenThrow(new CodeGenerationException(5));

    mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\": \"" + TARGET + "\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Short code generation failed"));
  }

  @Test
  void shouldReturnGeneric500ProblemForUnexpectedErrors() throws Exception {
    when(linkService.get("boom")).thenThrow(new IllegalStateException("secret internals"));

    mockMvc.perform(get("/api/v1/links/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
  }

  @Test
  void shouldReturn429ProblemWithRetryAfterWhenRateLimited() throws Exception {
    when(rateLimiter.tryAcquire("127.0.0.1"))
        .thenReturn(new RateLimitDecision(false, Duration.ofMillis(2_500)));

    mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\": \"" + TARGET + "\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "3"))
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Too many requests"));
    verifyNoInteractions(linkService);
  }

  @Test
  void shouldNotRateLimitReads() throws Exception {
    when(linkService.get("abcdefg")).thenReturn(new Link(1L, "abcdefg", TARGET, CREATED, null));

    mockMvc.perform(get("/api/v1/links/abcdefg")).andExpect(status().isOk());

    verifyNoInteractions(rateLimiter);
  }

  @Test
  void shouldReturnLinkMetadata() throws Exception {
    when(linkService.get("abcdefg")).thenReturn(new Link(1L, "abcdefg", TARGET, CREATED, null));

    mockMvc.perform(get("/api/v1/links/abcdefg"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("abcdefg"))
        .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/abcdefg"))
        .andExpect(jsonPath("$.targetUrl").value(TARGET));
  }

  @Test
  void shouldReturn404ProblemForUnknownLinkMetadata() throws Exception {
    when(linkService.get("missing")).thenThrow(new LinkNotFoundException("missing"));

    mockMvc.perform(get("/api/v1/links/missing"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Link not found"))
        .andExpect(jsonPath("$.instance").value("/api/v1/links/missing"));
  }

  @Test
  void shouldDeleteLinkAndReturn204() throws Exception {
    mockMvc.perform(delete("/api/v1/links/abcdefg")).andExpect(status().isNoContent());

    verify(linkService).delete("abcdefg");
  }

  @Test
  void shouldReturn404ProblemWhenDeletingUnknownLink() throws Exception {
    doThrow(new LinkNotFoundException("missing")).when(linkService).delete("missing");

    mockMvc.perform(delete("/api/v1/links/missing"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void shouldRedirectWith302AndNoStore() throws Exception {
    when(linkResolver.resolve("abcdefg")).thenReturn(TARGET);

    mockMvc.perform(get("/abcdefg"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", TARGET))
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void shouldReturn404ProblemWhenRedirectingUnknownCode() throws Exception {
    when(linkResolver.resolve("missing")).thenThrow(new LinkNotFoundException("missing"));

    mockMvc.perform(get("/missing"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void shouldReturn410ProblemWhenRedirectingExpiredCode() throws Exception {
    when(linkResolver.resolve("expired")).thenThrow(new LinkExpiredException("expired"));

    mockMvc.perform(get("/expired"))
        .andExpect(status().isGone())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Link expired"));
  }

  @Test
  void shouldNotRouteMalformedCodesToTheRedirectHandler() throws Exception {
    mockMvc.perform(get("/abc")).andExpect(status().isNotFound());
    mockMvc.perform(get("/" + "a".repeat(33))).andExpect(status().isNotFound());
    mockMvc.perform(get("/bad.code"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

    verifyNoInteractions(linkResolver);
  }

  /** {@code @WebMvcTest} does not apply {@code @ConfigurationPropertiesScan}; bind explicitly. */
  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ShortenerProperties.class)
  static class PropertiesConfiguration {}
}
