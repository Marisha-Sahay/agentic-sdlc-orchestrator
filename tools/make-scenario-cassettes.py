#!/usr/bin/env python3
"""Generates hand-authored model replies for the greenfield, brownfield and ambiguous scenarios.

These are FIXTURES, not recordings: every reply is labelled model="fixture:hand-authored" and
promptSha256="*", so the audit log reports mode "fixture". They let the three scenarios run end to
end through the real workflow (templates, policy, gates, Maven builds, approvals) without an API
key, and make CI reproducible. They demonstrate the orchestration, not model capability; live
recordings are a separate cassette set (see docs/scenarios).

The code in the replies is real and validated by the run's own gates:
  greenfield  builds url-shortener/ (v1) from the scaffold: the replies carry v1's files
  brownfield  evolves v1 into v2: tools/scenario-fixtures/v2 plus the issue #1 fix from
              tools/drill-fixtures
  ambiguous   the analytics part of v2 only, after a clarification round

Usage: python3 tools/make-scenario-cassettes.py   (from the repository root)
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
V1 = ROOT / "url-shortener"
V2 = ROOT / "tools" / "scenario-fixtures" / "v2"
FIX = ROOT / "tools" / "drill-fixtures"
PKG = "src/main/java/io/github/marishasahay/shortener"
TPKG = "src/test/java/io/github/marishasahay/shortener"
V2_SQL = "src/main/resources/db/migration/V2__create_link_clicks.sql"


def read(base, path):
    return (base / path).read_text()


def v1(path):
    return read(V1, path)


def v2(path):
    return read(V2, path)


def fr(id_, desc, criteria, priority="MUST"):
    return {"id": id_, "description": desc, "priority": priority, "acceptanceCriteria": criteria}


def nfr(id_, category, desc, measure):
    return {"id": id_, "category": category, "description": desc, "measure": measure}


def risk(description, impact, mitigation):
    return {"description": description, "impact": impact, "mitigation": mitigation}


def decision(title, choice, rationale, alternatives):
    return {"title": title, "choice": choice, "rationale": rationale, "alternatives": alternatives}


def component(path, kind, change, reason):
    return {"path": path, "kind": kind, "change": change, "reason": reason}


def task(id_, title, description, reqs, files, deps=(), criteria=("compiles",)):
    return {"id": id_, "title": title, "description": description, "requirementIds": list(reqs),
            "files": list(files), "dependsOn": list(deps), "acceptanceCriteria": list(criteria)}


def proposal(summary, files, decisions=()):
    return {"summary": summary, "files": [{"path": p, "content": c} for p, c in files],
            "decisions": list(decisions)}


def files_from(reader, paths):
    return [(p, reader(p)) for p in paths]


def cassette(key, response):
    return {"key": key, "agent": key.rsplit("/", 1)[1], "model": "fixture:hand-authored",
            "promptSha256": "*", "response": json.dumps(response), "latencyMs": 0,
            "inputTokens": -1, "outputTokens": -1, "recordedAt": "2026-10-29T00:00:00Z",
            "error": None}


def write(scenario, fixtures):
    out = ROOT / "cassettes" / scenario
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.json"):
        old.unlink()
    for key, response in fixtures.items():
        (out / (key.replace("/", "_") + ".json")).write_text(
            json.dumps(cassette(key, response), indent=2) + "\n")
    print(f"wrote {len(fixtures)} cassettes to cassettes/{scenario}")


def design(overview, components, contract, data_model, decisions, risks):
    return {"overview": overview, "components": components, "openApiYaml": contract,
            "dataModel": data_model, "decisions": decisions, "risks": risks}


def release(title, summary, changes, risks, rollback, testing, follow_ups):
    return {"title": title, "summary": summary, "changes": changes, "risks": risks,
            "rollbackPlan": rollback, "testingSummary": testing, "followUps": follow_ups}


# --------------------------------------------------------------------------------------------
# Greenfield: the scaffold becomes the v1 service.
# --------------------------------------------------------------------------------------------

def greenfield():
    spec = {
        "title": "URL shortener service for customer communications",
        "problemStatement": "Customer messages need short, safe links that redirect to long URLs, "
                            "can expire or be deleted, and cannot easily be abused for phishing.",
        "goals": ["Short links that redirect reliably", "Safe to expose on the public internet",
                  "A published, machine-readable contract"],
        "functionalRequirements": [
            fr("FR-1", "Create a short link for an absolute http(s) URL, with an optional custom "
               "alias and an optional expiry in the future.",
               ["Given a valid URL, when POST /api/v1/links, then 201 with code and shortUrl",
                "Given a taken alias, when creating it again, then 409",
                "Given an expiry in the past, then 400 naming expiresAt"]),
            fr("FR-2", "GET /{code} redirects to the target URL; expired links return 410, "
               "unknown or deleted links 404.",
               ["Given an active link, then 302 with Location and Cache-Control: no-store",
                "Given an expired link, then 410", "Given a deleted link, then 404"]),
            fr("FR-3", "Read a link's metadata and delete a link; codes are never reused.",
               ["GET /api/v1/links/{code} returns 200 with the link",
                "DELETE returns 204 and the code then returns 404 and cannot be re-created"]),
            fr("FR-4", "Reject phishing tricks: user info in the URL, redirect loops to the "
               "shortener itself, non-http schemes, reserved aliases.",
               ["https://bank.com@evil.com is rejected with 400",
                "A URL on the shortener's own host is rejected with 400"]),
            fr("FR-5", "Rate-limit link creation per client.",
               ["More than the configured creations per period return 429 with Retry-After"]),
            fr("FR-6", "Every error is an RFC 9457 problem document; the API has an OpenAPI 3 "
               "contract.", ["Validation errors list the invalid fields",
                             "api/openapi.yaml documents every operation"]),
        ],
        "nonFunctionalRequirements": [
            nfr("NFR-1", "security", "Codes are not enumerable.",
                "Codes are 7 random base62 characters from SecureRandom (unit test)"),
            nfr("NFR-2", "reliability", "Data lives in PostgreSQL with versioned migrations.",
                "Flyway migration applied by the Testcontainers integration tests"),
            nfr("NFR-3", "quality", "Production-quality build.",
                "Checkstyle passes and line coverage >= 80% in mvn verify"),
        ],
        "ambiguities": [],
        "assumptions": ["302 rather than 301 so expiry, deletion and future analytics keep "
                        "working", "Deletion is a soft delete so codes are never reused",
                        "No authentication in this iteration; the management API sits behind a "
                        "trusted gateway"],
        "outOfScope": ["Click analytics", "User accounts and link ownership"],
        "risks": [risk("Abuse for phishing", "brand damage", "URL policy and rate limiting"),
                  risk("Per-instance rate limits", "N instances allow N x the limit",
                       "documented; shared store when scaled out")],
    }
    contract = v1("api/openapi.yaml")
    the_design = design(
        "A single Spring Boot service on PostgreSQL: a links table with soft delete, random "
        "base62 codes with insert-on-conflict retries, a Caffeine redirect cache, URL and alias "
        "policies, a per-client token bucket on creation, and RFC 9457 errors.",
        [{"name": "LinkService", "responsibility": "Create, read and delete links",
          "files": [f"{PKG}/link/LinkService.java"]},
         {"name": "LinkResolver", "responsibility": "Redirect lookups through a cache",
          "files": [f"{PKG}/link/LinkResolver.java"]},
         {"name": "RateLimiter", "responsibility": "Per-client token bucket",
          "files": [f"{PKG}/ratelimit/RateLimiter.java"]},
         {"name": "ProblemDetailsAdvice", "responsibility": "Consistent errors",
          "files": [f"{PKG}/web/ProblemDetailsAdvice.java"]}],
        contract,
        "links(id, code unique, target_url, created_at, expires_at, deleted_at); a check that "
        "expiry follows creation.",
        [decision("Code generation", "7 random base62 characters, ON CONFLICT DO NOTHING retry",
                  "Not enumerable, no coordination between instances",
                  ["Sequential ids encoded in base62 (enumerable)", "Hash of the URL"]),
         decision("Redirect status", "302 with Cache-Control: no-store",
                  "Browsers do not cache it, so deletion and expiry take effect",
                  ["301 (cached forever by browsers)"]),
         decision("Persistence", "JdbcClient with explicit SQL and Flyway",
                  "Small schema; SQL stays visible", ["JPA"])],
        ["Rate limits and cache are per instance"])
    plan = {
        "rationale": "Configuration and persistence have no dependencies and run in parallel; "
                     "rules depend on configuration; the service needs persistence and rules; the "
                     "web layer comes last because it wires everything.",
        "tasks": [
            task("configuration", "Typed configuration, clock and redirect cache",
                 "ShortenerProperties with validation, a Clock bean and the Caffeine cache.",
                 ["FR-2", "FR-5"], [f"{PKG}/config/ShortenerProperties.java",
                                    f"{PKG}/config/ClockConfiguration.java",
                                    f"{PKG}/config/CacheConfiguration.java",
                                    "src/main/resources/application.yml"]),
            task("persistence", "Links table and repository",
                 "V1 migration, Link record, JdbcClient repository with soft delete.",
                 ["FR-1", "FR-3", "NFR-2"],
                 ["src/main/resources/db/migration/V1__create_links.sql", f"{PKG}/link/Link.java",
                  f"{PKG}/link/LinkRepository.java"]),
            task("link-rules", "Code generation and URL/alias policies",
                 "Random base62 generator, target URL and alias policies, domain exceptions.",
                 ["FR-1", "FR-4", "NFR-1"],
                 [f"{PKG}/link/{n}.java" for n in [
                     "CodeGenerator", "RandomBase62CodeGenerator", "TargetUrlPolicy",
                     "AliasPolicy", "LinkConfiguration", "InvalidLinkRequestException",
                     "AliasAlreadyExistsException", "CodeGenerationException",
                     "LinkExpiredException", "LinkNotFoundException"]], ["configuration"]),
            task("link-service", "Link lifecycle and redirect resolution",
                 "LinkService (create, get, delete) and the cached LinkResolver.",
                 ["FR-1", "FR-2", "FR-3"],
                 [f"{PKG}/link/LinkService.java", f"{PKG}/link/LinkResolver.java"],
                 ["persistence", "link-rules"]),
            task("rate-limiting", "Per-client token bucket",
                 "Token bucket, limiter and interceptor for link creation.", ["FR-5"],
                 [f"{PKG}/ratelimit/{n}.java" for n in [
                     "ClientKeyResolver", "RateLimitConfiguration", "RateLimitDecision",
                     "RateLimitExceededException", "RateLimitInterceptor", "RateLimiter",
                     "TokenBucket"]], ["configuration"]),
            task("web-api", "Controllers, errors and the served contract",
                 "REST and redirect controllers, RFC 9457 advice, the contract endpoint for "
                 "Swagger UI (adds the springdoc UI dependency).",
                 ["FR-1", "FR-2", "FR-3", "FR-6"],
                 [f"{PKG}/link/LinkApiController.java", f"{PKG}/link/RedirectController.java",
                  f"{PKG}/link/CreateLinkRequest.java", f"{PKG}/link/LinkResponse.java",
                  f"{PKG}/ratelimit/RateLimitWebConfigurer.java",
                  f"{PKG}/web/ProblemDetailsAdvice.java", f"{PKG}/web/FieldViolation.java",
                  f"{PKG}/web/ApiContractController.java", "pom.xml"],
                 ["link-service", "rate-limiting"]),
        ],
    }
    fixtures = {
        "requirements/e1/attempt-1/primary/requirements-analyst": spec,
        "design/e1/attempt-1/primary/architect": the_design,
        "plan/e1/attempt-1/primary/planner": plan,
    }
    for t in plan["tasks"]:
        fixtures[f"impl-{t['id']}/e1/attempt-1/primary/coder"] = proposal(
            t["title"], files_from(v1, t["files"]))
    tests = sorted(str(p.relative_to(V1)) for p in (V1 / "src" / "test").rglob("*.java"))
    fixtures["tests/e1/attempt-1/primary/test-author"] = proposal(
        "Unit tests for policies, generator, service, resolver, rate limiter and web layer; "
        "Testcontainers integration tests for the lifecycle, repository, rate limit and actuator.",
        files_from(v1, tests))
    fixtures["docs/e1/attempt-1/primary/tech-writer"] = proposal(
        "README with setup, Swagger UI walkthrough, configuration and design notes; API guide.",
        files_from(v1, ["README.md", "API.md"]))
    fixtures["security-review/e1/attempt-1/primary/security-reviewer"] = {
        "summary": "Inputs are validated at the boundary, SQL is parameterised, codes come from "
                   "SecureRandom and errors leak no internals.",
        "findings": [{"severity": "MEDIUM", "source": "reviewer", "category": "access-control",
                      "location": "LinkApiController", "description": "Create and delete are "
                      "unauthenticated; anyone who knows a code can delete it",
                      "recommendation": "Protect the management API at the gateway"}]}
    fixtures["release/e1/attempt-1/primary/release-manager"] = release(
        "URL shortener v1", "Create, redirect, read and delete short links on PostgreSQL with "
        "abuse protection and an OpenAPI contract.",
        ["Link API and redirect", "URL and alias policies", "Per-client rate limit",
         "RFC 9457 errors", "Swagger UI on the checked-in contract"],
        ["Management API is unauthenticated (gateway required)",
         "Rate limit and cache are per instance"],
        "New service: undeploy; V1 can be dropped with the database.",
        "Unit and Testcontainers integration tests; coverage gate.",
        ["Authentication and link ownership", "Click analytics"])
    write("greenfield", fixtures)


# --------------------------------------------------------------------------------------------
# Brownfield and ambiguous: v1 -> v2.
# --------------------------------------------------------------------------------------------

FIX_FILES = {f"{PKG}/link/CachedLink.java": "CachedLink.java",
             f"{PKG}/link/LinkResolver.java": "LinkResolver.java",
             f"{PKG}/config/CacheConfiguration.java": "CacheConfiguration.java",
             f"{TPKG}/link/LinkResolverTest.java": "LinkResolverTest.java"}
STORAGE = [V2_SQL, f"{PKG}/click/AgentFamily.java", f"{PKG}/click/ClickEvent.java",
           f"{PKG}/click/ClickRepository.java", f"{PKG}/config/ShortenerProperties.java",
           "src/main/resources/application.yml"]
RECORDING = [f"{PKG}/click/ClickRecorder.java", f"{PKG}/click/ClickMaintenance.java",
             f"{PKG}/link/RedirectController.java"]
STATISTICS = [f"{PKG}/click/LinkStats.java", f"{PKG}/click/LinkStatsService.java",
              f"{PKG}/click/LinkStatsController.java"]
ANALYTICS_TESTS = [f"{TPKG}/{p}" for p in [
    "click/ClickEventTest.java", "click/ClickRecorderTest.java",
    "click/LinkStatsServiceTest.java", "click/ClickAnalyticsIT.java",
    "ContractConformanceIT.java", "ApplicationWiringTest.java", "IntegrationTestSupport.java",
    "link/LinkWebLayerTest.java"]]
FIX_TESTS = [f"{TPKG}/link/CachedLinkExpiryIT.java"]


def v2_or_fix(path):
    return read(FIX, FIX_FILES[path]) if path in FIX_FILES else v2(path)


def without_fix(text, replacements):
    for old, new in replacements:
        assert old in text, old[:70]
        text = text.replace(old, new)
    return text


AMBIGUOUS_README = [
    ("""8. Expiry: create a link whose `expiresAt` is a minute ahead and wait; `GET /{code}` returns 410,
   also if you followed the link before it expired (the cache re-checks expiry on every hit).""",
     """8. Expiry: create a link whose `expiresAt` is a minute ahead and wait; `GET /{code}` returns 410.
   If you followed the link before it expired, it keeps redirecting until its cache entry
   expires (up to 10 minutes): known issue #1, not addressed in this change."""),
    ("""- **Redirect cache.** `LinkResolver` keeps a Caffeine cache of `code -> (target URL, expiry)`
  (TTL and size configurable, statistics exported as `cache.*` metrics). Expiry is checked on
  every lookup, hit or miss, so a cached link stops redirecting exactly at `expiresAt` (issue #1);
  unknown codes are not cached.""",
     """- **Redirect cache.** `LinkResolver` keeps a Caffeine cache of `code -> target URL` (TTL and size
  configurable, statistics exported as `cache.*` metrics). On a miss it loads the link from the
  database and applies the not-found and expiry checks; unknown codes are not cached. Known issue
  #1: a cached link keeps redirecting after it expires until its entry's TTL ends."""),
    ("""| Expiry | Enforced on every redirect from the cached `expiresAt`, on every instance | Clock skew between instances shifts it by the skew |""",
     """| Expiry | Checked when a link is loaded into the cache | A cached link can redirect up to the cache TTL after it expires (issue #1) |"""),
]
AMBIGUOUS_CONTRACT = [(""" Expiry is enforced on every
        request, including when the link is cached.""", "")]
