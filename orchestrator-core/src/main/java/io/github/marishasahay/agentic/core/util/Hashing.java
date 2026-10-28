package io.github.marishasahay.agentic.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helper used for artifact identity and the tamper-evident event chain. */
public final class Hashing {

  private Hashing() {}

  /**
   * Hex-encoded SHA-256 of a UTF-8 string.
   *
   * @param value input
   * @return 64-character lowercase hex digest
   */
  public static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
    }
  }
}
