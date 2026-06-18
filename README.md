# Dice Chess Analytics

High-performance analytical backend for Dice Chess. Stores, normalizes, and analyzes
140k+ games using Scala 3, PostgreSQL, and the
[dicechess engine](https://github.com/rabestro/dicechess-engine-scala) as the single
source of truth for game rules.

## Overview

The `dicechess-analytics` project is the data engine of the Dice Chess ecosystem: it
stores game history, turns, and deduplicated board positions, and serves them through a
typed REST API consumed by
[dicechess-analytics-ui](https://github.com/rabestro/dicechess-analytics-ui). The
long-term goal is position analytics — empirical win statistics and expected value per
position, the metric that matters most in a dice-driven chess variant.

## Tech Stack

- **Backend**: Scala 3 — http4s (Ember) + Tapir (typed endpoints, Swagger UI at `/docs`)
- **Database**: PostgreSQL 18; access via Doobie, migrations via Flyway
- **Game rules**: `lv.id.jc:dicechess-engine-scala` (GitHub Packages Maven)
- **Tests**: MUnit + testcontainers (real PostgreSQL)
- **Tooling**: [mise](https://mise.jdx.dev/) (toolchain + tasks), lefthook (git hooks),
  Docker Compose

See the [development guide](docs/src/content/docs/development.md) for setup details
(requirements, configuration, Rancher Desktop / testcontainers notes).

## Developer Workflows

This project uses `mise` as the core task runner. Use `mise run <task>` from the root of
the repository.

### Core Commands

- `mise run setup` - Installs tooling and registers lefthook git hooks.
- `mise run compile` / `test` / `run` - Scala build tasks (run directly from the root).
- `mise run check` - Repo-wide gate: full validation (format check, coverage-gated tests).
- `mise run format` - Reformats the Scala sources.

### Database & Services

- `mise run db:up` - Starts only the PostgreSQL container in the background.
- `mise run db:down` - Stops and removes only the PostgreSQL container (the data volume survives).
- `mise run stack:up` / `mise run stack:down` - Full stack (db + api + ui) from published images.

Database schema migrations are applied by the backend itself via Flyway on startup.

### Documentation

- `mise run docs:dev` - Runs the local Astro/Starlight docs dev server.
- `mise run docs:build` - Builds the static documentation site.

## Deployment

Every push to `main` touching the Scala sources publishes the multi-arch image
`ghcr.io/rabestro/dicechess-analytics-api`. The production server needs only two files:
`docker-compose.yaml` and `.env` (the compose project name is pinned, so the deploy
directory can live anywhere without losing the data volume).

Production pins the image to a released version with `API_TAG` in its `.env`
(e.g. `API_TAG=v0.1.5`), so `docker compose pull` fetches that exact tag instead of a
floating `:latest` — a non-booting build can no longer reach prod on a re-pull (issue
[#117](https://github.com/rabestro/dicechess-analytics/issues/117)). Promote by bumping
the pin to a staging-verified tag, then:

```bash
docker compose pull api && docker compose up -d api
```

### Staging-first promotion

`docker-compose.yaml` reads the image tag from `API_TAG` (default `latest`), so one
file serves every environment. CI smoke-tests each image before publishing it
(`.mise/tasks/smoke-test`); before a tag reaches production it is also verified on the
staging stack (Dexus):

```bash
mise run staging:deploy v0.1.5   # deploy + smoke-check a candidate tag on staging
```

The task syncs the compose to the staging host, rolls `api` to the candidate tag, then
fails unless the deployed endpoint serves `GET /`, reports the expected version at
`/version` (asserted for `vX.Y.Z` tags), and returns 401 for an unauthenticated
`POST /api/games`. On green, promote deliberately: bump `API_TAG` in the prod `.env`
to that tag and pull (see above).

## Roadmap & Milestones

1. **v0.1 - Foundation & Local Setup** — done: schema, initial data import (140k+ games).
2. **v0.2 - Ingestion API & Scala rewrite** — done: read-parity Scala backend in production and transactional `POST /api/games` with engine-side validation.
3. **v0.3 - Position Analytics & Deduplication** — active: position statistics API.
4. **v0.4 - Aggregate Metrics & Materialized Views** — rating histories, opening stats.
5. **v1.0 - Production Readiness & CI/CD** — observability, hardening.
