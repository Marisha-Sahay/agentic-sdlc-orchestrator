# ADR-0007: Policy-required approvals cannot be opted out; fail closed

Status: accepted, 2026-10-28

## Context
Human approval is required for high-impact actions. If each node could declare "no approval",
change control would depend on every workflow author getting it right.

## Decision
`ApprovalMode` has only `POLICY` (default) and `ALWAYS`. A node can *add* a mandatory sign-off but
can never remove one the policy engine demands (schema migration, dependency change, API contract
change, security configuration, large change). Rules that throw produce a BLOCK; gates that throw
fail.

## Consequences
- Governance is centralised and reviewable in one place (`GovernancePolicy`).
- More approvals in unattended runs; the scripted reviewer records every decision it makes as
  `scripted-demo (scenarios/<name>.yaml)`, so automation is never mistaken for a human in the
  audit trail, and it only decides requests whose reasons it explicitly covers (ADR-0016).
