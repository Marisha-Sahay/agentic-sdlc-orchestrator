# ADR-0014: PostgreSQL with `JdbcClient` and Flyway, tested with Testcontainers

Status: accepted, 2026-10-28

## Decision
- PostgreSQL 17; schema managed by Flyway migrations, which are immutable once applied (enforced
  for agents by the `schema-migration` policy rule).
- Spring `JdbcClient` with explicit SQL instead of JPA: the data model is one or two tables, the
  SQL is visible in review, and features like `ON CONFLICT ... RETURNING` are used directly.
- Integration tests run against real PostgreSQL in Testcontainers (one shared container per JVM,
  `@ServiceConnection`), not an in-memory substitute.

## Consequences
- Tests exercise the real dialect, constraints and migrations (including agent-written ones).
- `mvn verify` needs Docker; without it the orchestrator records a degraded verify profile and CI
  runs the full suite.
