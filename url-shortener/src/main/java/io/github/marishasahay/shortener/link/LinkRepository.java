package io.github.marishasahay.shortener.link;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Explicit-SQL persistence for {@link Link}s. Deleted links are soft-deleted and invisible to
 * lookups, but their codes stay in the table so they are never reused.
 */
@Repository
public class LinkRepository {

  private static final String INSERT_SQL = """
      insert into links (code, target_url, created_at, expires_at)
      values (:code, :targetUrl, :createdAt, :expiresAt)
      on conflict (code) do nothing
      returning id
      """;

  private static final String FIND_ACTIVE_SQL = """
      select id, code, target_url, created_at, expires_at
        from links
       where code = :code
         and deleted_at is null
      """;

  private static final String SOFT_DELETE_SQL = """
      update links
         set deleted_at = :deletedAt
       where code = :code
         and deleted_at is null
      """;

  private final JdbcClient jdbcClient;

  public LinkRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Inserts a link unless its code is already taken (including by a deleted link).
   *
   * @param code the short code
   * @param targetUrl the validated target URL
   * @param createdAt creation instant
   * @param expiresAt optional expiry instant
   * @return the inserted link, or empty if the code already exists
   */
  public Optional<Link> insertIfAbsent(String code, String targetUrl, Instant createdAt,
      Instant expiresAt) {
    return jdbcClient.sql(INSERT_SQL)
        .param("code", code)
        .param("targetUrl", targetUrl)
        .param("createdAt", timestamp(createdAt))
        .param("expiresAt", timestamp(expiresAt))
        .query(Long.class)
        .optional()
        .map(id -> new Link(id, code, targetUrl, createdAt, expiresAt));
  }

  /**
   * Finds a link that has not been deleted. Expired links are returned.
   *
   * @param code the short code
   * @return the link, or empty if it does not exist or was deleted
   */
  public Optional<Link> findActiveByCode(String code) {
    return jdbcClient.sql(FIND_ACTIVE_SQL)
        .param("code", code)
        .query(LinkRepository::mapRow)
        .optional();
  }

  /**
   * Soft-deletes a link.
   *
   * @param code the short code
   * @param deletedAt deletion instant
   * @return {@code true} if an active link was deleted
   */
  public boolean softDelete(String code, Instant deletedAt) {
    int updated = jdbcClient.sql(SOFT_DELETE_SQL)
        .param("code", code)
        .param("deletedAt", timestamp(deletedAt))
        .update();
    return updated > 0;
  }

  private static SqlParameterValue timestamp(Instant instant) {
    OffsetDateTime value = instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, value);
  }

  private static Link mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Link(
        rs.getLong("id"),
        rs.getString("code"),
        rs.getString("target_url"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("expires_at", OffsetDateTime.class)));
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