AMBIGUOUS_CHANGELOG = [("""
### Fixed
- Issue #1: an expired link no longer keeps redirecting while it is in the redirect cache; expiry
  is checked on every lookup.
""", "")]


def analytics_requirements(ambiguous_answers):
    frs = [
        fr("FR-1", "Record each successful redirect (timestamp, referrer host, coarse user-agent "
           "family) asynchronously, so recording never delays or fails the redirect.",
           ["Given a successful redirect, when the recorder flushes, then one click row exists",
            "Given a 404 or 410 response, then no click is recorded",
            "Given a full buffer, then the redirect still returns 302 and the drop is counted"]),
        fr("FR-2", "GET /api/v1/links/{code}/stats returns total clicks, the last click time and "
           "daily counts for the last N UTC days (default 7).",
           ["Given clicks on two of three days, when days=3, then daily is [n, 0, m]",
            "Given an unknown or deleted code, then 404",
            "Given days outside 1..90, then 400 naming days"]),
        fr("FR-3", "Store no raw IP address or user-agent string, and no referrer path or "
           "query.", ["The link_clicks table has no IP or user-agent column",
                      "A referrer https://a.example/x?u=1 is stored as a.example"]),
        fr("FR-4", "Delete click data older than 90 days.",
           ["Given a click older than the retention period, when the purge runs, it is gone"]),
    ]
    nfrs = [
        nfr("NFR-1", "performance", "Recording adds no database access to the redirect path.",
            "Unit test: record() only offers to an in-memory queue"),
        nfr("NFR-2", "compatibility", "Existing operations stay backward compatible.",
            "api-compatibility gate (structural) and contract conformance test"),
    ]
    if ambiguous_answers:
        nfrs.append(nfr("NFR-3", "performance", "Redirects sustain 2,000 requests/second with "
                        "p99 below 50 ms on the reference deployment.",
                        "Repeatable load test in tools/loadtest; reported, not a CI gate"))
    return frs, nfrs


