# Architecture decision records

| # | Decision | Area |
|---|---|---|
| [0001](0001-custom-graph-engine.md) | Hand-written graph engine in pure Java 21 instead of an agent framework | Orchestration |
| [0002](0002-event-log-as-source-of-truth.md) | One hash-chained event log is the source of truth | Observability |
| [0003](0003-agents-propose-engine-applies.md) | Agents propose; only the engine writes | Autonomy |
| [0004](0004-deterministic-gates.md) | Acceptance is decided by deterministic gates, not model judgment | Validation |
| [0005](0005-replanning-by-content-hash.md) | Re-planning by content-hash invalidation | Orchestration |
| [0006](0006-git-checkpoints-for-rollback.md) | Git checkpoints in a private run workspace for rollback | Reliability |
| [0007](0007-non-optional-policy-approvals.md) | Policy-required approvals cannot be opted out; fail closed | Governance |
| [0008](0008-positional-record-replay.md) | Positional record/replay of model calls | Reproducibility |
| [0009](0009-run-time-graph-expansion.md) | The planner expands the graph at run time | Decomposition |
| [0010](0010-langchain4j-structured-output.md) | LangChain4j as the model client, with schema-constrained output | Agents |
| [0011](0011-random-base62-codes.md) | Random base62 codes with a database uniqueness guarantee | Service |
| [0012](0012-302-redirects.md) | 302 redirects with `Cache-Control: no-store` | Service |
| [0013](0013-redirect-cache-and-expiry.md) | In-process redirect cache; expiry re-checked on every hit | Service |
| [0014](0014-postgres-jdbcclient-testcontainers.md) | PostgreSQL with `JdbcClient` and Flyway, tested with Testcontainers | Service |
| [0015](0015-in-process-rate-limiting.md) | In-process token-bucket rate limiting on link creation | Service |
