package io.github.marishasahay.shortener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests (*IT): the full application against a real PostgreSQL in a
 * JVM-wide singleton container, so every IT class shares one container and one Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestSupport {

  /**
   * Set by a sandboxed build gate, which has no Docker inside: use the database it provides
   * instead of starting a container.
   */
  private static final String EXTERNAL_DATABASE = System.getenv("IT_DATABASE_URL");

  static final PostgreSQLContainer POSTGRES =
      EXTERNAL_DATABASE == null ? new PostgreSQLContainer("postgres:17-alpine") : null;

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
}
