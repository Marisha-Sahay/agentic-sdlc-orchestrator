package io.github.marishasahay.shortener.link;

/**
 * The link exists but its expiry has passed. Mapped to {@code 410 Gone}.
 */
public class LinkExpiredException extends RuntimeException {

  public LinkExpiredException(String code) {
    super("The link for code '" + code + "' has expired");
  }
}
