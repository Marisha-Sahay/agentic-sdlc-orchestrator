#!/usr/bin/env python3
"""Generates the governance drill: scenarios/drill.yaml and cassettes/drill/*.json.

The drill runs the real brownfield workflow (same templates, agents, policy, gates and Maven
builds) with HAND-AUTHORED model replies instead of a live model. Every fixture is labelled
model="fixture:hand-authored" and promptSha256="*", so the audit log reports mode "fixture".
Faults are planted on purpose to exercise the controls deterministically:

  impact-analysis  attempt 1 cites a file that does not exist      -> grounding gate, retry
  design           reviewer amends requirements.spec at approval   -> re-plan (impact + design)
  impl-resolver..  attempt 1 forgets to create CachedLink.java     -> compile gate, rollback, retry
  impl-expiry-idx  attempt 1 edits the applied V1 migration        -> policy BLOCK, retry
  impl-expiry-idx  attempt 2 adds V2 migration                     -> human approval (schema change)
  docs             model returns 503 twice                         -> fallback to template writer

Usage: python3 tools/make-drill-cassettes.py   (from the repository root)
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "tools" / "drill-fixtures"
OUT = ROOT / "cassettes" / "drill"
PKG = "src/main/java/io/github/marishasahay/shortener"
TEST_PKG = "src/test/java/io/github/marishasahay/shortener"
CONTRACT = (ROOT / "url-shortener" / "api" / "openapi.yaml").read_text()
V1 = "src/main/resources/db/migration/V1__create_links.sql"
V2 = "src/main/resources/db/migration/V2__index_links_expires_at.sql"


def src(name):
    return (SRC / name).read_text()


def fr(id_, desc, criteria, priority="MUST"):
    return {"id": id_, "description": desc, "priority": priority, "acceptanceCriteria": criteria}


FR1 = fr("FR-1", "GET /{code} returns 410 Gone once a link's expiresAt has passed, even when the "
         "link is in the redirect cache (issue #1).",
         ["Given a cached link with expiresAt T, when GET /{code} is called at or after T, then "
          "the response is 410 Gone",
          "Given a cached link, when GET /{code} is called before its expiresAt, then it still "
          "redirects with 302 from the cache"])
FR2 = fr("FR-2", "Index links.expires_at so the planned expired-link cleanup job can find "
         "expired links without a full table scan.",
         ["A new Flyway migration creates a partial index on links(expires_at) for rows with an "
          "expiry", "Existing migrations are not modified"], "SHOULD")
FR3 = fr("FR-3", "Deleting a link stops it redirecting immediately, even if it is cached.",
         ["Given a cached link, when it is deleted, then GET /{code} returns 404 without waiting "
          "for the cache TTL"])

NFRS = [{"id": "NFR-1", "category": "performance",
         "description": "The fix adds no database access to the redirect cache-hit path.",
         "measure": "Unit test verifies the repository is called once across cached lookups"},
        {"id": "NFR-2", "category": "compatibility",
         "description": "No change to the public API contract.",
         "measure": "api-compatibility gate: all baseline operations kept"}]


def spec(frs):
    return {
        "title": "Fix cached-expiry bug and index link expiry",
        "problemStatement": "Expired links keep redirecting while they are in the redirect cache "
                            "because expiry is only checked when a link is loaded on a cache "
                            "miss. Separately, the upcoming cleanup job needs to find expired "
                            "links efficiently.",
        "goals": ["Expired links stop redirecting exactly at expiresAt",
                  "No extra latency on the redirect hot path"],
        "functionalRequirements": frs,
        "nonFunctionalRequirements": NFRS,
        "ambiguities": [],
        "assumptions": ["Expiry is evaluated with the application Clock, as today",
                        "A 410 response for an expired link is preferred over 404"],
        "outOfScope": ["The cleanup job itself", "Changing the cache TTL"],
        "risks": [{"description": "Cache entry type changes from String to a record",
                   "impact": "Every component that builds the cache must change together",
                   "mitigation": "Single bean definition; compile gate catches mismatches"}],
    }


SPEC_V1 = spec([FR1, FR2])
SPEC_V2 = spec([FR1, FR2, FR3])


def component(path, kind, change, reason):
    return {"path": path, "kind": kind, "change": change, "reason": reason}


IMPACT_OK = {
    "summary": "The defect is confined to LinkResolver: the cache stores only the target URL, so "
               "expiry cannot be re-checked on a hit. The cache value type changes to a record "
               "holding the expiry; the index is an additive migration.",
    "components": [
        component(f"{PKG}/link/LinkResolver.java", "cache", "MODIFY",
                  "check expiry on every lookup, not only on load"),
        component(f"{PKG}/config/CacheConfiguration.java", "config", "MODIFY",
                  "cache value type becomes CachedLink"),
        component(f"{PKG}/link/CachedLink.java", "domain", "ADD",
                  "cache value carrying target URL and expiry"),
        component(V2, "migration", "ADD", "partial index on expires_at"),
        component(f"{TEST_PKG}/link/LinkResolverTest.java", "test", "MODIFY",
                  "regression tests for issue #1"),
    ],
    "apiChanges": ["None; GET /{code} already documents 410 for expired links"],
    "dataChanges": ["V2: create index idx_links_expires_at on links(expires_at) "
                    "where expires_at is not null"],
    "dataFlows": ["Redirect hit path: cache lookup now followed by an in-memory expiry check"],
    "risks": [{"description": "Index build on a large table", "impact": "lock during migration",
               "mitigation": "table is small today; use CREATE INDEX CONCURRENTLY in production"}],
    "testStrategy": "Unit tests with MutableClock for hit/miss/boundary expiry; Flyway applies V2 "
                    "in the Testcontainers integration tests.",
}
IMPACT_HALLUCINATED = dict(IMPACT_OK, components=[
    component(f"{PKG}/link/LinkCache.java", "cache", "MODIFY", "add expiry check to the cache"),
] + IMPACT_OK["components"][1:])
IMPACT_V2 = dict(IMPACT_OK, summary=IMPACT_OK["summary"] + " FR-3 (deleted links) is already "
                 "satisfied by LinkService.delete evicting the cache entry; it needs a "
                 "regression test only.")


def design(extra_decision=None):
    decisions = [
        {"title": "Where to enforce expiry", "choice": "Re-check expiry on every lookup in "
         "LinkResolver, using the cached expiresAt", "rationale": "Correct at the exact instant, "
         "O(1), no database access on hits", "alternatives": [
             "Per-entry Caffeine expiry (correct but relies on the cache ticker, not the "
             "application Clock, which makes it hard to test)",
             "Shorter TTL (reduces but does not remove the window)"]},
        {"title": "Schema change", "choice": "New V2 migration with a partial index",
         "rationale": "Applied migrations are immutable; partial index keeps it small",
         "alternatives": ["Full index on expires_at", "Edit V1 (rejected: breaks Flyway "
                          "checksums in existing environments)"]},
    ]
    if extra_decision:
        decisions.append(extra_decision)
    return {
        "overview": "Store (targetUrl, expiresAt) in the redirect cache and re-check expiry on "
                    "every lookup; add a partial index on links.expires_at via V2. No API change.",
        "components": [
            {"name": "LinkResolver", "responsibility": "Resolve codes; enforce expiry on hit and "
             "miss", "files": [f"{PKG}/link/LinkResolver.java"]},
            {"name": "CachedLink", "responsibility": "Cache value with expiry",
             "files": [f"{PKG}/link/CachedLink.java"]},
            {"name": "CacheConfiguration", "responsibility": "Typed cache bean",
             "files": [f"{PKG}/config/CacheConfiguration.java"]},
        ],
        "openApiYaml": CONTRACT,
        "dataModel": "links: unchanged columns; new partial index idx_links_expires_at "
                     "(expires_at) WHERE expires_at IS NOT NULL",
        "decisions": decisions,
        "risks": ["Clock skew across instances can shift expiry by the skew; acceptable"],
    }


DESIGN_V2 = design({"title": "Deleted links in cache (FR-3, reviewer amendment)",
                    "choice": "Keep evict-on-delete; add a regression test",
                    "rationale": "LinkService.delete already evicts synchronously; the gap was "
                                 "missing test coverage, not behaviour",
                    "alternatives": ["Tombstone entries in the cache"]})

PLAN = {
    "rationale": "Two independent tasks: the resolver fix and the migration touch disjoint files, "
                 "so they can be implemented in parallel.",
    "tasks": [
        {"id": "resolver-expiry-check", "title": "Enforce expiry on cache hits",
         "description": "Introduce CachedLink, change the cache value type and re-check expiry "
                        "in LinkResolver.resolve.",
         "requirementIds": ["FR-1", "FR-3", "NFR-1"],
         "files": [f"{PKG}/link/CachedLink.java", f"{PKG}/link/LinkResolver.java",
                   f"{PKG}/config/CacheConfiguration.java"],
         "dependsOn": [], "acceptanceCriteria": ["compiles", "expired cached link -> 410"]},
        {"id": "expiry-index", "title": "Index links.expires_at",
         "description": "Add Flyway migration V2 with a partial index.",
         "requirementIds": ["FR-2"], "files": [V2], "dependsOn": [],
         "acceptanceCriteria": ["migration applies on PostgreSQL 17"]},
    ],
}


def proposal(summary, files, decisions=()):
    return {"summary": summary, "files": [{"path": p, "content": c} for p, c in files],
            "decisions": list(decisions)}


CODE_BROKEN = proposal("Check expiry on cache hits via a CachedLink value", [
    (f"{PKG}/link/LinkResolver.java", src("LinkResolver.java")),
    (f"{PKG}/config/CacheConfiguration.java", src("CacheConfiguration.java")),
])
CODE_OK = proposal("Check expiry on cache hits via a CachedLink value (adds the missing record)", [
    (f"{PKG}/link/CachedLink.java", src("CachedLink.java")),
    (f"{PKG}/link/LinkResolver.java", src("LinkResolver.java")),
    (f"{PKG}/config/CacheConfiguration.java", src("CacheConfiguration.java")),
], [{"title": "Expired entry handling", "choice": "Invalidate the entry when found expired",
     "rationale": "Frees memory early; next lookup hits the database once and returns 410",
     "alternatives": ["Leave it to the TTL"]}])
V1_SQL = (ROOT / "url-shortener" / V1).read_text()
MIGRATION_EDITS_V1 = proposal("Add the expiry index to the links migration", [
    (V1, V1_SQL + "\ncreate index idx_links_expires_at on links (expires_at);\n")])
MIGRATION_OK = proposal("Add V2 migration with a partial index on expires_at", [
    (V2, src("V2__index_links_expires_at.sql"))])
TESTS = proposal("Regression tests for issue #1 (cache-hit expiry, boundary) and FR-3 (delete)", [
    (f"{TEST_PKG}/link/LinkResolverTest.java", src("LinkResolverTest.java"))])
SECURITY = {"summary": "No new attack surface; expiry is now enforced consistently.",
            "findings": [{"severity": "LOW", "source": "reviewer", "category": "logging",
                          "location": "LinkResolver", "description": "Expired-link 410s are not "
                          "logged, so abuse of expired campaign links is invisible",
                          "recommendation": "Count 410s in a metric"}]}
RELEASE = {
    "title": "Fix: expired links no longer redirect from cache (issue #1); index expires_at",
    "summary": "Expiry is enforced on every redirect, including cache hits. A partial index on "
               "links.expires_at prepares the cleanup job. No API change.",
    "changes": ["Redirect cache stores CachedLink(targetUrl, expiresAt)",
                "LinkResolver re-checks expiry on hits and evicts expired entries",
                "V2 migration: idx_links_expires_at (partial)"],
    "risks": ["Index creation locks writes briefly on large tables; use CONCURRENTLY in prod",
              "Reviewer advisory: 410s are not yet counted in metrics"],
    "rollbackPlan": "Revert the commit; V2 is additive and can stay, or drop the index with "
                    "DROP INDEX idx_links_expires_at.",
    "testingSummary": "Unit tests for hit/miss/boundary expiry and delete; full verify with "
                      "Testcontainers integration tests applying V2.",
    "followUps": ["Expired-link cleanup job", "Metric for 410 responses"],
}

FIXTURES = {
    "requirements/e1/attempt-1/primary/requirements-analyst": SPEC_V1,
    "impact-analysis/e1/attempt-1/primary/impact-analyst": IMPACT_HALLUCINATED,
    "impact-analysis/e1/attempt-2/primary/impact-analyst": IMPACT_OK,
    "design/e1/attempt-1/primary/architect": design(),
    "impact-analysis/e2/attempt-1/primary/impact-analyst": IMPACT_V2,
    "design/e2/attempt-1/primary/architect": DESIGN_V2,
    "plan/e1/attempt-1/primary/planner": PLAN,
    "impl-resolver-expiry-check/e1/attempt-1/primary/coder": CODE_BROKEN,
    "impl-resolver-expiry-check/e1/attempt-2/primary/coder": CODE_OK,
    "impl-expiry-index/e1/attempt-1/primary/coder": MIGRATION_EDITS_V1,
    "impl-expiry-index/e1/attempt-2/primary/coder": MIGRATION_OK,
    "tests/e1/attempt-1/primary/test-author": TESTS,
    "security-review/e1/attempt-1/primary/security-reviewer": SECURITY,
    "release/e1/attempt-1/primary/release-manager": RELEASE,
}
OUTAGES = ["docs/e1/attempt-1/primary/tech-writer", "docs/e1/attempt-2/primary/tech-writer"]


def cassette(key, response=None, error=None):
    return {"key": key, "agent": key.rsplit("/", 1)[1], "model": "fixture:hand-authored",
            "promptSha256": "*", "response": None if response is None else json.dumps(response),
            "latencyMs": 0, "inputTokens": -1, "outputTokens": -1,
            "recordedAt": "2026-10-28T00:00:00Z", "error": error}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.json"):
        old.unlink()
    for key, response in FIXTURES.items():
        (OUT / (key.replace("/", "_") + ".json")).write_text(
            json.dumps(cassette(key, response), indent=2) + "\n")
    for key in OUTAGES:
        (OUT / (key.replace("/", "_") + ".json")).write_text(json.dumps(cassette(
            key, error="503 UNAVAILABLE: model overloaded (simulated outage)"), indent=2) + "\n")
    scenario = f"""# GENERATED by tools/make-drill-cassettes.py - do not edit by hand.
