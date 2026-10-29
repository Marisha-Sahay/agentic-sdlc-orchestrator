# ADR-0017: Best-effort click analytics through a bounded in-memory queue

Status: accepted, 2026-10-29

## Context
The brownfield request asks to "record every successful redirect" and also "without adding
noticeable latency". A durable record of every redirect needs a synchronous database write or a
durable log on the hot path; the redirect path today does no database work on a cache hit.

## Decision
The conflict is raised as an open question (Q-1) by the requirements stage and decided at design
review: recording is **best effort**. A redirect offers the click to a bounded
`ArrayBlockingQueue` (10,000) and returns; a scheduled task writes batches of 500 every second.
Full queue: the click is dropped and counted. Failed batch: lost and counted, never retried (so
never duplicated). Crash: up to one flush interval is lost; graceful shutdown flushes. Only the
referrer host and a coarse agent family are stored, keyed by code; clicks are purged after 90 days,
which also bounds the statistics window.

## Consequences
- No database work and no blocking on the redirect path; analytics can never fail a redirect.
- Counts can be low during saturation or failures; the loss is visible in
  `shortener.clicks.dropped` and `shortener.clicks.failed`.
- If exact counts become a requirement, a durable log (e.g. Kafka) replaces the queue; the
  repository and statistics API stay.
