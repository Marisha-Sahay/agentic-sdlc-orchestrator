# ADR-0011: Random base62 codes with a database uniqueness guarantee

Status: accepted, 2026-10-28

## Context
Short codes must be unique, short, and not guessable (a public shortener with enumerable codes
leaks every link ever created).

## Decision
Generate 7-character codes from `SecureRandom` over `[0-9A-Za-z]` (62^7 ≈ 3.5 × 10^12). Insert with
`INSERT ... ON CONFLICT (code) DO NOTHING RETURNING id`; on a collision, generate a new code, up to
5 attempts, then fail with a clear error. Custom aliases skip generation and return 409 on
conflict.

## Alternatives
- Sequential ID encoded in base62: no collisions, but enumerable and needs coordination across
  instances.
- Hash of the URL: deterministic, but collisions need handling anyway and the same URL can't have
  two links with different expiries.
- Snowflake-style IDs: coordination-free but longer codes.

## Consequences
- No coordination between instances; uniqueness is guaranteed by the database constraint, not the
  generator.
- `ON CONFLICT DO NOTHING` avoids exception-driven retries and the aborted-transaction problem in
  PostgreSQL.
- At 10^9 links the per-insert collision probability is ~3 × 10^-4: retries stay rare. Length is
  configurable.