BEST_EFFORT_QUESTION = {
    "id": "Q-1", "question": "Must every redirect be recorded durably, or is best-effort "
    "recording acceptable?", "whyItMatters": "'Every redirect' and 'no noticeable latency' "
    "conflict: a durable record needs a synchronous write or a durable queue on the hot path; a "
    "bounded in-memory buffer is fast but loses clicks when full or on a crash.",
    "options": ["Synchronous insert per redirect", "Durable queue (Kafka)",
                "Bounded in-memory buffer, drops counted"],
    "recommendedDefault": "Bounded in-memory buffer with counted drops; document the loss cases"}


def brownfield_spec():
    frs, nfrs = analytics_requirements(False)
    frs.append(fr("FR-5", "GET /{code} returns 410 once expiresAt has passed, even when the link "
                  "is cached (issue #1).",
                  ["Given a cached link with expiresAt T, at T the redirect returns 410",
                   "Given a cached link, when it is deleted, then the redirect returns 404"]))
    return {
        "title": "Click analytics and cached-expiry fix",
        "problemStatement": "The service records nothing about link usage, and expired links keep "
                            "redirecting while cached because expiry is only checked on a miss.",
        "goals": ["Per-link click statistics", "Expired links stop exactly at expiresAt"],
        "functionalRequirements": frs, "nonFunctionalRequirements": nfrs,
        "ambiguities": [BEST_EFFORT_QUESTION],
        "assumptions": ["Q-1 defaults to best effort unless the design review decides otherwise",
                        "Statistics are per link; no cross-link reporting"],
        "outOfScope": ["Dashboards", "Geo-location", "Authentication"],
        "risks": [risk("Unbounded click growth", "database bloat", "90-day retention purge"),
                  risk("Analytics slowing redirects", "user-visible latency",
                       "asynchronous bounded queue")]}


