# Scenario: greenfield (build the service from a requirement)

```bash
./agentic run greenfield --llm record --approvals interactive
./agentic run greenfield
```

## Requirement ([scenarios/greenfield.yaml](../../scenarios/greenfield.yaml))
A URL shortener for customer communications: create short links (optional alias and expiry),
redirect, look up and delete links; safe on the public internet (input validation, phishing
tricks such as credentials-in-URL and redirect loops, rate-limited creation); consistent
machine-readable errors; an OpenAPI contract and docs; PostgreSQL.

## Starting point
[`scenarios/scaffolds/greenfield`](../../scenarios/scaffolds/greenfield): an empty Spring Boot 4
project with the build (Checkstyle, JaCoCo 80% gate, Testcontainers), `application.yml`, the
application class and an integration-test base class. No domain code, no API contract.

## What it demonstrates
- **Requirement → design from nothing:** the architect writes the first OpenAPI contract (no
  baseline, so `api-compatibility` validates the document only) and ADR-style decisions; human
  design sign-off.
- **Decomposition depth:** the planner splits the service into ordered tasks (schema and
  repository before service, service before controllers); independent ones run in parallel.
- **Build-up under gates:** every task must compile against the tasks before it; the test author
  must make unit tests pass; `verify` enforces integration tests on PostgreSQL and the coverage
  gate, with bounded self-repair.
- **Compare with the hand-reviewed baseline:** the run's output can be diffed against
  [`url-shortener`](../../url-shortener) (built with AI assistance and human review) to judge
  output quality.

## Decomposition
The same template as brownfield without `codebase-scan` and `impact-analysis`:
`intake → requirements → design 👤 → plan ⇒ impl-* → integrate → {tests, docs, security-review}
→ verify → release 👤`.

## Results

*Pending the live recording (needs `GEMINI_API_KEY` / `AGENTIC_MODEL`).*
