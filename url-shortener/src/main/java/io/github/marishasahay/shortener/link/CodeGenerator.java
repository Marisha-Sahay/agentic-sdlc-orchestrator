package io.github.marishasahay.shortener.link;

/**
 * Produces candidate short codes. Uniqueness is enforced by the database, not by the generator.
 */
public interface CodeGenerator {

  /**
   * Generates a new candidate code.
   *
   * @return a code made of URL-safe characters
   */
  String generate();
}
