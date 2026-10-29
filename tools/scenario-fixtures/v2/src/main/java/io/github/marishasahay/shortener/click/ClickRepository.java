package io.github.marishasahay.shortener.click;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Explicit-SQL persistence for clicks. */
@Repository
public class ClickRepository {

  private static final String INSERT_SQL = """
      insert into link_clicks (code, clicked_at, referrer_host, agent_family)
      values (?, ?, ?, ?)
      """;

  private static final String TOTALS_SQL = """
      select count(*) as total, max(clicked_at) as last_click
        from link_clicks
       where code = :code
      """;

  private static final String DAILY_SQL = """
      select cast(clicked_at at time zone 'UTC' as date) as day, count(*) as clicks
        from link_clicks
       where code = :code
         and clicked_at >= :from
       group by day
      """;

  private static final String PURGE_SQL = "delete from link_clicks where clicked_at < :cutoff";

  private final JdbcClient jdbcClient;
  private final JdbcTemplate jdbcTemplate;

  public ClickRepository(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
    this.jdbcClient = jdbcClient;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Inserts a batch of clicks in one round trip.
   *
   * @param events clicks to store
   */
  public void insertBatch(List<ClickEvent> events) {
    jdbcTemplate.batchUpdate(INSERT_SQL, events, events.size(), (ps, event) -> {
      ps.setString(1, event.code());
      ps.setObject(2, event.clickedAt().atOffset(ZoneOffset.UTC),
          Types.TIMESTAMP_WITH_TIMEZONE);
      ps.setString(3, event.referrerHost());
      ps.setString(4, event.agentFamily().name());
    });
  }

  /**
   * Total clicks and the time of the last one for a code.
   *
   * @param code the short code
   * @return totals over the retained clicks
   */
  public Totals totals(String code) {
    return jdbcClient.sql(TOTALS_SQL).param("code", code)
        .query((rs, row) -> {
          OffsetDateTime last = rs.getObject("last_click", OffsetDateTime.class);
          return new Totals(rs.getLong("total"), last == null ? null : last.toInstant());
        })
        .single();
  }

  /**
   * Clicks per UTC day since an instant; days without clicks are absent.
   *
   * @param code the short code
   * @param from start of the window (inclusive)
   * @return day to clicks
   */
  public Map<LocalDate, Long> dailyCounts(String code, Instant from) {
    Map<LocalDate, Long> counts = new LinkedHashMap<>();
    jdbcClient.sql(DAILY_SQL).param("code", code).param("from", timestamp(from))
        .query((rs, row) -> Map.entry(rs.getObject("day", LocalDate.class), rs.getLong("clicks")))
        .list()
        .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
    return counts;
  }

  /**
   * Deletes clicks older than the retention cutoff.
   *
   * @param cutoff clicks before this instant are deleted
   * @return number of deleted clicks
   */
  public int deleteOlderThan(Instant cutoff) {
    return jdbcClient.sql(PURGE_SQL).param("cutoff", timestamp(cutoff)).update();
  }

  private static SqlParameterValue timestamp(Instant instant) {
    return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, instant.atOffset(ZoneOffset.UTC));
  }

  /**
   * Aggregate over all retained clicks of a code.
   *
   * @param total number of clicks
   * @param lastClickAt most recent click, or null if none
   */
  public record Totals(long total, Instant lastClickAt) {}
}
