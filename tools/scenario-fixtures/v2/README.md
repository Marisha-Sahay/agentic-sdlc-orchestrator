# URL Shortener

A small URL shortener service: create short links, redirect short codes, read link metadata and
click statistics, and delete links. Spring Boot 4.1, Java 21, PostgreSQL (Flyway, `JdbcClient`),
Caffeine, Actuator. The API contract is in [`api/openapi.yaml`](api/openapi.yaml); a plain-English
guide with sample requests and every possible response is in [`API.md`](API.md).

## Running locally

Prerequisites: Java 21 (check with `mvn -v`; if Maven picks another JDK, set `JAVA_HOME` to a
Java 21 installation), Maven 3.9+, Docker.

```bash
docker compose up -d      # PostgreSQL 17 on localhost:5432 (db/user/password: shortener)
mvn spring-boot:run       # applies Flyway migrations and starts on http://localhost:8080
```

Stop the database with `docker compose down` (add `-v` to drop the data volume).

## Try it in the browser (Swagger UI)

With the service running, open <http://localhost:8080/swagger-ui.html>. The page renders the
checked-in contract [`api/openapi.yaml`](api/openapi.yaml), served unchanged at `/openapi.yaml`,
rather than a spec generated from the code. It therefore documents exactly what the API
compatibility gate enforces. "Try it out" is enabled on every operation and sends real requests
to the running service; the request examples come from the contract.

A walkthrough that exercises every behaviour:

1. `POST /api/v1/links` with the `generated` example: 201 with `code` and `shortUrl`.
2. The same operation with `customAliasWithExpiry`: 201. Send it again: 409 `Alias already in use`.
3. `GET /api/v1/links/{code}`: 200. An unknown code: 404.
4. `GET /{code}` with an unknown code: 404 problem document. For a real code, open its `shortUrl`
   in a new tab, or use `curl -i`, to see the 302. Swagger UI cannot show a redirect: the
   browser follows it to the target site and, unless that site allows cross-origin requests,
   reports "Failed to fetch". That is the browser's CORS check, not a service failure.
5. `DELETE /api/v1/links/{code}`: 204. Afterwards `GET /{code}` returns 404 and creating the same
   alias returns 409, because codes are never reused.
6. Invalid input returns 400 with an `errors` list: leave out `url`, use
   `https://bank.com@evil.com`, or use the reserved alias `swagger`.
7. More than 20 `POST`s within a minute return 429 with `Retry-After`.
8. Expiry: create a link whose `expiresAt` is a minute ahead and wait; `GET /{code}` returns 410,
   also if you followed the link before it expired (the cache re-checks expiry on every hit).
9. Statistics: follow a link a few times, wait a second (clicks are written in the background),
   then `GET /api/v1/links/{code}/stats` (optionally `?days=30`).

Swagger UI is a development aid; switch it off with `SPRINGDOC_SWAGGERUI_ENABLED=false`.

## Building and testing

| Command | What runs | Needs Docker |
|---|---|---|
| `mvn verify` | unit tests (surefire), integration tests `*IT` (failsafe + Testcontainers PostgreSQL), Checkstyle, JaCoCo 80% line gate on merged unit + IT coverage | yes |
| `mvn -DskipITs verify` | unit tests, Checkstyle, JaCoCo report (the gate is skipped: it is defined on the merged unit + IT data) | no |

Integration tests share one PostgreSQL container (`postgres:17-alpine`) and one Spring context
across all test classes (see `IntegrationTestSupport`). Without Docker they fail rather than
being skipped silently; use `-DskipITs` explicitly. Coverage report: `target/site/jacoco/`.

## API by example

```bash
# Create a link with a generated 7-character code -> 201 + Location
curl -i -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://example.com/some/long/path"}'

# Create with a custom alias and an expiry
curl -i -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://example.com/launch", "customAlias": "launch-2026", "expiresAt": "2026-12-31T23:59:59Z"}'

# Follow the short link -> 302, Location: <target>, Cache-Control: no-store (404 unknown/deleted, 410 expired)
curl -i http://localhost:8080/launch-2026

# Link metadata -> 200 (404 if unknown or deleted)
curl -i http://localhost:8080/api/v1/links/launch-2026

# Click statistics -> 200: total, last click, daily counts for the last 7 UTC days (?days=1..90)
curl -i http://localhost:8080/api/v1/links/launch-2026/stats?days=7

# Delete (soft) -> 204, then 404; the code is never reused
curl -i -X DELETE http://localhost:8080/api/v1/links/launch-2026

# Operations
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/info
curl 'http://localhost:8080/actuator/metrics/cache.gets?tag=cache:redirects'
```

