# ADR-0005: Re-planning by content-hash invalidation

Status: accepted, 2026-10-28

## Context
The workflow must dynamically re-plan when upstream outputs change, e.g. when a reviewer amends
the requirements at the design checkpoint, without redoing work that is unaffected.

## Decision
Artifacts are versioned and content-addressed. Each accepted node records the SHA-256 of every
input it read. When any artifact gets a new version, the scheduler invalidates every accepted
node whose recorded input hash differs, plus its downstream closure; nodes spawned by an
invalidated planner are removed and regenerated. Storing identical content is a no-op, so re-runs
that reproduce the same output do not cascade.

## Alternatives
- Restart the workflow from the changed stage: simple but redoes unaffected parallel branches.
- Ask a model to decide what to redo: non-deterministic and unauditable.

## Consequences
- Re-planning is precise and explainable: each `NodeInvalidated` event names the input and the old
  and new hashes. In the drill, a requirements amendment re-ran exactly `impact-analysis` and
  `design`.
- Re-running a code-writing node writes full files over the current tree; files a previous
  execution created and the new one does not are left in place (documented limitation).
