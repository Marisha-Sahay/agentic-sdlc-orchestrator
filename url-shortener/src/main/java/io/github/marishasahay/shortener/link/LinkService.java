package io.github.marishasahay.shortener.link;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Link lifecycle: create (with generated code or custom alias), read metadata, soft delete.
 */
@Service
public class LinkService {

  static final int MAX_CODE_ATTEMPTS = 5;
  static final String EXPIRES_AT_FIELD = "expiresAt";

  private static final Logger LOG = LoggerFactory.getLogger(LinkService.class);

  private final LinkRepository repository;
  private final CodeGenerator codeGenerator;
  private final TargetUrlPolicy targetUrlPolicy;
  private final AliasPolicy aliasPolicy;
  private final LinkResolver linkResolver;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param repository link persistence
   * @param codeGenerator generator for random codes
   * @param targetUrlPolicy target URL rules
   * @param aliasPolicy custom alias rules
   * @param linkResolver redirect resolver whose cache is evicted on delete
   * @param clock time source
   */
  public LinkService(LinkRepository repository, CodeGenerator codeGenerator,
      TargetUrlPolicy targetUrlPolicy, AliasPolicy aliasPolicy, LinkResolver linkResolver,
      Clock clock) {
    this.repository = repository;
    this.codeGenerator = codeGenerator;
    this.targetUrlPolicy = targetUrlPolicy;
    this.aliasPolicy = aliasPolicy;
    this.linkResolver = linkResolver;
    this.clock = clock;
  }

  /**
   * Creates a short link.
   *
   * @param url the target URL
   * @param customAlias optional alias to use as the code; {@code null} generates a code
   * @param expiresAt optional expiry, must be in the future
   * @return the created link
   * @throws InvalidLinkRequestException if the URL, alias or expiry is invalid
   * @throws AliasAlreadyExistsException if the alias is taken
   * @throws CodeGenerationException if no unique code could be generated
   */
  public Link create(String url, String customAlias, Instant expiresAt) {
    String targetUrl = targetUrlPolicy.validate(url);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Instant expiry = expiresAt == null ? null : expiresAt.truncatedTo(ChronoUnit.MICROS);
    if (expiry != null && !expiry.isAfter(now)) {
      throw new InvalidLinkRequestException(EXPIRES_AT_FIELD, "must be in the future");
    }
    if (customAlias != null) {
      aliasPolicy.validate(customAlias);
      return repository.insertIfAbsent(customAlias, targetUrl, now, expiry)
          .orElseThrow(() -> new AliasAlreadyExistsException(customAlias));
    }
    return insertWithGeneratedCode(targetUrl, now, expiry);
  }

  /**
   * Returns the metadata of an active (not deleted) link; expired links are still returned.
   *
   * @param code the short code
   * @return the link
   * @throws LinkNotFoundException if no active link exists for the code
   */
  public Link get(String code) {
    return repository.findActiveByCode(code)
        .orElseThrow(() -> new LinkNotFoundException(code));
  }

  /**
   * Soft-deletes a link and evicts it from the redirect cache. The code is never reused.
   *
   * @param code the short code
   * @throws LinkNotFoundException if no active link exists for the code
   */
  public void delete(String code) {
    boolean deleted = repository.softDelete(code, clock.instant().truncatedTo(ChronoUnit.MICROS));
    linkResolver.evict(code);
    if (!deleted) {
      throw new LinkNotFoundException(code);
    }
  }

  private Link insertWithGeneratedCode(String targetUrl, Instant now, Instant expiry) {
    for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
      String code = codeGenerator.generate();
      Optional<Link> inserted = repository.insertIfAbsent(code, targetUrl, now, expiry);
      if (inserted.isPresent()) {
        return inserted.get();
      }
      LOG.info("Generated code collided with an existing code (attempt {}/{})", attempt,
          MAX_CODE_ATTEMPTS);
    }
    LOG.error("Exhausted {} attempts to generate a unique code", MAX_CODE_ATTEMPTS);
    throw new CodeGenerationException(MAX_CODE_ATTEMPTS);
  }
}
