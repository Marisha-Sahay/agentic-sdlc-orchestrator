package io.github.marishasahay.shortener.link;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which target URLs may be shortened.
 *
 * <p>Only absolute {@code http}/{@code https} URLs with a host are accepted. URLs carrying
 * user-info are rejected because {@code https://bank.com@evil.com} visually suggests the wrong
 * host, and URLs pointing at the shortener itself are rejected to prevent redirect loops.
 */
public class TargetUrlPolicy {

  static final int MAX_LENGTH = 2048;
  static final String FIELD = "url";

  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

  private final String ownHost;

  /**
   * Creates the policy.
   *
   * @param ownHost the shortener's own public host, rejected as a target; may be {@code null}
   */
  public TargetUrlPolicy(String ownHost) {
    this.ownHost = ownHost == null ? null : ownHost.toLowerCase(Locale.ROOT);
  }

  /**
   * Validates a candidate target URL.
   *
   * @param url the URL submitted by the client
   * @return the trimmed URL, safe to store and redirect to
   * @throws InvalidLinkRequestException if the URL violates the policy
   */
  public String validate(String url) {
    if (url == null || url.isBlank()) {
      throw reject("must not be blank");
    }
    String candidate = url.strip();
    if (candidate.length() > MAX_LENGTH) {
      throw reject("must be at most " + MAX_LENGTH + " characters");
    }
    URI uri = parse(candidate);
    String scheme = uri.getScheme();
    if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
      throw reject("must be an absolute http or https URL");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw reject("must contain a valid host");
    }
    if (uri.getRawUserInfo() != null) {
      throw reject("must not contain user info");
    }
    if (ownHost != null && ownHost.equals(host.toLowerCase(Locale.ROOT))) {
      throw reject("must not point at this shortener");
    }
    return candidate;
  }

  private static URI parse(String candidate) {
    try {
      return new URI(candidate);
    } catch (URISyntaxException e) {
      throw reject("is not a valid URL");
    }
  }

  private static InvalidLinkRequestException reject(String message) {
    return new InvalidLinkRequestException(FIELD, message);
  }
}
