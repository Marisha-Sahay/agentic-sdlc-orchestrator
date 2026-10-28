package io.github.marishasahay.shortener.link;

/**
 * The requested custom alias is taken (possibly by a deleted link, since codes are never
 * reused). Mapped to {@code 409 Conflict}.
 */
public class AliasAlreadyExistsException extends RuntimeException {

  public AliasAlreadyExistsException(String alias) {
    super("The alias '" + alias + "' is already in use");
  }
}
