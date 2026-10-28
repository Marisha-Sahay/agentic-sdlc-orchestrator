package io.github.marishasahay.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.marishasahay.shortener.IntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class LinkLifecycleIT extends IntegrationTestSupport {

  private static final String TARGET = "https://example.com/docs?page=1";

  @Test
  void shouldCreateLinkWithGeneratedCodeAndRedirectWith302() throws Exception {
    MvcResult created = postLink("{\"url\": \"" + TARGET + "\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value(matchesPattern("^[0-9A-Za-z]{7}$")))
        .andExpect(jsonPath("$.targetUrl").value(TARGET))
        .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.expiresAt").isEmpty())
        .andReturn();
    String code = jsonField(created, "$.code");

    assertThat(created.getResponse().getHeader("Location"))
        .isEqualTo("http://localhost:8080/api/v1/links/" + code);
    assertThat(jsonField(created, "$.shortUrl")).isEqualTo("http://localhost:8080/" + code);

    mockMvc.perform(get("/" + code))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", TARGET))
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void shouldReturnMetadataForCustomAliasWithExpiry() throws Exception {
    String alias = uniqueAlias();
    postLink("""
        {"url": "%s", "customAlias": "%s", "expiresAt": "2026-06-30T12:00:00Z"}
        """.formatted(TARGET, alias))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value(alias));

    mockMvc.perform(get("/api/v1/links/" + alias))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code").value(alias))
        .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/" + alias))
        .andExpect(jsonPath("$.targetUrl").value(TARGET))
        .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.expiresAt").value("2026-06-30T12:00:00Z"));
  }

  @Test
  void shouldRejectInvalidUrlWith400ProblemDetail() throws Exception {
    postLink("{\"url\": \"https://bank.com@evil.com/login\"}")
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Invalid request"))
        .andExpect(jsonPath("$.detail").value("url must not contain user info"))
        .andExpect(jsonPath("$.errors[0].field").value("url"));
  }

  @Test
  void shouldRejectExpiryInThePastWith400() throws Exception {
    postLink("{\"url\": \"" + TARGET + "\", \"expiresAt\": \"2025-12-31T23:59:59Z\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("expiresAt"));
  }

  @Test
  void shouldRejectDuplicateAliasWith409() throws Exception {
    String alias = uniqueAlias();
    String body = "{\"url\": \"" + TARGET + "\", \"customAlias\": \"" + alias + "\"}";
    postLink(body).andExpect(status().isCreated());

    postLink(body)
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Alias already in use"));
  }

  @Test
  void shouldReturn410GoneOnceLinkHasExpired() throws Exception {
    String alias = uniqueAlias();
    postLink("""
        {"url": "%s", "customAlias": "%s", "expiresAt": "2026-01-01T01:00:00Z"}
        """.formatted(TARGET, alias))
        .andExpect(status().isCreated());

    clock.advance(Duration.ofHours(2));

    mockMvc.perform(get("/" + alias))
        .andExpect(status().isGone())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Link expired"));
    mockMvc.perform(get("/api/v1/links/" + alias))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expiresAt").value("2026-01-01T01:00:00Z"));
  }

  @Test
  void shouldReturn404ForUnknownCode() throws Exception {
    mockMvc.perform(get("/unknown-" + shortId()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Link not found"));
    mockMvc.perform(get("/api/v1/links/unknown-" + shortId()))
        .andExpect(status().isNotFound());
  }

  @Test
  void shouldSoftDeleteLinkThenReturn404AndNeverReuseTheCode() throws Exception {
    String alias = uniqueAlias();
    String body = "{\"url\": \"" + TARGET + "\", \"customAlias\": \"" + alias + "\"}";
    postLink(body).andExpect(status().isCreated());
    mockMvc.perform(get("/" + alias)).andExpect(status().isFound());

    mockMvc.perform(delete("/api/v1/links/" + alias)).andExpect(status().isNoContent());

    mockMvc.perform(get("/" + alias)).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/v1/links/" + alias)).andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/v1/links/" + alias)).andExpect(status().isNotFound());
    postLink(body).andExpect(status().isConflict());
  }

  private static String uniqueAlias() {
    return "it-" + shortId();
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