def impact(with_fix):
    components = [
        component(f"{PKG}/link/RedirectController.java", "controller", "MODIFY",
                  "offer each successful redirect to the click recorder"),
        component(f"{PKG}/config/ShortenerProperties.java", "config", "MODIFY",
                  "queue capacity, batch size, retention"),
        component("src/main/resources/application.yml", "config", "MODIFY", "click settings"),
        component(V2_SQL, "migration", "ADD", "link_clicks table and indexes"),
        component(f"{PKG}/click/ClickRecorder.java", "service", "ADD",
                  "bounded queue and batched flush"),
        component(f"{PKG}/click/LinkStatsService.java", "service", "ADD", "statistics"),
        component(f"{TPKG}/ApplicationWiringTest.java", "test", "MODIFY",
                  "mock the click repository in the database-free context"),
    ]
    if with_fix:
        components[1:1] = [
            component(f"{PKG}/link/LinkResolver.java", "cache", "MODIFY",
                      "check expiry on every lookup (issue #1)"),
            component(f"{PKG}/config/CacheConfiguration.java", "config", "MODIFY",
                      "cache value type becomes CachedLink"),
            component(f"{PKG}/link/CachedLink.java", "domain", "ADD",
                      "cache value carrying target URL and expiry")]
    return {
        "summary": "Analytics is additive: a new click package, one new table and a one-line "
                   "hook in RedirectController after a successful resolve."
                   + (" The expiry bug is confined to LinkResolver and its cache type." if with_fix
                      else ""),
        "components": components,
        "apiChanges": ["New GET /api/v1/links/{code}/stats in api/openapi.yaml; existing "
                       "operations unchanged"],
        "dataChanges": ["V2: link_clicks(code, clicked_at, referrer_host, agent_family) with "
                        "indexes (code, clicked_at) and (clicked_at)"],
        "dataFlows": ["Redirect -> resolve -> 302; then offer(click) to a bounded queue -> "
                      "scheduled batch insert"],
        "risks": [risk("Clicks lost on crash or saturation", "under-counting",
                       "counted drops, documented best-effort semantics")],
        "testStrategy": "Unit tests for privacy reduction, saturation and flushing; "
                        "Testcontainers ITs for statistics, retention and the cached-expiry "
                        "regression; a contract conformance IT."}


