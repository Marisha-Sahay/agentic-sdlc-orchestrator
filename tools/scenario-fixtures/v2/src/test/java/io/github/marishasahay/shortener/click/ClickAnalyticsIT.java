package io.github.marishasahay.shortener.click;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.marishasahay.shortener.IntegrationTestSupport;
import io.github.marishasahay.shortener.config.ShortenerProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

class ClickAnalyticsIT extends IntegrationTestSupport {

  private static final String CHROME = "Mozilla/5.0 (X11; Linux x86_64) Chrome/120.0 Safari/537";

  @Autowired
  private JdbcClient jdbc;

  @Autowired
  private ClickRepository clickRepository;

  @Autowired
  private ShortenerProperties properties;

  private String createLink(String expiresAt) throws Exception {
    String alias = "c-" + UUID.randomUUID().toString().substring(0, 12);
    postLink("""
        {"url": "https://example.com/campaign", "customAlias": "%s"%s}
        """.formatted(alias, expiresAt == null ? "" : ", \"expiresAt\": \"" + expiresAt + "\""))
        .andExpect(status().isCreated());
    return alias;
  }

  private ResultActions follow(String code, String referer, String userAgent) throws Exception {
    return mockMvc.perform(get("/" + code).header("Referer", referer)
        .header("User-Agent", userAgent));
  }

  @Test
  void reportsTotalsLastClickAndZeroFilledDailyCountsOfSuccessfulRedirects() throws Exception {
    String code = createLink(null);
    follow(code, "https://news.example.com/story?id=1", CHROME).andExpect(status().isFound());
    follow(code, "https://news.example.com/other", CHROME).andExpect(status().isFound());
    clock.advance(Duration.ofDays(2));
    follow(code, "", "curl/8.4").andExpect(status().isFound());
    clickRecorder.flush();

    mockMvc.perform(get("/api/v1/links/" + code + "/stats").param("days", "3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.totalClicks").value(3))
        .andExpect(jsonPath("$.lastClickAt").value("2026-01-03T00:00:00Z"))
        .andExpect(jsonPath("$.from").value("2026-01-01"))
        .andExpect(jsonPath("$.to").value("2026-01-03"))
        .andExpect(jsonPath("$.daily[*].clicks").value(org.hamcrest.Matchers.contains(2, 0, 1)));
    mockMvc.perform(get("/api/v1/links/" + code + "/stats"))
        .andExpect(jsonPath("$.daily.length()").value(7));
  }

  @Test
  void storesOnlyTheReferrerHostAndAnAgentFamilyNeverAnIpOrUserAgent() throws Exception {
    String code = createLink(null);
    follow(code, "https://mail.example.com/inbox?user=alice@example.com", CHROME);
    clickRecorder.flush();

    Map<String, Object> row = jdbc.sql("select * from link_clicks where code = :code")
        .param("code", code).query().singleRow();
    List<String> columns = jdbc.sql("""
        select column_name from information_schema.columns
         where table_name = 'link_clicks' order by ordinal_position
        """).query(String.class).list();

    assertThat(columns).containsExactly("id", "code", "clicked_at", "referrer_host",
        "agent_family");
    assertThat(row).containsEntry("referrer_host", "mail.example.com")
        .containsEntry("agent_family", "BROWSER");
  }

  @Test
  void failedRedirectsAreNotCountedAndTheStatisticsWindowIsValidated() throws Exception {
    String expiring = createLink("2026-01-01T01:00:00Z");
    clock.advance(Duration.ofHours(2));
    follow(expiring, null, CHROME).andExpect(status().isGone());
    follow("zz-unknown-" + UUID.randomUUID().toString().substring(0, 8), null, CHROME)
        .andExpect(status().isNotFound());
    clickRecorder.flush();

    mockMvc.perform(get("/api/v1/links/" + expiring + "/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalClicks").value(0))
        .andExpect(jsonPath("$.lastClickAt").isEmpty());
    mockMvc.perform(get("/api/v1/links/" + expiring + "/stats").param("days", "91"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("days"));
    mockMvc.perform(delete("/api/v1/links/" + expiring)).andExpect(status().isNoContent());
    mockMvc.perform(get("/api/v1/links/" + expiring + "/stats"))
        .andExpect(status().isNotFound());
  }

  @Test
  void retentionDeletesClicksOlderThanTheRetentionPeriod() throws Exception {
    String code = createLink(null);
    follow(code, null, CHROME);
    clock.advance(Duration.ofDays(91));
    follow(code, null, CHROME);
    clickRecorder.flush();

    new ClickMaintenance(clickRecorder, clickRepository, properties, clock).purge();

    assertThat(clickRepository.totals(code).total()).isEqualTo(1);
  }
}
