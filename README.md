# Dice Chess Analytics

High-performance analytical backend and data pipeline for Dice Chess. Ingests, normalizes, and analyzes millions of games using PostgreSQL and FastAPI.

## Overview

The `dicechess-analytics` project acts as the core data engine, designed to efficiently store and analyze game history, positions, and aggregate metrics. It leverages async database operations, bulk ETL pipelines, and specialized indexing to perform fast lookups on board states and statistical performance.

## Tech Stack

- **Framework**: [FastAPI](https://fastapi.tiangolo.com/) (Async web server)
- **Database**: PostgreSQL (with asyncpg)
- **ORM / Migrations**: [SQLAlchemy](https://www.sqlalchemy.org/) 2.0 & [Alembic](https://alembic.sqlalchemy.org/)
- **Data Validation**: [Pydantic](https://docs.pydantic.dev/) v2
- **Environment**: Docker Compose, Python 3.12+, `uv` (Package management)
- **Task Runner**: [mise](https://mise.jdx.dev/)

## Developer Workflows

This project uses `mise` as the core task runner. Use `mise run <task>` from the root of the repository.

### Core Commands

- `mise run check` - Repo-wide gate: Ruff checks (legacy Python) plus the Scala backend validation.
- `mise run format` - Runs Ruff checks with autofixes and the formatter.
- `mise run backend:*` - Scala backend tasks (`compile`, `test`, `check`, `format`, `run`); see [backend/README.md](backend/README.md).

### Database & Services

- `mise run db:up` - Starts only the PostgreSQL container in the background.
- `mise run db:down` - Stops and removes only the PostgreSQL container (the data volume survives).
- `mise run stack:up` / `stack:down` - Full stack (db + api + ui). The ui image is currently amd64-only, so this works on the server but not on Apple Silicon.
- `mise run db:migrate` - Applies database migrations using Alembic (legacy; the Scala backend migrates via Flyway on startup).
- `mise run db:makemigrations "description"` - Auto-generates a new migration script (legacy).

### Documentation

- `mise run docs:dev` - Runs the local hot-reloaded development server (MkDocs).
- `mise run docs:build` - Compiles all pages to static HTML assets.

## Roadmap & Milestones

The project is structured around the following key milestones:

1. **v0.1 - Foundation & Local Setup**: Project bootstrapping, initial schemas, basic ETL SQLite importer, and documentation site setup.
2. **v0.2 - Ingestion API & ETL Optimization**: Transactional endpoints for saving new games, high-throughput ETL updates, and unit/integration testing suite.
3. **v0.3 - Position Analytics & Deduplication**: FEN normalization, signed xxhash64 bigint mapping in PostgreSQL, database index tuning, and position analytics API endpoints.
4. **v0.4 - Aggregate Metrics & Materialized Views**: Complex analytical queries (player rating histories, opening stats), PostgreSQL Materialized Views for performance caching, and advanced game search filters.
5. **v0.5 - Real-Time Dashboard & WebSockets**: Real-time game event streaming and dashboard data endpoints using WebSockets.
6. **v0.6 - Cache Layer & Query Tuning**: Redis integration for caching position analytics, query optimization, and API load testing.
7. **v1.0 - Production Readiness & CI/CD**: Production Dockerfile, GitHub Actions CI pipelines, structured logging, and monitoring metrics.

## License

This project is licensed under the [AGPL-3.0 License](LICENSE).