def analytics_design(contract, bounded, with_fix, ambiguous):
    decisions = [
        decision("Where clicks are recorded", "After a successful resolve in RedirectController",
                 "404/410 are not clicks; the controller already has the headers",
                 ["Servlet filter (sees failed lookups too)"]),
        decision("Click key", "Store the code, not the link id",
                 "Codes are never reused; recording needs no extra lookup", ["link_id foreign key"]),
        decision("Privacy", "Referrer host and agent family only",
                 "Enough for campaign analysis; no personal data", ["Hashed IP"]),
    ]
    if bounded:
        decisions[0:0] = [decision(
            "Recording durability (Q-1)", "Best effort: bounded ArrayBlockingQueue (10,000), "
            "batched inserts every second; full queue drops and counts; graceful shutdown flushes",
            "No database work on the redirect path and bounded memory; losses are visible in "
            "metrics", ["Synchronous insert (adds a write to every redirect)",
                        "Kafka (new infrastructure for an internal statistic)"])]
        decisions.append(decision("Retention", "Purge clicks older than 90 days hourly; days "
                                  "window capped at 90", "Bounded table growth; stats never "
                                  "promise data that was purged", ["Keep forever"]))
    if with_fix:
        decisions.append(decision("Issue #1", "Cache (target, expiresAt) and re-check expiry on "
                                  "every lookup", "Correct at the exact instant with no database "
                                  "access on hits", ["Shorter TTL"]))
    return design(
        "Click analytics as an additive click package: RedirectController offers each successful "
        "redirect to a ClickRecorder, which " + ("buffers it in a bounded queue flushed in "
                                                 "batches" if bounded else "writes it in the "
                                                 "background") + "; a statistics endpoint reads "
        "aggregated counts." + (" The redirect cache stores the expiry and re-checks it on every "
                                "hit." if with_fix else ""),
        [{"name": "ClickRecorder", "responsibility": "Non-blocking click capture and batched "
          "writes", "files": [f"{PKG}/click/ClickRecorder.java"]},
         {"name": "LinkStatsService", "responsibility": "Totals and zero-filled daily counts",
          "files": [f"{PKG}/click/LinkStatsService.java"]},
         {"name": "ClickRepository", "responsibility": "Batch insert, aggregates, purge",
          "files": [f"{PKG}/click/ClickRepository.java"]}],
        contract,
        "link_clicks(id, code, clicked_at timestamptz, referrer_host varchar(255), agent_family "
        "varchar(16) check in BROWSER/BOT/OTHER); indexes (code, clicked_at) and (clicked_at).",
        decisions,
        ["Best-effort counts can under-count during saturation or crashes"]
        + (["Statistics answer the clarified questions only; no referrer breakdown yet"]
           if ambiguous else []))


