# ADR-0016: Authorize before execution, accept a validated tree, bind evidence to that tree

Status: accepted, 2026-10-29 (amends ADR-0007)

## Context
The first version asked for approval after applying a change and running the build. A modified
`pom.xml` can run arbitrary plugins, so a dependency change was executed before anyone approved
it. Approvals were also not tied to what they approved: verification could repair code after the
security review, and the release sign-off could not say which tree it covered.

## Decision
- Two decisions, two moments. **Authorization** happens before anything in the proposal is
  written or built, for execution-sensitive changes (`REQUIRE_AUTHORIZATION`: the build file).
  **Acceptance** happens after every exit gate passed, on the exact validated tree. Both are
  recorded with the proposal hash; acceptance also with the tree hash, and the engine checks the
  tree is unchanged before committing.
- Validation controls in `pom.xml` (test, coverage and Checkstyle configuration, skip flags) are
  blocked outright (`build-integrity`); authorizing a dependency does not authorize weakening a
  gate.
- Evidence order is fixed by the workflow and stage write scopes: verify (the only bounded
  repair) runs before the security review; documentation can only write documentation; a
  deterministic `final-validation` node re-runs every deterministic check on one tree and checks
  that the security review still describes it; release is bound to that tree.
- Reviewers fail closed: no scripted default, decisions cover named reason codes, an unreachable
  reviewer pauses the run, amendments are validated against the artifact's contract.

## Consequences
- One extra full build per run (final validation), roughly 10 s for the service.
- Scripted demonstration decisions must list what they cover; unexpected approvals pause runs,
  which is the point.
- This is change control, not a sandbox: execution isolation is the gate sandbox (host process
  with a scrubbed environment by default, or the opt-in Docker sandbox).
