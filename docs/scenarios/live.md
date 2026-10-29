# Live-model validation

**Status: partial.** One live run was recorded (brownfield, Gemini 2.5 Flash through LangChain4j,
2026-10-29). It ran the real model through the first half of the workflow and then paused,
fail-closed, at the first high-impact change no reviewer had decided. No live run has reached
release; greenfield and ambiguous have not been run live. The completed scenario evidence uses
hand-authored replies (see [README](README.md)).

```bash
./agentic run brownfield --llm record --cassettes brownfield-live --approvals scripted --run-id brownfield-live
./agentic resume brownfield-live --approvals interactive      # needs a human reviewer
./agentic run brownfield --cassettes brownfield-live --approvals scripted   # replays to the same pause
```

Evidence: [docs/evidence/brownfield-live](../evidence/brownfield-live) (audit log, artifacts, report;
8 recorded replies in [`cassettes/brownfield-live`](../../cassettes/brownfield-live), checked for
the API key: none).

## What happened

| Step | Live model | Governance |
|---|---|---|
| requirements | spec produced in 32 s | `requirements-quality` passed first time |
| impact-analysis | named the impacted files | `impact-grounding` passed first time (no invented files) |
| design, attempt 1 | design + full OpenAPI contract | demonstration reviewer **rejected** it (make recording bounded, best effort, add retention); contract change rolled back |
| design, attempt 2 | revised design | approved (sign-off, api-contract-change) |
| plan | 8 tasks with its own ids and dependencies | two tasks both edit `ClickService.java` without depending on each other; the engine **serialized** them at expansion |
| impl-link-resolver-expiry-fix | issue #1 fix | compiled; accepted |
| impl-db-migration-clicks-table | `V2__create_clicks_table.sql` | needs schema-migration **acceptance**: the demonstration decisions were written for the fixture's node ids, so nothing covered this request; rolled back and the run **paused** |
| impl-async-ua-parser-config | adds `ua-parser-java` to `pom.xml` | needs **authorization before execution**; requested, not granted; nothing was written or built |

Totals: 7 nodes accepted, 10 attempts, 2 rollbacks, 8 model calls, about 141k input / 30k output
tokens, 4 min 24 s, status `PAUSED`.

## What it shows, and what it does not
- The governance holds with real model output: grounding, rejection loop, overlap
  serialization, and fail-closed approvals (an unforeseen migration and a new third-party
  dependency stopped the run instead of being approved by a script).
- It does not show that the live model completes the scenario: the remaining tasks, tests,
  verification and release were not reached. Completing it needs a human to review the migration
  and decide on the `ua-parser-java` dependency (`--approvals interactive`).
