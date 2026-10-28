package io.github.marishasahay.shortener.link;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * Request body of {@code POST /api/v1/links}.
 *
 * @param url the target URL (required)
 * @param customAlias optional custom code
 * @param expiresAt optional expiry instant (ISO-8601), must be in the future
 */
public record CreateLinkRequest(@NotBlank String url, String customAlias, Instant expiresAt) {}
