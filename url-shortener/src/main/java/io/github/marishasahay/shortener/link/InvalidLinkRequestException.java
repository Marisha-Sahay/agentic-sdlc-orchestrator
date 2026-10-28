package io.github.marishasahay.shortener.link;

/**
 * A create-link request violates a business rule (target URL policy, alias policy or expiry).
 * Mapped to {@code 400 Bad Request}.
 */
public class InvalidLinkRequestException extends RuntimeException {

  private final String field;
  private final String reason;

  /**
   * Creates the exception.
   *
   * @param field the request field that is invalid
   * @param reason why the value is invalid, phrased to follow the field name
   */
  public InvalidLinkRequestException(String field, String reason) {
    super(field + " " + reason);
    this.field = field;
    this.reason = reason;
  }

  public String field() {
    return field;
  }

  public String reason() {
    return reason;
  }
}
