package io.github.marishasahay.shortener.link;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.marishasahay.shortener.IntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #1 end to end: the cache must never extend a link's life. */
class CachedLinkExpiryIT extends IntegrationTestSupport {

  private String create(String expiry) throws Exception {
    String alias = "e-" + UUID.randomUUID().toString().substring(0, 12);
    postLink("{\"url\": \"https://example.com/sale\", \"customAlias\": \"" + alias + "\""
        + (expiry == null ? "" : ", \"expiresAt\": \"" + expiry + "\"") + "}")
        .andExpect(status().isCreated());
    return alias;
  }

  @Test
  void aCachedLinkStopsRedirectingTheMomentItExpires() throws Exception {
    String code = create("2026-01-01T01:00:00Z");
    mockMvc.perform(get("/" + code)).andExpect(status().isFound());
    clock.advance(Duration.ofMinutes(59));
    mockMvc.perform(get("/" + code)).andExpect(status().isFound());

    clock.advance(Duration.ofMinutes(1));

    mockMvc.perform(get("/" + code)).andExpect(status().isGone());
  }

  @Test
  void aDeletedCachedLinkStopsRedirectingImmediately() throws Exception {
    String code = create(null);
    mockMvc.perform(get("/" + code)).andExpect(status().isFound());

    mockMvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isNoContent());

    mockMvc.perform(get("/" + code)).andExpect(status().isNotFound());
  }
}