def analytics_plan(with_fix):
    tasks = []
    if with_fix:
        tasks.append(task("expiry-fix", "Enforce expiry on cache hits (issue #1)",
                          "CachedLink as cache value; LinkResolver re-checks expiry on every hit.",
                          ["FR-5"], [p for p in FIX_FILES if p.startswith("src/main")]))
    tasks += [
        task("click-storage", "Click table, model and repository",
             "V2 migration, privacy-reducing ClickEvent, AgentFamily, ClickRepository, settings.",
             ["FR-1", "FR-3", "FR-4"], STORAGE),
        task("click-recording", "Non-blocking recording from the redirect",
             "Bounded queue with counted drops, scheduled flush and retention purge, redirect "
             "hook.", ["FR-1", "FR-4", "NFR-1"], RECORDING, ["click-storage"]),
        task("click-statistics", "Statistics endpoint",
             "GET /api/v1/links/{code}/stats with a validated days window.", ["FR-2"], STATISTICS,
             ["click-storage"]),
    ]
    return {"rationale": "Storage first; recording and statistics both build on it and run in "
                         "parallel" + ("; the expiry fix touches disjoint files and runs in "
                                       "parallel with storage." if with_fix else "."),
            "tasks": tasks}


def analytics_security(with_fix):
    return {"summary": "No raw personal data is stored; recording cannot block or fail "
                       "redirects; statistics reuse the existing problem responses.",
            "findings": [
                {"severity": "MEDIUM", "source": "reviewer", "category": "access-control",
                 "location": "LinkStatsController", "description": "Statistics are "
                 "unauthenticated, like the rest of the management API",
                 "recommendation": "Protect /api/** at the gateway"},
                {"severity": "LOW", "source": "reviewer", "category": "privacy",
                 "location": "ClickEvent", "description": "A referrer host can still identify a "
                 "small private site", "recommendation": "Acceptable for campaign analytics; "
                 "revisit if referrers become sensitive"}]}