Example response body (`201` / `200`):

```json
{"code":"aZ3kP9q","shortUrl":"http://localhost:8080/aZ3kP9q","targetUrl":"https://example.com/some/long/path","createdAt":"2026-01-01T00:00:00Z","expiresAt":null}
```

Errors are RFC 9457 problem documents (`application/problem+json`): 400 validation or policy
violation (with an `errors` list of `{field, message}`), 404, 409 alias conflict, 410 expired,
415, 429 rate limited (with `Retry-After` in seconds), 500.

## Configuration

Set in `src/main/resources/application.yml`; override with environment variables (relaxed
binding, e.g. `SHORTENER_BASEURL`) or command-line arguments, e.g.
`java -jar target/url-shortener-0.1.0-SNAPSHOT.jar --shortener.code-length=8` or
`mvn spring-boot:run -Dspring-boot.run.arguments=--shortener.code-length=8`.

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/shortener` | JDBC URL |
| `spring.datasource.username` | `DB_USER` | `shortener` | DB user |
| `spring.datasource.password` | `DB_PASSWORD` | `shortener` | DB password |
| `shortener.base-url` | `SHORTENER_BASEURL` | `http://localhost:8080` | Public base URL for `shortUrl`/`Location`; its host is rejected as a target |
| `shortener.code-length` | `SHORTENER_CODELENGTH` | `7` | Generated code length (4-32) |
| `shortener.redirect-cache.ttl` | `SHORTENER_REDIRECTCACHE_TTL` | `10m` | Redirect cache time-to-live after load |
| `shortener.redirect-cache.max-size` | `SHORTENER_REDIRECTCACHE_MAXSIZE` | `10000` | Max cached codes |
| `shortener.rate-limit.requests-per-period` | `SHORTENER_RATELIMIT_REQUESTSPERPERIOD` | `20` | Token bucket capacity per client |
| `shortener.rate-limit.period` | `SHORTENER_RATELIMIT_PERIOD` | `1m` | Period over which the bucket refills completely |
| `shortener.rate-limit.max-tracked-clients` | `SHORTENER_RATELIMIT_MAXTRACKEDCLIENTS` | `100000` | Upper bound on in-memory buckets |
| `shortener.trust-forwarded-headers` | `SHORTENER_TRUSTFORWARDEDHEADERS` | `false` | Key the rate limit on the first `X-Forwarded-For` hop (only behind a trusted proxy) |
| `shortener.clicks.queue-capacity` | `SHORTENER_CLICKS_QUEUECAPACITY` | `10000` | Clicks buffered in memory before new ones are dropped |
| `shortener.clicks.batch-size` | `SHORTENER_CLICKS_BATCHSIZE` | `500` | Clicks written per insert |
| `shortener.clicks.flush-interval` | `SHORTENER_CLICKS_FLUSHINTERVAL` | `1s` | How often queued clicks are written (statistics lag) |
| `shortener.clicks.retention` | `SHORTENER_CLICKS_RETENTION` | `90d` | Clicks older than this are deleted; also the longest `days` window |
| `shortener.clicks.purge-interval` | `SHORTENER_CLICKS_PURGEINTERVAL` | `1h` | How often the retention purge runs |

Invalid values (e.g. `code-length=3`) fail startup.

## Design notes

- **302, not 301.** A 301 is cached by browsers indefinitely, so later visits would never reach
  the service; that breaks deletion, expiry and any future click analytics. Redirects also send
  `Cache-Control: no-store`.
- **Random base62 codes.** `RandomBase62CodeGenerator` draws 7 characters from `SecureRandom`
  (62^7, about 3.5 * 10^12 codes): not enumerable, no coordination between instances. The
  database unique constraint is the source of truth: inserts use
  `ON CONFLICT (code) DO NOTHING` and a collision is retried with a fresh code up to 5 times,
  then fails with a 500 (`CodeGenerationException`). No exceptions drive the control flow, and a
  collision cannot abort a surrounding transaction.
- **Custom aliases** must match `^[A-Za-z0-9_-]{4,32}$` (the same pattern the redirect route
  accepts) and must not be a reserved word (`api`, `actuator`, `health`, `admin`, `static`, ...).
  A taken alias returns 409.
- **Soft delete.** `DELETE` sets `deleted_at`; the row keeps its code, so a code is never reused
  (re-creating the alias returns 409). The redirect cache entry is evicted on delete.
