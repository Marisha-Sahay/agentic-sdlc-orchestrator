# ADR-0013: In-process redirect cache; expiry re-checked on every hit

Status: accepted, 2026-10-28 (amended by the brownfield bug fix)

## Context
Redirects are read-heavy and latency-sensitive. Links can expire or be deleted.

## Decision
A bounded Caffeine cache in `LinkResolver` (TTL 10 minutes, max 10,000 entries, Micrometer
metrics) sits in front of PostgreSQL. Deleting a link evicts its entry synchronously.

The v1 baseline cached only `code -> targetUrl` and checked expiry when loading on a cache miss,
so **an expired link kept redirecting while cached** (issue #1; kept in the baseline on purpose as
the brownfield bug-fix target). The fix caches `CachedLink(targetUrl, expiresAt)` and re-checks
expiry on every lookup with the application `Clock`, evicting expired entries.

## Alternatives
- Per-entry Caffeine expiry (`expireAfter`): correct, but driven by the cache ticker rather than
  the injected `Clock`, which makes the behaviour hard to test deterministically.
- Shorter TTL: shrinks the window but does not close it.
- Redis: shared across instances, but adds a network hop and infrastructure; unnecessary at this
  scale.

## Consequences
- Cache hits stay O(1) with no database access; expiry is exact.
- Each instance has its own cache: a delete on one instance is visible on others only after their
  TTL. Acceptable for this prototype; a multi-instance deployment would add pub/sub invalidation.