def analytics_release(with_fix):
    return release(
        "Click analytics" + (" and cached-expiry fix" if with_fix else ""),
        "Best-effort click recording with per-link statistics" + (", and expired links no "
                                                                  "longer redirect from cache"
                                                                  if with_fix else "") + ".",
        ["link_clicks table (V2)", "Bounded asynchronous recording with counted drops",
         "GET /api/v1/links/{code}/stats", "90-day retention purge"]
        + (["Expiry checked on every cache hit (issue #1)"] if with_fix else []),
        ["Clicks can be lost on saturation, failed batches or a crash (counted, documented)",
         "Statistics are unauthenticated (gateway required)"],
        "Revert the commit. V2 is additive: leave it, or drop table link_clicks after reverting.",
        "Unit tests for privacy reduction, saturation and flushing; Testcontainers ITs for "
        "statistics, retention" + (", the expiry regression" if with_fix else "") + " and "
        "contract conformance; final validation re-ran every check on the released tree.",
        ["Referrer breakdown", "Durable click pipeline if exact counts become a requirement"])


def brownfield():
    contract = v2("api/openapi.yaml")
    fixtures = {
        "requirements/e1/attempt-1/primary/requirements-analyst": brownfield_spec(),
        "impact-analysis/e1/attempt-1/primary/impact-analyst": impact(True),
        # First design is rejected by the (demonstration) reviewer for not stating the
        # buffer bound and the retention policy; the second addresses both.
        "design/e1/attempt-1/primary/architect": analytics_design(contract, False, True, False),
        "design/e1/attempt-2/primary/architect": analytics_design(contract, True, True, False),
        "plan/e1/attempt-1/primary/planner": analytics_plan(True),
        "security-review/e1/attempt-1/primary/security-reviewer": analytics_security(True),
        "release/e1/attempt-1/primary/release-manager": analytics_release(True),
    }
    for t in analytics_plan(True)["tasks"]:
        fixtures[f"impl-{t['id']}/e1/attempt-1/primary/coder"] = proposal(
            t["title"], files_from(v2_or_fix, t["files"]))
    tests = ANALYTICS_TESTS + FIX_TESTS + [f"{TPKG}/link/LinkResolverTest.java"]
    fixtures["tests/e1/attempt-1/primary/test-author"] = proposal(
        "Privacy, saturation and flush unit tests; statistics, retention, cached-expiry and "
        "contract conformance ITs; existing web and wiring tests know the recorder.",
        files_from(v2_or_fix, tests))
    fixtures["docs/e1/attempt-1/primary/tech-writer"] = proposal(
        "README: statistics endpoint, click settings, best-effort semantics, scale boundaries; "
        "changelog.", files_from(v2, ["README.md", "CHANGELOG.md"]))
    write("brownfield", fixtures)


