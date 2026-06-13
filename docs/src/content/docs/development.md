---
title: Development & Setup
description: Guide to set up the development environment, spin up the database, and run the Scala backend.
---

Follow this guide to set up the development environment, spin up the database, and run the Scala backend.

---

## Prerequisites

Ensure you have the following installed on your system:

- [mise-en-place](https://mise.jdx.dev/) (environment manager and task runner)
- [sbt](https://www.scala-sbt.org/) (Scala build tool; `brew install sbt`)
- A Docker-compatible container runtime for running PostgreSQL locally
  (e.g. [Rancher Desktop](https://rancherdesktop.io/) or [Colima](https://github.com/abiosoft/colima))
- [GitHub CLI](https://cli.github.com/) authenticated with your account — the backend depends on
  the `lv.id.jc:dicechess-engine-scala` artifact from GitHub Packages, which requires
  authentication even for public packages

---

## Quickstart

### 1. Project Initialization

Run the setup task to install pinned tools and register lefthook git hooks:

```bash
mise run setup
```

### 2. Start PostgreSQL Container

Spin up the local PostgreSQL database instance in the background using Docker Compose:

```bash
mise run db:up
```

This runs a PostgreSQL instance listening on port `5432` with:

- **Database**: `dicechess_analytics`
- **Username**: `dicechess_user`
- **Password**: `dicechess_password`

### 3. Run the API Server

```bash
mise run run
```

Database migrations are applied automatically: the backend runs
[Flyway](https://flywaydb.org/) on startup, so there is no separate migration step.
Migration scripts live in `src/main/resources/db/migration/`.

Once started, the interactive API documentation (Swagger UI, generated from the Tapir
endpoint definitions) is available at:

- **Swagger UI**: [http://localhost:8000/docs](http://localhost:8000/docs)

---

## GitHub Packages Authentication

The backend depends on the game engine, `lv.id.jc:dicechess-engine-scala`, published to
**GitHub Packages Maven**. Unlike Maven Central, GitHub Packages requires authentication
**even for public packages** (a token with the `read:packages` scope) — so any `sbt`
command that resolves dependencies needs a username and token.

Rather than repeat that credential dance in every task, the build resolves it once, in
[`build.sbt`](https://github.com/rabestro/dicechess-analytics/blob/main/build.sbt):

GitHub Packages validates only the **token** — any non-empty username is accepted — so the
build never needs a network call to discover the account name. (`credentials` is an sbt
*setting*, evaluated on every load, so a network lookup here would slow down every command
and break offline work.) The token is resolved as follows:

1. **In CI**, the workflow exports `GITHUB_TOKEN`; the build uses it directly.
2. **Locally**, when that variable is absent, the build reads it from the
   [GitHub CLI](https://cli.github.com/) via `gh auth token`, which returns the token from
   the OS keychain **without touching the network** — so it works offline and the token is
   never written to a file or a shell profile.

This is why the Scala build tasks are plain `sbt ...` with no credential prefix,
and why a bare `sbt` invocation works too: just keep `gh auth login` current.

---

## Task Runner Reference

`mise` tasks are configured in `mise.toml` for easy execution:

| Command | Description |
| :--- | :--- |
| `mise run setup` | Installs pinned tools and registers lefthook git hooks. |
| `mise run db:up` | Launches only the PostgreSQL container. |
| `mise run db:down` | Stops and removes only the PostgreSQL container (data volume survives). |
| `mise run stack:up` | Starts db + api + ui from published images. |
| `mise run stack:down` | Stops and removes all compose services. |
| `mise run check` | Repo-wide gate: scalafmt check plus coverage-gated tests on real PostgreSQL. |
| `mise run format` | Runs scalafmt across the Scala sources. |
| `mise run compile` | Compiles the backend. |
| `mise run test` | Runs the test suite without the coverage/clean overhead. |
| `mise run run` | Starts the API server on port `8000`. |
| `mise run docs:dev` | Starts the Astro/Starlight dev server at [http://localhost:4321](http://localhost:4321). |
| `mise run docs:build` | Compiles the documentation site into static HTML inside `docs/dist/`. |

---

## Configuration

Environment variables (compatible with docker-compose):

| Variable | Default | Notes |
| :--- | :--- | :--- |
| `DATABASE_URL` | — | `postgres://`, `postgresql://`, or `postgresql+asyncpg://` forms accepted |
| `POSTGRES_HOST/PORT/DB/USER/PASSWORD` | docker-compose defaults | used when `DATABASE_URL` is absent |
| `HTTP_HOST` / `HTTP_PORT` | `0.0.0.0` / `8000` | |
| `CORS_ORIGINS` | `http://localhost:5173,http://localhost:3000` | comma-separated |

---

## Rancher Desktop / testcontainers notes

The test suite uses testcontainers against a real PostgreSQL. On Rancher Desktop two
machine-local accommodations are needed:

1. `~/.testcontainers.properties`:

   ```properties
   docker.host=unix\:///Users/<you>/.rd/docker.sock
   ```

2. `mise.local.toml` at the repo root (gitignored), so every task gets the variable
   regardless of the shell session — the ryuk cleanup sidecar cannot start against the
   Rancher moby VM, so cleanup falls back to JVM shutdown hooks. CI on ubuntu-latest
   keeps ryuk enabled.

   ```toml
   [env]
   TESTCONTAINERS_RYUK_DISABLED = "true"
   ```

The Docker API version is pinned to 1.43 via `Test / javaOptions` in `build.sbt`:
docker-java does not negotiate and its default (1.32) is rejected by Docker 29+ daemons.

---

## Git Hooks

`mise run setup` registers two [lefthook](https://github.com/evilmartians/lefthook) tiers:

- **pre-commit**: betterleaks secret scan + native scalafmt check on staged files
  (milliseconds per commit).
- **pre-push**: a hermetic full-module scalafmt check (~1s). Tests deliberately stay in CI.

Run all pre-commit jobs manually across the whole codebase with `mise run hook:run`.

---

## Data Ingestion

The historical archive (140k+ games from the frozen `dicechess-lab` SQLite database) has
already been imported into the production PostgreSQL instance — the one-time Python ETL
that performed it was retired together with the rest of the Python codebase.

Ongoing ingestion is the scope of milestone **v0.2**: a transactional `POST /api/games`
endpoint that validates every game against `dicechess-engine-scala` before persisting it.
