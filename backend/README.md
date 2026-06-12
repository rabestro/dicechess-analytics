# Dice Chess Analytics — Scala Backend

Scala 3 rewrite of the analytics REST API (architecture decision of 2026-06-12: the
[dicechess engine](https://github.com/rabestro/dicechess-engine-scala) runs in-process as the
single source of truth for game rules). The PostgreSQL schema and the REST contract are
unchanged; the Python app keeps serving until this backend reaches parity.

## Stack

| Concern | Library |
| :--- | :--- |
| HTTP server | http4s (Ember) |
| Typed endpoints + OpenAPI | Tapir (Swagger UI at `/docs`) |
| Database access | Doobie (HikariCP) |
| Migrations | Flyway (`baseline-on-migrate` for the existing production DB) |
| JSON | Circe (snake_case, matching the Pydantic contract) |
| Game rules | `lv.id.jc:dicechess-engine-scala_3` |
| Tests | MUnit + munit-cats-effect + testcontainers (real PostgreSQL) |

## Requirements

- JDK 25, sbt (`mise install` at the repo root provides both)
- Docker (for testcontainers and local PostgreSQL)
- GitHub Packages credentials to resolve the engine artifact:

```bash
export GITHUB_ACTOR=<your github login>
export GITHUB_TOKEN=$(gh auth token)   # or a PAT with read:packages
```

## Commands

From the repository root (delegated via mise) or from `backend/` directly:

```bash
mise run backend:test      # sbt test (testcontainers spins up postgres:18-alpine)
mise run backend:check     # scalafmt check + coverage-gated test run (CI parity)
mise run backend:format    # scalafmt
mise run backend:run       # start the API (needs DATABASE_URL or POSTGRES_* env)
```

## Configuration

Environment variables (compatible with the Python app and docker-compose):

| Variable | Default | Notes |
| :--- | :--- | :--- |
| `DATABASE_URL` | — | `postgres://`, `postgresql://` or `postgresql+asyncpg://` forms accepted |
| `POSTGRES_HOST/PORT/DB/USER/PASSWORD` | docker-compose defaults | used when `DATABASE_URL` is absent |
| `HTTP_HOST` / `HTTP_PORT` | `0.0.0.0` / `8000` | |
| `CORS_ORIGINS` | `http://localhost:5173,http://localhost:3000` | comma-separated |

## Rancher Desktop notes

testcontainers needs two machine-local accommodations (already documented here so any
machine can be set up quickly):

1. `~/.testcontainers.properties`:

   ```properties
   docker.host=unix\:///Users/<you>/.rd/docker.sock
   ```

2. `mise.local.toml` at the repo root (gitignored), so every `mise run backend:*` task gets
   the variable regardless of the shell session — the ryuk cleanup sidecar cannot start
   against the Rancher moby VM; cleanup falls back to JVM shutdown hooks. CI on
   ubuntu-latest keeps ryuk enabled.

   ```toml
   [env]
   TESTCONTAINERS_RYUK_DISABLED = "true"
   ```

The Docker API version is pinned to 1.43 via `Test / javaOptions` in `build.sbt`: docker-java
does not negotiate and its default (1.32) is rejected by Docker 29+ daemons.

## Deployment

Every push to `main` touching `backend/**` publishes the multi-arch image
`ghcr.io/rabestro/dicechess-analytics-api` (CD: Publish API Image). The compose `api`
service runs this image; on the server:

```bash
cd ~/dicechess-analytics && git pull && docker compose pull api && docker compose up -d api
```

The Dockerfile builds the sbt stage on the builder's native platform (JVM bytecode is
architecture-independent) and ships a slim JRE runtime; the GitHub Packages token for the
engine artifact is passed as a BuildKit secret and never lands in image layers.