name: drill
kind: BROWNFIELD
title: Governance drill - fix issue #1 with planted faults (hand-authored model, no API key)
baseline: url-shortener
requirement: |
  Fix issue #1: an expired link keeps redirecting while it is in the redirect cache. Once a
  link's expiresAt has passed, GET /{{code}} must return 410 Gone even if it was cached.
  Also add a database index on links.expires_at for the planned expired-link cleanup job.
scriptedApprovals:
  design:
    - approve: true
      comment: >-
        Approved with an amendment: deleted links must also stop redirecting immediately when
        cached (added FR-3). Re-plan downstream work.
      amendments:
        requirements.spec: |
{indent(json.dumps(SPEC_V2, indent=2), 10)}
    - approve: true
      comment: FR-3 addressed by the evict-on-delete decision plus a regression test.
  release:
    - approve: true
      comment: Approved after reviewing the patch, the V2 migration and the traceability matrix.
"""
    (ROOT / "scenarios" / "drill.yaml").write_text(scenario)
    print(f"wrote {len(FIXTURES) + len(OUTAGES)} cassettes to {OUT.relative_to(ROOT)} "
          "and scenarios/drill.yaml")


def indent(text, spaces):
    return "\n".join(" " * spaces + line for line in text.splitlines())


if __name__ == "__main__":
    main()
