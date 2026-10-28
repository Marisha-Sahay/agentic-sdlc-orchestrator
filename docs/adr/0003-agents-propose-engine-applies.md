# ADR-0003: Agents propose; only the engine writes

Status: accepted, 2026-10-28

## Context
Controlled autonomy means agents do the work but cannot bypass oversight. If an agent can write
files or run commands directly, every guardrail becomes advisory.

## Decision
A `NodeTask` returns a `NodeResult` proposal (artifacts, full-content file changes, decisions). The
engine evaluates policy on the proposal *before* anything is written, then applies it under a
workspace lock, runs gates, asks for approval if needed, and commits or rolls back.

## Alternatives
- Tool-using agents with file-system and shell tools: more flexible, but guardrails would have to
  intercept every tool call, and partial writes are hard to undo.
- Patches (unified diffs) instead of full files: smaller, but a patch that does not apply cleanly
  is a worse failure mode than a slightly larger proposal.

## Consequences
- Policy is evaluated on intent, so violations cost nothing to reject (no rollback needed).
- The workspace only ever contains accepted work; the agent's write scope is an allow-list, and
  the files defining the guardrails (CI, Checkstyle, policy) are protected from agents.
- Full-file proposals cost tokens on large files; acceptable at this service's size.