DRAFT_QUESTIONS = [
    {"id": "Q-1", "question": "What does 'heavy traffic' mean in numbers?",
     "whyItMatters": "Sizing, caching and whether new infrastructure is needed",
     "options": ["hundreds/s", "~2,000/s", ">10,000/s"],
     "recommendedDefault": "Peak 2,000 redirects/s"},
    {"id": "Q-2", "question": "What latency is 'not slow'?",
     "whyItMatters": "Defines the acceptance test for performance work",
     "options": ["p99 < 50 ms", "p99 < 200 ms"], "recommendedDefault": "p99 < 50 ms"},
    {"id": "Q-3", "question": "Which statistics, at what granularity?",
     "whyItMatters": "Determines the data model and the API",
     "options": ["Totals only", "Totals + daily counts", "Raw click export"],
     "recommendedDefault": "Per-link totals, last click, daily counts"},
    {"id": "Q-4", "question": "Must every click be counted exactly, and how fresh must it be?",
     "whyItMatters": "Exact counting needs durable writes on the redirect path",
     "options": ["Exact, real time", "Best effort, < 1 minute lag"],
     "recommendedDefault": "Best effort, up to 1 minute lag"},
    {"id": "Q-5", "question": "What may we store about visitors, and for how long?",
     "whyItMatters": "Privacy and retention obligations",
     "options": ["IP and user agent", "Referrer host and agent family only"],
     "recommendedDefault": "Referrer host and agent family; 90 days"},
]


def ambiguous_draft():
    return {
        "title": "Faster links under campaign load and usage statistics (draft)",
        "problemStatement": "Marketing reports slow short links during campaigns and has no "
                            "visibility into link usage. Neither 'heavy traffic' nor 'stats' is "
                            "defined.",
        "goals": ["Links stay fast at campaign peaks", "Marketing can see link usage"],
        "functionalRequirements": [fr("FR-1", "Expose usage statistics per link.",
                                      ["Statistics are available through the API"])],
        "nonFunctionalRequirements": [nfr("NFR-1", "performance", "Redirects stay fast at peak.",
                                          "To be defined by Q-1 and Q-2")],
        "ambiguities": DRAFT_QUESTIONS,
        "assumptions": ["Existing redirect cache stays"], "outOfScope": [],
        "risks": [risk("Building the wrong thing", "wasted effort", "clarify before design")]}


def ambiguous_refined():
    frs, nfrs = analytics_requirements(True)
    return {
        "title": "Click statistics and a verified latency target",
        "problemStatement": "Marketing needs per-link usage statistics and redirects that stay "
                            "fast at campaign peaks (clarified with the stakeholder).",
        "goals": ["Per-link statistics with at most a minute of lag",
                  "Redirect latency target measured, not assumed"],
        "functionalRequirements": frs, "nonFunctionalRequirements": nfrs, "ambiguities": [],
        "assumptions": ["Q-1: peak 2,000 redirects/s", "Q-2: p99 < 50 ms at that load",
                        "Q-3: totals, last click, daily counts (7 days default, up to 90)",
                        "Q-4: best effort is acceptable; up to 1 minute lag",
                        "Q-5: referrer host and agent family only; 90-day retention"],
        "outOfScope": ["Dashboards", "Referrer breakdown", "Geo-location", "Authentication"],
        "risks": [risk("Performance target unverified in CI", "regressions go unnoticed",
                       "repeatable load test with recorded results")]}


def ambiguous():
    contract = without_fix(v2("api/openapi.yaml"), AMBIGUOUS_CONTRACT)
    fixtures = {
        "requirements/e1/attempt-1/primary/requirements-analyst": ambiguous_draft(),
        "refine-requirements/e1/attempt-1/primary/requirements-refiner": ambiguous_refined(),
        "impact-analysis/e1/attempt-1/primary/impact-analyst": impact(False),
        "design/e1/attempt-1/primary/architect": analytics_design(contract, True, False, True),
        "plan/e1/attempt-1/primary/planner": analytics_plan(False),
        "security-review/e1/attempt-1/primary/security-reviewer": analytics_security(False),
        "release/e1/attempt-1/primary/release-manager": analytics_release(False),
    }
    for t in analytics_plan(False)["tasks"]:
        fixtures[f"impl-{t['id']}/e1/attempt-1/primary/coder"] = proposal(
            t["title"], files_from(v2, t["files"]))
    fixtures["tests/e1/attempt-1/primary/test-author"] = proposal(
        "Privacy, saturation and flush unit tests; statistics, retention and contract "
        "conformance ITs.", files_from(v2, ANALYTICS_TESTS))
    fixtures["docs/e1/attempt-1/primary/tech-writer"] = proposal(
        "README and changelog for click statistics.",
        [("README.md", without_fix(v2("README.md"), AMBIGUOUS_README)),
         ("CHANGELOG.md", without_fix(v2("CHANGELOG.md"), AMBIGUOUS_CHANGELOG))])
    write("ambiguous", fixtures)


if __name__ == "__main__":
    greenfield()
    brownfield()
    ambiguous()
