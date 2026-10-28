# AI collaboration log

The brief asks for the work to be done with AI assistance while demonstrating engineering
judgment. This log records how AI was used and, more importantly, where a decision was made,
corrected or overridden. The project was built with Claude Code (Claude Opus 5.5) as a pair
programmer; architecture decisions, scope and final review were mine.

## How AI was used
- **Design partner.** Options and trade-offs for the orchestration model were discussed before
  any code; the choices are recorded as [ADRs](adr).
- **Implementation.** The orchestrator was written incrementally with tests for each mechanism
  before moving on. The v1 service baseline was delegated to a sub-agent with a detailed brief
  (stack, design decisions, quality gates, the intentional defect) and reviewed before commit.
- **Verification.** Every change was compiled, tested and gated (Checkstyle, 80% coverage) before
  commit; claims in the docs were checked against the code and the run logs.

## Decisions and corrections

| # | Situation | Judgment |
|---|---|---|
| 1 | Use an agent framework (LangGraph4j) for orchestration? | No: the orchestration layer is the thing being evaluated, so its mechanics stay explicit and owned ([ADR-0001](adr/0001-custom-graph-engine.md)). LangChain4j only as the model client. |
| 2 | Should a model judge whether output is good enough? | No: acceptance is deterministic tools; model review is advisory ([ADR-0004](adr/0004-deterministic-gates.md)). |
| 3 | Allow a workflow author to disable approvals per node? | No: `ApprovalMode` has no "never"; policy approvals cannot be opted out ([ADR-0007](adr/0007-non-optional-policy-approvals.md)). |
| 4 | Agents writing files directly with tools | Rejected: agents propose, the engine applies, so policy runs on intent and rollback is exact ([ADR-0003](adr/0003-agents-propose-engine-applies.md)). |
| 5 | A test caught that skip propagation stopped after a safe stop (dependents stayed PENDING) | Fixed: skip propagation is bookkeeping and always runs. |
| 6 | Review found budgets were only checked between scheduling rounds, so one node's retry loop could overrun the attempt budget | Fixed: runners check budgets before every attempt; covered by `attemptBudgetStopsARunawayRetryLoop`. |
| 7 | A test against the real service showed the codebase indexer missed `@RequestMapping(BASE_PATH)` (constant, not literal) | Fixed: the indexer resolves constants and concatenations; the test runs against the real baseline, not a toy. |
| 8 | Designing the drill exposed that replay keys collide when re-planning re-runs a node | Fixed: keys include the node's execution number, restored from the log on resume ([ADR-0008](adr/0008-positional-record-replay.md)). |
| 9 | The drill showed design flagged as an "API contract change" when it rewrote the contract unchanged | Fixed: change-control rules evaluate effective changes only; scope and secret rules still see every write. |
| 10 | The imported Checkstyle ruleset rejected idiomatic empty record bodies (`record Foo(int a) {}`) | Aligned one rule with Google's own configuration (`alone_or_singleline`) instead of contorting code. |
| 11 | Coverage gate failed on the agents module | Not lowered: wrote agent contract tests running each agent in a real engine with a scripted model. Only the CLI composition root is excluded, as is common for `main` classes. |
| 12 | Sub-agent used `ON CONFLICT DO NOTHING` instead of catching `DuplicateKeyException` for code collisions | Accepted: avoids exception-driven retries and PostgreSQL's aborted-transaction state ([ADR-0011](adr/0011-random-base62-codes.md)). |
| 13 | H2 vs PostgreSQL for tests | PostgreSQL with Testcontainers: tests must exercise the real dialect and the migrations agents write ([ADR-0014](adr/0014-postgres-jdbcclient-testcontainers.md)). |
| 14 | `.env` loaded with `source` broke on `<placeholder>` values (and would execute arbitrary content) | Changed to parsing `.env` as data; placeholders are treated as unset. |
| 15 | Shutdown hook released before the report was written, so Ctrl-C could lose the report | Fixed: the hook waits until the log, report and summary are written. |
| 16 | A commit went in while a test was red (the pipeline status came from `tail`, not Maven) | Amended before anything was pushed; build checks now use Maven's exit code. |
| 17 | The baseline keeps one known defect (cached expiry) | Deliberate and documented, so the brownfield bug fix is a real fix to real code, not a toy. |
| 18 | The assessment brief is marked internal | Not committed (`*.pdf` ignored); requirements restated in my own words in [requirements.md](requirements.md). |

## What AI was not trusted with
- Deciding that work is done: only gates and humans do that.
- Secrets: the key is never in prompts, logs, cassettes or commits; agents are blocked from
  writing anything that looks like a credential.
- Its own guardrails: CI, Checkstyle and policy files are protected from agent edits.
