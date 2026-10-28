package io.github.marishasahay.shortener.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the system UTC clock as a bean so every time-dependent component can be tested with a
 * controllable clock.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
