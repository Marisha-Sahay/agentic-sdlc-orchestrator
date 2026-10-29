# Changelog

## Unreleased

### Added
- Click analytics: every successful redirect is recorded asynchronously and best effort (bounded
  queue, batched writes, drops and failures counted as metrics); only the referrer host and a
  coarse user-agent family are stored. Clicks are kept for 90 days.
- `GET /api/v1/links/{code}/stats`: total clicks, last click and daily counts for the last
  `days` UTC days (default 7, 1-90).
- Migration `V2__create_link_clicks.sql`.

### Fixed
- Issue #1: an expired link no longer keeps redirecting while it is in the redirect cache; expiry
  is checked on every lookup.
