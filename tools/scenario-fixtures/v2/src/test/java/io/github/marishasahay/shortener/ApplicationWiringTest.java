package io.github.marishasahay.shortener;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.marishasahay.shortener.click.ClickRepository;
import io.github.marishasahay.shortener.link.Link;
import io.github.marishasahay.shortener.link.LinkRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boots the complete application except the database (DataSource and Flyway auto-configuration
 * excluded, repository mocked), so the real wiring of configuration, cache, rate limiter and
 * actuator is verified in the fast suite without Docker.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
    + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@AutoConfigureMockMvc
class ApplicationWiringTest {

  private static final String TARGET = "https://example.com/wired";

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private LinkRepository repository;

  @MockitoBean
  private ClickRepository clickRepository;

  @Test
  void shouldCreateLinkWithConfiguredCodeGenerator() throws Exception {
    when(repository.insertIfAbsent(anyString(), eq(TARGET), any(), any()))
        .thenAnswer(call -> Optional.of(new Link(1L, call.getArgument(0), TARGET,
            call.getArgument(2), call.getArgument(3))));

    mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\": \"" + TARGET + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value(matchesPattern("^[0-9A-Za-z]{7}$")));
  }

  @Test
  void shouldRedirectThroughTheRealResolverAndCache() throws Exception {
    when(repository.findActiveByCode("wired01")).thenReturn(Optional.of(
        new Link(1L, "wired01", TARGET, Instant.parse("2026-01-01T00:00:00Z"), null)));

    mockMvc.perform(get("/wired01")).andExpect(status().isFound())
        .andExpect(header().string("Location", TARGET));
    mockMvc.perform(get("/wired01")).andExpect(status().isFound());

    verify(repository, times(1)).findActiveByCode("wired01");
  }

  @Test
  void shouldServeSwaggerUiConfiguredWithTheCheckedInContract() throws Exception {
    mockMvc.perform(get("/swagger-ui.html"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", endsWith("/swagger-ui/index.html")));
    mockMvc.perform(get("/swagger-ui/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("swagger-ui")));
    mockMvc.perform(get("/v3/api-docs/swagger-config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("/openapi.yaml"));
  }

  @Test
  void shouldServeTheApiContractUnchangedAndGenerateNoSpecFromCode() throws Exception {
    String contract = Files.readString(Path.of("api/openapi.yaml"));

    mockMvc.perform(get("/openapi.yaml"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/yaml"))
        .andExpect(content().string(contract));
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
  }

  @Test
  void shouldServeActuatorRootDespiteMatchingTheRedirectPattern() throws Exception {
    mockMvc.perform(get("/actuator"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$._links.health").exists());
  }

  @Test
  void shouldExposeRedirectCacheMetrics() throws Exception {
    mockMvc.perform(get("/actuator/metrics/cache.gets").param("tag", "cache:redirects"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("cache.gets"));
  }

  @Test
  void shouldExposeApplicationAndBuildInfo() throws Exception {
    mockMvc.perform(get("/actuator/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.app.name").value("url-shortener"))
        .andExpect(jsonPath("$.build.artifact").value("url-shortener"));
  }
}
