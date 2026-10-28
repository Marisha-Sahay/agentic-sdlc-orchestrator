package io.github.marishasahay.shortener.link;

/**
 * No unique code could be generated within the retry budget. This indicates a keyspace that is
 * too small for the data volume (raise {@code shortener.code-length}). Mapped to
 * {@code 500 Internal Server Error}.
 */
public class CodeGenerationException extends RuntimeException {

  public CodeGenerationException(int attempts) {
    super("Could not generate a unique short code after " + attempts + " attempts");
  }
}
