package io.github.marishasahay.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ShortenerPropertiesTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void shouldApplyDocumentedDefaults() {
    runner.run(context -> {
      ShortenerProperties properties = context.getBean(ShortenerProperties.class);
      assertThat(properties.baseUrl()).isEqualTo(URI.create("http://localhost:8080"));
      assertThat(properties.codeLength()).isEqualTo(7);
      assertThat(properties.redirectCache().ttl()).isEqualTo(Duration.ofMinutes(10));
      assertThat(properties.redirectCache().maxSize()).isEqualTo(10_000);
      assertThat(properties.rateLimit().requestsPerPeriod()).isEqualTo(20);
      assertThat(properties.rateLimit().period()).isEqualTo(Duration.ofMinutes(1));
      assertThat(properties.rateLimit().maxTrackedClients()).isEqualTo(100_000);
      assertThat(properties.trustForwardedHeaders()).isFalse();
    });
  }

  @Test
  void shouldBindOverrides() {
    runner.withPropertyValues("shortener.base-url=https://sho.rt", "shortener.code-length=9",
            "shortener.redirect-cache.ttl=30s", "shortener.rate-limit.requests-per-period=5",
            "shortener.trust-forwarded-headers=true")
        .run(context -> {
          ShortenerProperties properties = context.getBean(ShortenerProperties.class);
          assertThat(properties.baseUrl().getHost()).isEqualTo("sho.rt");
          assertThat(properties.codeLength()).isEqualTo(9);
          assertThat(properties.redirectCache().ttl()).isEqualTo(Duration.ofSeconds(30));
          assertThat(properties.rateLimit().requestsPerPeriod()).isEqualTo(5);
          assertThat(properties.trustForwardedHeaders()).isTrue();
        });
  }

  @Test
  void shouldFailFastOnInvalidValues() {
    runner.withPropertyValues("shortener.code-length=3")
        .run(context -> assertThat(context).hasFailed());
    runner.withPropertyValues("shortener.rate-limit.requests-per-period=0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ShortenerProperties.class)
  static class PropertiesConfiguration {}
}
