# URL Shortener

A small URL shortener service (v1 baseline): create short links, redirect short codes, read link
metadata, and delete links. Spring Boot 4.1, Java 21, PostgreSQL (Flyway, `JdbcClient`),
Caffeine, Actuator. The API contract is in [`api/openapi.yaml`](api/openapi.yaml).

## Running locally

Prerequisites: Java 21 (check with `mvn -v`; if Maven picks another JDK, set `JAVA_HOME` to a
Java 21 installation), Maven 3.9+, Docker.

```bash
docker compose up -d      # PostgreSQL 17 on localhost:5432 (db/user/password: shortener)
mvn spring-boot:run       # applies Flyway migrations and starts on http://localhost:8080
```

Stop the database with `docker compose down` (add `-v` to drop the data volume).

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
- **Redirect cache.** `LinkResolver` keeps a Caffeine cache of `code -> target URL` (TTL and size
  configurable, statistics exported as `cache.*` metrics). On a miss it loads the link from the
  database and applies the not-found and expiry checks; unknown codes are not cached.
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
  ratelimit/  RateLimiter, TokenBucket, interceptor and its registration
  web/        ProblemDetailsAdvice (RFC 9457 error mapping)
src/main/resources/db/migration/V1__create_links.sql
api/openapi.yaml
```