- **Redirect cache.** `LinkResolver` keeps a Caffeine cache of `code -> (target URL, expiry)`
  (TTL and size configurable, statistics exported as `cache.*` metrics). Expiry is checked on
  every lookup, hit or miss, so a cached link stops redirecting exactly at `expiresAt` (issue #1);
  unknown codes are not cached.
- **Click analytics (best effort, by design).** A successful redirect offers a click to a
  bounded in-memory queue and returns; it never waits for the database. A background task writes
  the queue in batches every second. Consequences, accepted at design review:
  - *Saturation:* when the queue (10,000) is full, new clicks are dropped and counted in
    `shortener.clicks.dropped`; redirects are never slowed down or failed by analytics.
  - *Loss:* a failed batch insert loses that batch (`shortener.clicks.failed`); a crash or
    `kill -9` loses what is queued (at most one flush interval of clicks); a graceful shutdown
    flushes first. Clicks are not retried, so they are never duplicated.
  - *Freshness:* statistics lag redirects by about the flush interval.
  - *Privacy:* only the referrer **host** and a coarse agent family (browser, bot, other) are
    stored; no IP address, no user-agent string, no referrer path or query.
  - *Retention and windows:* clicks older than 90 days are purged hourly; `totalClicks` covers the
    retained clicks; `days` selects 1 to 90 whole UTC days ending today, zero-filled. Indexes
    `(code, clicked_at)` and `(clicked_at)` serve the statistics queries and the purge.
  - A link's statistics are available while the link exists (also after it expires); deleted
    links return 404. 404 and 410 responses are not counted.
- **Rate limiting.** A hand-rolled per-client token bucket (`RateLimiter`) on
  `POST /api/v1/links` only, applied by a Spring MVC interceptor so rejections are rendered by
  the same ProblemDetail advice (429 + `Retry-After`). Refill uses exact integer arithmetic on
  an injected `Clock`. Buckets live in a size-bounded Caffeine cache that expires idle buckets
  after one period, which loses nothing because such a bucket would be full again anyway. The
  limit is per instance; several instances would need a shared store.
- **Target URL policy.** Only absolute `http`/`https` URLs with a host, at most 2048 characters;
  URLs with user info are rejected (`https://bank.com@evil.com` actually goes to `evil.com`), and
  so are URLs pointing at the shortener's own host (redirect loops).
- **Time** comes from an injected `java.time.Clock` everywhere, so tests control it. Timestamps
  are stored as `timestamptz` at microsecond precision.

## Layout

```
src/main/java/io/github/marishasahay/shortener/
  UrlShortenerApplication.java
  config/     ShortenerProperties, ClockConfiguration, CacheConfiguration
  link/       domain, service, repository, controllers, policies, code generator, DTOs, exceptions
  click/      ClickRecorder (bounded async queue), ClickRepository, statistics service/controller
  ratelimit/  RateLimiter, TokenBucket, interceptor and its registration
  web/        ProblemDetailsAdvice (RFC 9457 error mapping), ApiContractController (/openapi.yaml)
src/main/resources/db/migration/V1__create_links.sql, V2__create_link_clicks.sql
api/openapi.yaml    the API contract; also what Swagger UI shows
```

## Scale and reliability boundaries

The service is designed for a single region behind a trusted reverse proxy. What holds, and what
does not, when it runs as several instances:

| Concern | Behaviour | Boundary |
|---|---|---|
| Expiry | Enforced on every redirect from the cached `expiresAt`, on every instance | Clock skew between instances shifts it by the skew |
| Deletion | The deleting instance evicts its cache entry immediately | Other instances may keep redirecting a deleted link until their entry's TTL ends (10 minutes by default); a shared cache or an invalidation broadcast would close this |
| Rate limiting | Token bucket per client and per instance | N instances allow N times the limit; a shared store (e.g. Redis) is the scale-out answer |
| Hot links | Cache hits never touch the database; a miss is one indexed query | Click recording adds one batched insert per 500 clicks per instance, not one per redirect |
| Analytics backpressure | Bounded queue, drops counted | Not durable: use a durable log (e.g. Kafka) if every click must be counted |
| Management API | `POST`/`GET`/`DELETE` and statistics are unauthenticated; anyone who knows a code can delete it or read its statistics | Must be protected at the gateway (authentication, ownership) before exposure beyond trusted callers |
| Throughput | No load target is verified in CI | See the load test in `tools/loadtest` and its documented results for one machine |
