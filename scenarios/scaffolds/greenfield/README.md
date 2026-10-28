# URL Shortener

Spring Boot 4 / Java 21 service on PostgreSQL. This README is a scaffold; the orchestrator's
documentation stage fills it in.

## Run locally

```bash
docker compose up -d
mvn spring-boot:run
```

Integration tests (`*IT`) use Testcontainers and need Docker: `mvn verify`.
