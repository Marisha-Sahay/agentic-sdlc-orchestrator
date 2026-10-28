package io.github.marishasahay.shortener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * Actuator checks that need the real database; routing, metrics and info are covered without
 * Docker by {@link ApplicationWiringTest}.
 */
class ActuatorIT extends IntegrationTestSupport {

  @Test
  void shouldReportHealthUpWithDatabaseAndMigrationsInPlace() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
