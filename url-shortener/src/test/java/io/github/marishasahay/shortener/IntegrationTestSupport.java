package io.github.marishasahay.shortener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.marishasahay.shortener.support.MutableClock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests: the full application against a real PostgreSQL.
 *
 * <p>The container is a JVM-wide singleton (started once in a static initializer, stopped by
 * Testcontainers' reaper at JVM exit) and every subclass uses the same context configuration,
 * so all integration test classes share one container and one cached Spring context. Because
 * state is shared, tests must not assume an empty table: they use unique aliases or generated
 * codes, and each test gets its own client address so rate-limit buckets never interfere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestSupport.TestClockConfiguration.class)
public abstract class IntegrationTestSupport {

  protected static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  /**
   * Set by a sandboxed build gate, which has no Docker inside: use the database it provides
   * instead of starting a container.
   */
  private static final String EXTERNAL_DATABASE = System.getenv("IT_DATABASE_URL");

  static final PostgreSQLContainer POSTGRES =
      EXTERNAL_DATABASE == null ? new PostgreSQLContainer("postgres:17-alpine") : null;

  private static final AtomicInteger CLIENT_SEQUENCE = new AtomicInteger();

  static {
    if (POSTGRES != null) {
      POSTGRES.start();
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    if (POSTGRES == null) {
      registry.add("spring.datasource.url", () -> EXTERNAL_DATABASE);
      registry.add("spring.datasource.username", () -> System.getenv("IT_DATABASE_USER"));
      registry.add("spring.datasource.password", () -> System.getenv("IT_DATABASE_PASSWORD"));
    } else {
      registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
  }

  @Autowired
  protected MockMvc mockMvc;

  @Autowired
  protected MutableClock clock;

  protected String clientAddress;

  @BeforeEach
  void resetClockAndClient() {
    clock.setInstant(START);
    int n = CLIENT_SEQUENCE.incrementAndGet();
    clientAddress = "10.%d.%d.%d".formatted((n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff);
  }

  /** Sends {@code POST /api/v1/links} from this test's client address. */
  protected ResultActions postLink(String json) throws Exception {
    return mockMvc.perform(post("/api/v1/links")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json)
        .with(fromAddress(clientAddress)));
  }

  protected static RequestPostProcessor fromAddress(String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
  }

  protected static String jsonField(MvcResult result, String path) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), path);
  }

  /** Replaces the system clock with a controllable one for the whole context. */
  @TestConfiguration(proxyBeanMethods = false)
  static class TestClockConfiguration {

    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(START);
    }
  }
}
