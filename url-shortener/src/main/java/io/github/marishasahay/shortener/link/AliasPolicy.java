package io.github.marishasahay.shortener.link;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates client-chosen custom aliases: URL-safe characters only, a bounded length that matches
 * the redirect route, and no reserved words that could be confused with application paths.
 */
public class AliasPolicy {

  static final String FIELD = "customAlias";
  static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{4,32}$");

  static final Set<String> RESERVED_WORDS = Set.of(
      "api", "actuator", "health", "admin", "static", "assets", "public", "login", "logout",
      "error", "favicon", "robots", "metrics", "info", "docs", "swagger", "openapi", "www");

  /**
   * Validates an alias.
   *
   * @param alias the alias submitted by the client
   * @throws InvalidLinkRequestException if the alias is malformed or reserved
   */
  public void validate(String alias) {
    if (alias == null || !ALIAS_PATTERN.matcher(alias).matches()) {
      throw new InvalidLinkRequestException(FIELD,
          "must be 4-32 characters of letters, digits, '_' or '-'");
    }
    if (RESERVED_WORDS.contains(alias.toLowerCase(Locale.ROOT))) {
      throw new InvalidLinkRequestException(FIELD, "is a reserved word");
    }
  }
}
