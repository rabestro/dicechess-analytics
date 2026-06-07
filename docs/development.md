# Development & Local Setup Guide

Follow this guide to set up the development environment, spin up the database, run migrations, and run the ETL importer.

---

## Prerequisites

Ensure you have the following installed on your system:
*   [mise-en-place](https://mise.jdx.dev/) (environment manager and task runner)
*   [uv](https://github.com/astral-sh/uv) (extremely fast Python package manager)
*   [Docker Desktop](https://www.docker.com/products/docker-desktop/) (for running PostgreSQL locally)

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
mise run db_up
```
This runs a PostgreSQL instance listening on port `5432` with:
*   **Database**: `dicechess_analytics`
*   **Username**: `dicechess_user`
*   **Password**: `dicechess_password`

### 3. Run Database Migrations
Apply the initial schema migrations using Alembic:
```bash
mise run migrate
```

### 4. Run the API Server
Start the development FastAPI server with uvicorn (with hot reloading enabled):
```bash
uv run uvicorn src.api.main:app --port 8000 --reload
```
Once started, the API documentation is available at:
*   **Swagger UI**: [http://127.0.0.1:8000/docs](http://127.0.0.1:8000/docs)
*   **ReDoc**: [http://127.0.0.1:8000/redoc](http://127.0.0.1:8000/redoc)

---

## Task Runner Reference

`mise` tasks are configured in `mise.toml` for easy execution:

| Command | Task | Description |
| :--- | :--- | :--- |
| `mise run setup` | Setup Environment | Synchronizes Python packages and registers pre-commit linters. |
| `mise run db_up` | Docker DB Up | Launches the PostgreSQL container. |
| `mise run db_down`| Docker DB Down | Stops and removes the PostgreSQL container. |
| `mise run migrate`| DB Migrations | Applies all pending Alembic migrations. |
| `mise run check` | Lint & Format Check | Runs `ruff` checks and code formatting dry-runs. |
| `mise run format` | Code Auto-Formatter | Runs `ruff` to automatically fix formatting and lint errors. |
| `mise run docs` | Run Docs Server | Starts the MkDocs dev server at [http://localhost:8000](http://localhost:8000). |
| `mise run docs_build`| Compile Static Docs | Compiles the documentation site into static HTML inside `site/`. |

---

## Running the SQLite to PostgreSQL ETL Importer

The project includes an ETL script (`src/importer.py`) to parse historical local SQLite database archives and import them into the PostgreSQL database.

### Importer Command Options
Execute the script using `uv run python src/importer.py` with the following flags:
*   `--sqlite-path` / `-s` (Required): Path to the source SQLite `.db` file.
*   `--limit` / `-l` (Optional): Limit the number of imported games (useful for testing pipeline performance).

### Example Ingestion Command
To import a test set of 1,000 games from a local SQLite database:
```bash
uv run python src/importer.py --sqlite-path ./dicechess.db --limit 1000
```

The script will read the SQLite records, normalize the board states, resolve/deduplicate positions, insert the player profiles, and commit games transactionally using a progress bar.
