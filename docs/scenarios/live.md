# Live-model validation

**Status: partial.** One live run was recorded (brownfield, Gemini 2.5 Flash through LangChain4j,
2026-10-29). It ran the real model through the first half of the workflow and then paused,
fail-closed, at the first high-impact change no reviewer had decided. A human then resumed it as
the reviewer; that session ended when the provider's daily quota ran out (see below). No live run
has reached release; greenfield and ambiguous have not been run live. The completed scenario
evidence uses hand-authored replies (see [README](README.md)).

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

## Second session: a human reviewer (2026-10-29, 16:56-17:02 UTC)

Resumed with `--approvals interactive`; the decisions below are a human's (`human:marishasahay`
in the audit log). The committed evidence above is from the first session only, because this
session's log was damaged by the bug described last.

| Step | What happened |
|---|---|
| Migration (acceptance) | The reviewer **rejected** the model's `V2__create_clicks_table.sql`: "I dont need an index ... `idx_clicks_clicked_at`". The model's next attempt removed exactly that index and kept the others. Sound call: the approved live design stores clicks indefinitely, so no purge would use it. |
| `ua-parser-java` (authorization) | The reviewer **authorized** the dependency. Nothing had been built before that. The compile gate then failed: the model had asked for `com.github.ua-parser:ua-parser-java:1.5.0`, which does not exist in Maven Central. Rolled back, nothing accepted. Authorization is the moment to check coordinates (or to refuse: the fixture design classifies user agents with a few substring rules). |
| Provider quota | Gemini's free-tier daily quota ran out ("retry in 838 min"). The task's remaining attempts failed immediately, the failure threshold stopped the run, 11 downstream nodes were skipped: `FAILED`, 7 accepted nodes unchanged. |
| Scripted vs human review | In the first session the scripted demonstration decision approved the live model's second design although it did not add the retention policy the first rejection asked for (it keeps clicks indefinitely). Scripted decisions cannot judge content; live runs need a human reviewer. |

**Bug found and fixed.** The first resume (started 16:56:57) was still running when a second
resume started at 16:59:06: after Ctrl-C a run finishes its in-flight attempts (a 153 s model call
and a reviewer prompt) before exiting. Both processes appended to the same log, so `verify-log`
reports the chain broken at event 85 (sequence numbers 85-90 appear twice), and the first process
rolled back the shared workspace while the second was running. A run directory is now locked by
the process that runs or resumes it; a second process is refused with the owner's pid
(`RunLockTest`, and checked across two real processes), and Ctrl-C says the process is finishing
in-flight work. The damaged run cannot be resumed (resume refuses a broken chain, as intended);
the next live attempt is a fresh run after the quota resets, with a human reviewer.

The screen recording of this session shows only the macOS lock screen; the audit log is the
record of the decisions.
