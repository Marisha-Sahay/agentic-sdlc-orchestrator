package io.github.marishasahay.shortener.link;

/**
 * No active link exists for a code (never created or deleted). Mapped to {@code 404 Not Found}.
 */
public class LinkNotFoundException extends RuntimeException {

  public LinkNotFoundException(String code) {
    super("No link exists for code '" + code + "'");
  }
}
