# ADR-0015: In-process token-bucket rate limiting on link creation

Status: accepted, 2026-10-28

## Decision
`POST /api/v1/links` is limited per client (default 20 requests/minute) by an in-memory token
bucket keyed by client address, stored in a bounded Caffeine cache that expires idle clients.
Exceeding it returns `429` with `Retry-After`. `X-Forwarded-For` is only trusted when
`trust-forwarded-headers` is enabled, because otherwise any client could spoof its key.

## Rationale
Link creation is the abuse vector (spam and phishing links); redirects are not limited because
they are the product. The limiter is integer-arithmetic, `Clock`-driven and fully unit-tested.

## Consequences
- Limits are per instance; N instances allow N× the rate. A shared limiter (Redis, API gateway)
  is the production answer.
- Memory is bounded by `max-tracked-clients`.
