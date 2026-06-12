---
title: Development & Setup
description: Guide to set up the development environment, spin up the database, run migrations, and run the ETL importer.
---

Follow this guide to set up the development environment, spin up the database, run migrations, and run the ETL importer.

---

## Prerequisites

Ensure you have the following installed on your system:

- [mise-en-place](https://mise.jdx.dev/) (environment manager and task runner)
- [uv](https://github.com/astral-sh/uv) (extremely fast Python package manager)
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (for running PostgreSQL locally)

---

## Quickstart

### 1. Project Initialization

Run the setup task to install dependencies via `uv` and register pre-commit code formatting hooks:

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

### 3. Run Database Migrations

Apply the initial schema migrations using Alembic:

```bash
mise run db:migrate
```

### 4. Run the API Server

Start the development FastAPI server with uvicorn (with hot reloading enabled):

```bash
uv run uvicorn src.api.main:app --port 8000 --reload
```

Once started, the API documentation is available at:

- **Swagger UI**: [http://127.0.0.1:8000/docs](http://127.0.0.1:8000/docs)
- **ReDoc**: [http://127.0.0.1:8000/redoc](http://127.0.0.1:8000/redoc)

---

## Task Runner Reference

`mise` tasks are configured in `mise.toml` for easy execution:

| Command | Description |
| :--- | :--- |
| `mise run setup` | Synchronizes Python packages and registers pre-commit linters. |
| `mise run db:up` | Launches only the PostgreSQL container. |
| `mise run db:down` | Stops and removes only the PostgreSQL container (data volume survives). |
| `mise run stack:up` | Starts db + api + ui (amd64 hosts only until the ui image is multi-arch). |
| `mise run stack:down` | Stops and removes all compose services. |
| `mise run db:migrate` | Applies all pending Alembic migrations (legacy; the Scala backend uses Flyway). |
| `mise run check` | Runs `ruff` checks plus the full Scala backend validation (`backend:check`). |
| `mise run format` | Runs `ruff` to automatically fix formatting and lint errors. |
| `mise run backend:test` | Runs the Scala backend test suite without the coverage/clean overhead. |
| `mise run dev` | Starts the FastAPI dev server (until Scala parity). |
| `mise run docs:dev` | Starts the Astro/Starlight dev server at [http://localhost:4321](http://localhost:4321). |
| `mise run docs:build` | Compiles the documentation site into static HTML inside `docs/dist/`. |

---

## Running the SQLite to PostgreSQL ETL Importer

The project includes an ETL script (`src/importer.py`) to parse historical local SQLite database archives and import them into the PostgreSQL database.

### Importer Command Options

Execute the script using `uv run python src/importer.py` with the following flags:

- `--sqlite-path` / `-s` (Required): Path to the source SQLite `.db` file.
- `--limit` / `-l` (Optional): Limit the number of imported games (useful for testing pipeline performance).

### Example Ingestion Command

To import a test set of 1,000 games from a local SQLite database:

```bash
uv run python src/importer.py --sqlite-path ./dicechess.db --limit 1000
```

The script will read the SQLite records, normalize the board states, resolve/deduplicate positions, insert the player profiles, and commit games transactionally using a progress bar.
