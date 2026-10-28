package io.github.marishasahay.shortener.link;

import java.util.random.RandomGenerator;

/**
 * Generates fixed-length codes from the base62 alphabet using a (secure) random source. Codes are
 * not enumerable and need no coordination between instances; with 62^7 (about 3.5 * 10^12)
 * possible 7-character codes, collisions are rare and handled by a retry on insert.
 */
public class RandomBase62CodeGenerator implements CodeGenerator {

  static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

  private final RandomGenerator random;
  private final int length;

  /**
   * Creates a generator.
   *
   * @param random the random source; production uses {@link java.security.SecureRandom}
   * @param length the code length, at least 1
   */
  public RandomBase62CodeGenerator(RandomGenerator random, int length) {
    if (length < 1) {
      throw new IllegalArgumentException("length must be positive, was " + length);
    }
    this.random = random;
    this.length = length;
  }

  @Override
  public String generate() {
    StringBuilder code = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }
}
