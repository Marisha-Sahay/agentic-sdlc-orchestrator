# ADR-0004: Acceptance is decided by deterministic gates

Status: accepted, 2026-10-28

## Context
"LLM-as-judge" is a common pattern, but a model grading a model's output is neither reproducible
nor defensible as a release control.

## Decision
Every exit gate is code or a tool: the compiler, unit and integration tests, Checkstyle, the
coverage gate, JSON-contract validation, plan DAG validation, impact grounding against a
JavaParser index, OpenAPI compatibility, docs coverage and a pattern-based security scanner.
Model reviews (security reviewer) are advisory and surface at release approval.

## Alternatives
- Model reviewers as blocking gates: catches semantic issues but adds non-determinism and false
  positives that stall the pipeline.

## Consequences
- A gate result is reproducible and comes with evidence (tool output), which doubles as the
  feedback that lets the next attempt fix the problem.
- Semantic quality beyond what tools check is left to the human checkpoints (design, release).
- Gates are fail-closed: a gate that throws counts as failed.
