# Demo script (10–15 minutes)

A walkthrough for presenting the system live. Every step works offline except the optional live
run in step 6.

**Before:** `mvn -q verify` once (warms the Maven cache; ~20 s), Docker running, two terminals.

## 1. The idea (1 min)
"A requirement goes in; a reviewable patch comes out, with tests, docs, traceability and an audit
trail. Agents do the work, a governed workflow engine decides what's accepted, and humans approve
what matters." Show the diagram in the [README](../README.md).

## 2. The engine in one picture (2 min)
[architecture §3.3](architecture.md#33-one-attempt): one attempt = agent proposes → policy →
apply under lock → deterministic gates → (human) → checkpoint, or rollback with the evidence fed
to the next attempt. Then [§3.5](architecture.md#35-dynamic-planning-and-re-planning): expansion
and content-hash re-planning.

## 3. Break things on purpose: the drill (4 min)
```bash
./agentic run drill
```
Narrate as the log scrolls:
- `codebase-scan` and `requirements` start together (parallel), `impact-analysis` waits for both
  (join).
- **FAIL impact-grounding: LinkCache.java: no such file**: the model invented a file; the
  JavaParser index caught it; retry with the evidence.
- **design: approved, amended [requirements.spec]** → **invalidated** `impact-analysis` and
  `design`: the reviewer changed the requirement; only the affected nodes re-run.
- **plan expanded into [impl-resolver-expiry-check, impl-expiry-index]**: both run in parallel.
- **policy: BLOCK schema-migration**: the agent edited an applied migration; rejected before a
  byte was written. Attempt 2 adds V2 → **waiting for human approval** (schema change).
- **FAIL maven-compile → rollback**: a real compiler error, rolled back to the last checkpoint.
- **docs: 503 … fallback strategy activated**: model outage, deterministic fallback.
- `tests`, `docs`, `security-review` in parallel → `verify` runs the full `mvn verify` with
  Testcontainers → `release` waits for sign-off.

## 4. The evidence (3 min)
```bash
open .runs/drill/report.html
```
- KPIs: success rate, first-pass yield, retry/rollback rates, MTTR, human vs machine time.
- Graph: amber borders = nodes that recovered.
- Human checkpoints: the amendment and its comment.
- Decisions and lineage: the same decision based on `requirements.spec@v1` and then `@v2`.
- Gates, policy and recovery: compiler output, policy finding, rollback, fallback.

Then the audit trail is tamper-evident:
```bash
./agentic verify-log drill                         # verified
cp -R .runs/drill .runs/tampered
python3 -c "p='.runs/tampered/events.jsonl';s=open(p).read();open(p,'w').write(s.replace('\"reviewer\":\"scripted-reviewer\"','\"reviewer\":\"someone-else\"',1))"
./agentic verify-log tampered                      # BROKEN at event 31
```
And the outcome itself: `cat .runs/drill/release.patch`, and the
[traceability matrix](evidence/drill/artifacts/release.traceability/v1.txt).

## 5. Safe stop and resume (1 min)
```bash
./agentic run drill --run-id stop-demo &           # terminal 1
./agentic stop stop-demo                           # terminal 2, a few seconds later
./agentic resume stop-demo                         # accepted nodes are not redone
```

## 6. Human in the loop, for real (optional, 3 min)
```bash
./agentic run brownfield --approvals interactive   # replays the recording if present
```
At the design checkpoint: `v` to view the design, `d` for the diff, then `r` and type "make
click recording bounded and asynchronous". The design rolls back and the architect retries with
that comment. Or `e` to edit `requirements.spec` and watch downstream nodes re-plan.

## 7. Code tour (2 min)
- `WorkflowEngine.schedule` (scheduler loop), `replanStaleNodes` (re-planning).
- `NodeRunner.attempt` / `gateApproveAndAccept` (the governed lifecycle).
- `GovernancePolicy` (change control as code) and `WorkspaceScopeRule` (protecting the
  guardrails).
- `WorkflowEngineTest`: the test names read as the specification of the controls.

## 8. Close (1 min)
Trade-offs and the production path: [architecture §10](architecture.md#10-production-path) and
[risks-and-limitations.md](risks-and-limitations.md).
