# ADR-0006: Git checkpoints in a private run workspace for rollback

Status: accepted, 2026-10-28

## Context
Rollback must restore the exact last good state after a failed gate, a rejection or a stop, and
the outcome must be reviewable as a diff. The developer's own repository must never be touched.

## Decision
Each run copies the baseline into `.runs/<id>/workspace`, initialises a private git repository,
tags the baseline, and commits a checkpoint for every accepted change. A rejected attempt is
rolled back with `reset --hard` + `clean -fd` to the checkpoint it started from. The release patch
is `git diff baseline HEAD`.

## Alternatives
- File snapshots/copies: heavier, and no diff for reviewers.
- Branches in the real repository: realistic, but risky for a prototype and couples runs to the
  developer's working tree.

## Consequences
- Rollback is exact and cheap; every accepted node is attributable to a commit
  (`agentic-orchestrator` author) in the workspace history.
- Parallel nodes share one workspace; apply-gate-commit is serialised by a lock
  ([architecture §3.4](../architecture.md)).
- Production would map checkpoints to commits on a run branch and the release to a pull request.
