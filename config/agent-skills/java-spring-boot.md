# Engineering conventions (injected into every code-writing agent)

These are the standards the build enforces. Code that ignores them fails a gate and is retried.

## Language and style (enforced by Checkstyle, Google style)
- Java 21. Use records for DTOs/value objects, `switch` expressions, pattern matching, `var`
  only when the type is obvious.
- 2-space indentation, 4-space continuation indent, max 100 characters per line.
- Imports: static imports first, then all other imports in one alphabetical group, a blank line
  between the two groups. No wildcard imports, no unused imports.
- Braces on every `if`/`for`/`while`. `}` alone on its line, except empty bodies `{}`.
- If you write a Javadoc on a public method it MUST include every `@param` and `@return`.
  Prefer a short class-level Javadoc and no method Javadoc over an incomplete one.
- Constants `UPPER_SNAKE_CASE`, everything else camelCase. One top-level type per file.

## Spring Boot 4.1 specifics (these differ from Spring Boot 3; get them right)
- Starters are modular: `spring-boot-starter-webmvc`, `spring-boot-starter-jdbc`,
  `spring-boot-starter-flyway`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`.
- Jackson 3: packages are `tools.jackson.*` (annotations stay `com.fasterxml.jackson.annotation`).
- Test annotations moved: `@WebMvcTest` and `@AutoConfigureMockMvc` are in
  `org.springframework.boot.webmvc.test.autoconfigure`. `@MockBean` is gone; use
  `@MockitoBean` from `org.springframework.test.context.bean.override.mockito`.
- `@WebMvcTest` does not apply `@ConfigurationPropertiesScan`; add a nested `@TestConfiguration`
  with `@EnableConfigurationProperties(...)` when a controller needs properties.
- Testcontainers 2: `org.testcontainers.postgresql.PostgreSQLContainer` (non-generic), artifact
  `testcontainers-postgresql`. Integration tests extend `IntegrationTestSupport`.

## Architecture patterns used in this codebase
- Package by feature (`link`, `ratelimit`, `web`, `config`, ...). Constructor injection only; no
  field injection, no Lombok.
- Persistence with Spring `JdbcClient` and explicit SQL; Flyway migrations under
  `src/main/resources/db/migration` named `V<n>__<description>.sql`. Applied migrations are
  immutable: schema changes always go in a new migration.
- Errors are RFC 9457 `ProblemDetail` responses produced by the `@RestControllerAdvice`; throw
  domain exceptions from services and map them there.
- Inject `java.time.Clock` wherever time matters; never call `Instant.now()` directly.
- Keep the redirect path (`GET /{code}`) fast: no synchronous extra database writes on it.
- Configuration via a validated `@ConfigurationProperties` record with sensible defaults.

## Tests
- JUnit 5 + AssertJ. Unit tests `*Test.java` need no Docker; integration tests `*IT.java` run
  with failsafe against Testcontainers PostgreSQL.
- Use the test `MutableClock` (in `src/test/.../support`) for time-dependent behaviour.
- Test names describe behaviour (`shouldReturn410WhenLinkExpired`). Cover error paths.
- The build enforces >= 80% line coverage on unit + integration tests combined.
