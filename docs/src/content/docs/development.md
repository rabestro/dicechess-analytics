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
mise run backend:run
```

Database migrations are applied automatically: the backend runs
[Flyway](https://flywaydb.org/) on startup, so there is no separate migration step.
Migration scripts live in `backend/src/main/resources/db/migration/`.

GitHub Packages credentials (`GITHUB_ACTOR` / `GITHUB_TOKEN`) are derived automatically
from the `gh` CLI when not already present in the environment.

Once started, the interactive API documentation (Swagger UI, generated from the Tapir
endpoint definitions) is available at:

- **Swagger UI**: [http://localhost:8000/docs](http://localhost:8000/docs)

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
| `mise run check` | Repo-wide gate: full Scala backend validation (scalafmt, coverage-gated tests on real PostgreSQL). |
| `mise run format` | Runs scalafmt across the Scala backend. |
| `mise run backend:compile` | Compiles the backend. |
| `mise run backend:test` | Runs the backend test suite without the coverage/clean overhead. |
| `mise run backend:run` | Starts the API server on port `8000`. |
| `mise run docs:dev` | Starts the Astro/Starlight dev server at [http://localhost:4321](http://localhost:4321). |
| `mise run docs:build` | Compiles the documentation site into static HTML inside `docs/dist/`. |

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
