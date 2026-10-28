# ADR-0008: Positional record/replay of model calls

Status: accepted, 2026-10-28

## Context
Reviewers must be able to run the prototype end to end without an API key, CI must be
deterministic, and live runs cost money and vary between runs.

## Decision
Every model call goes through `CassetteLlmClient`. In `record` mode each exchange is stored as a
cassette keyed by the call's position: `node/e<execution>/attempt-<n>/<strategy>/<agent>`. In
`replay` mode cassettes are served without network access. The prompt hash is stored with each
cassette and a mismatching replay is labelled `replay-drift`.

## Alternatives
- Key by prompt hash: brittle, because prompts legitimately include non-deterministic build
  output (timings), so most replays would miss.
- Mock agents in tests only: would not make the real CLI reproducible.

## Consequences
- Replays exercise the full production path (agents, policy, gates, real Maven builds); only the
  model is substituted.
- The execution number in the key keeps re-planned re-runs from colliding with earlier recordings;
  counters are restored from the log on resume.
- The same mechanism serves hand-authored fixtures for the governance drill (labelled `fixture`,
  able to simulate outages).
