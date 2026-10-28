# ADR-0012: 302 redirects with `Cache-Control: no-store`

Status: accepted, 2026-10-28

## Decision
`GET /{code}` answers `302 Found` with `Location` and `Cache-Control: no-store`.

## Rationale
A `301 Moved Permanently` is cached by browsers and proxies, so later clicks never reach the
service: click analytics, expiry and deletion would silently stop working for anyone who visited
once. 302 keeps the service in the loop at the cost of one round trip per click.

## Consequences
Every click hits the service, which is why the redirect path is cached in process
([ADR-0013](0013-redirect-cache-and-expiry.md)) and why analytics recording must stay off the
synchronous path.
