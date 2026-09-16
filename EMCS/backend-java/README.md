# B-Demo Spring Boot backend

This is the Java 17 migration target for the B project. It uses the official
RuoYi-Vue `springboot3` line as its architectural baseline (Spring Boot,
Spring Security, Redis, JWT, MyBatis and the RuoYi response/router contract).
The existing `backend/` FastAPI application is a read-only contract reference;
it is not part of the runtime or deployment path.

## Prerequisites

- JDK 17
- Maven 3.9.16 (the development-server baseline)
- MySQL and Redis from the repository root `docker-compose.yml`

## Run

```bash
docker compose up -d
# On a fresh MySQL volume, Compose automatically imports sql/.
# Wait for MySQL initialization to finish before starting the backend.
cd backend-java
mvn spring-boot:run
```

The service listens on port `9099`. The Vue dev proxy remains unchanged.
Do not set `APP_ROOT_PATH=/dev-api` for local Vite development because Vite
already removes that prefix. Set it only when the deployment gateway retains
the prefix.

Configuration is environment-driven. Generate a non-demo JWT secret with:

```bash
openssl rand -hex 32
```

## Implemented modules

- RuoYi-compatible auth, captcha, JWT, role menus and system administration
- overview, raw quality/backfill, equipment profiles and alert workflow
- suggestion lifecycle, immutable cost versions, reports and Excel archives
- AI inspection SSE, server/cache monitoring and Spring scheduled jobs
- full aggregation rebuild, six-bucket baseline publication and daily deviation refresh
- immutable monthly cost-v1 initialization and data-driven R01-R11 rule evaluation under `/pipeline`
- role guards, AREA-A dispatch data scope and denied-access audit records

Run unit tests with `mvn test`, or build the executable jar with `mvn clean package`.
Integration checks use the unchanged Docker MySQL/Redis schema.
