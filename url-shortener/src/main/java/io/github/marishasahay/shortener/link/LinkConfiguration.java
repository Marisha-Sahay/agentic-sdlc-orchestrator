package io.github.marishasahay.shortener.link;

import io.github.marishasahay.shortener.config.ShortenerProperties;
import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the link feature's collaborators that are plain classes.
 */
@Configuration(proxyBeanMethods = false)
public class LinkConfiguration {

  @Bean
  public CodeGenerator codeGenerator(ShortenerProperties properties) {
    return new RandomBase62CodeGenerator(new SecureRandom(), properties.codeLength());
  }

  @Bean
  public TargetUrlPolicy targetUrlPolicy(ShortenerProperties properties) {
    return new TargetUrlPolicy(properties.baseUrl().getHost());
  }

  @Bean
  public AliasPolicy aliasPolicy() {
    return new AliasPolicy();
  }
}
