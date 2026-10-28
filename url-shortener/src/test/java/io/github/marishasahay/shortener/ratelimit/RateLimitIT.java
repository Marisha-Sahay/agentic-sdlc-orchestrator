package io.github.marishasahay.shortener.ratelimit;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.marishasahay.shortener.IntegrationTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class RateLimitIT extends IntegrationTestSupport {

  private static final String BODY = "{\"url\": \"https://example.com/rate-limited\"}";

  @Test
  void shouldReturn429WithRetryAfterOnceCreationLimitIsExhausted() throws Exception {
    for (int i = 0; i < 20; i++) {
      postLink(BODY).andExpect(status().isCreated());
    }

    postLink(BODY)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "3"))
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(429))
        .andExpect(jsonPath("$.title").value("Too many requests"));
  }

  @Test
  void shouldAllowCreationAgainAfterTokensRefill() throws Exception {
    for (int i = 0; i < 20; i++) {
      postLink(BODY).andExpect(status().isCreated());
    }
    postLink(BODY).andExpect(status().isTooManyRequests());

    clock.advance(Duration.ofSeconds(3));

    postLink(BODY).andExpect(status().isCreated());
  }
}
