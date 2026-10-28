# ADR-0002: One hash-chained event log is the source of truth

Status: accepted, 2026-10-28

## Context
We need audit-grade traceability, decision lineage, reliability metrics (success rate,
retry/rollback frequency, MTTR, latency), a human-readable run record, and the ability to resume
a stopped run. Keeping separate stores for each invites them to disagree.

## Decision
Every state change is appended as a typed event to one append-only JSONL log per run. Each entry
carries `hash = SHA-256(prevHash, seq, runId, timestamp, event)`. Audit trail, lineage, metrics,
the HTML report and resume state are all *derived* from the log.

## Alternatives
- Counters and a status table updated in place: cheap, but loses history and can drift from the
  audit trail.
- A database per concern: more moving parts for a single-process prototype.

## Consequences
- One mechanism satisfies four requirements; metrics can be recomputed for any historical run and
  cannot contradict the audit trail.
- Tampering with any earlier event is detectable (`agentic verify-log`; tested).
- Appends are serialised; fine at hundreds of events per run. A log write failure stops the run
  ("refusing to continue") rather than proceeding unaudited.
- Production would move the log to Postgres/Kafka with signed checkpoints; the event model stays.
