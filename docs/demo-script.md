# Demo script (10-15 minutes)

Offline except the optional live step. Before: `mvn -q verify` once, Docker running.

## 1. Requirement → plan (3 min)
```bash
./agentic run brownfield --approvals scripted --run-id demo
```
- Show the requirement ([scenarios/brownfield.yaml](../scenarios/brownfield.yaml)) and the
  normalized spec: `.runs/demo/artifacts/requirements.spec/v1.txt`. Point at **Q-1**: "every
  redirect" vs "no noticeable latency" is raised, not guessed.
- `codebase-scan` and `requirements` start together; `impact-analysis` joins both and is checked
  against the JavaParser index.
- `plan expanded into [impl-expiry-fix, impl-click-storage, impl-click-recording,
  impl-click-statistics]`: two waves of parallel tasks.

## 2. Execution with rejection and recovery (3 min)
- **design rejected** by the (demonstration) reviewer: "make recording bounded, say it is best
  effort, add retention". The contract change is rolled back; attempt 2 addresses it and is
  approved. Both decisions are bound to the proposal hash.
- `impl-click-storage` stops for **acceptance** of the V2 migration; the validated tree hash is in
  the request.
- For failures under fire, run `./agentic run drill`: hallucinated file rejected by grounding, an
  edit to the applied V1 migration blocked by policy, a compile failure rolled back, a model outage
  handled by fallback, a reviewer amendment that re-plans exactly two nodes.
- For authorization **before** execution: `./agentic run greenfield --approvals scripted` and watch
  `impl-web-api waiting for authorization: [dependency-change]` before its `pom.xml` is written.

## 3. Validated patch → human approval (3 min)
- `final-validation` re-runs `mvn verify` (unit + PostgreSQL ITs + Checkstyle + coverage), the
  security scan, structural contract compatibility and docs coverage on one tree, and checks that
  the security review still describes it.
- `release` (entry gate `validated-tree`) asks for sign-off on that tree. Open
  `.runs/demo/release.patch` and the release notes' "Validation evidence" section.
- Be the human: `./agentic run brownfield --approvals interactive --run-id demo-human`; at
  `design` press `d` (diff), `r` and type feedback, or `e` to amend `requirements.spec` (a broken
  amendment is refused and you are asked again).

## 4. Evidence and recovery (2 min)
```bash
open .runs/demo/report.html
./agentic verify-log demo
./agentic check-evidence drill greenfield brownfield ambiguous
```
Tamper with an artifact (`echo x >> .runs/demo/artifacts/plan.tasks/v1.txt`) and run
`./agentic resume demo`: refused, the artifact no longer matches its recorded hash.

## 5. The output (1 min)
```bash
tools/final-service.sh && cd target/final-service && docker compose up -d && mvn spring-boot:run
```
Follow a link a few times, then `GET /api/v1/links/{code}/stats`. Explain the best-effort trade-off
([ADR-0017](adr/0017-best-effort-click-analytics.md)) and the scale boundaries in its README.

## 6. Close (1 min)
What is proven (orchestration, governance, the code) and what is not (model quality in the
committed evidence, production load): [FINAL_SUMMARY.md](../FINAL_SUMMARY.md).
