package io.github.marishasahay.shortener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests (*IT): the full application against a real PostgreSQL in a
 * JVM-wide singleton container, so every IT class shares one container and one Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestSupport {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired
  protected MockMvc mockMvc;
}
