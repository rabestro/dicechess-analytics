# AGENTS.md

Branch naming rules, milestones, and agent guidance for the Dice Chess Analytics repository.

## Branch Naming Conventions

Allowed branch prefixes:
- `task` — work items / tasks
- `feat` — new features
- `bug` — bug fixes
- `refactor` — code cleanup / restructuring

Branch name pattern (required):
  `(task|feat|bug|refactor)/<issue-number>-<short-description>`
Example: `bug/6-fix-mermaid-syntax`

## Agent Rules (AI Assistance)
- Do not implement or open a PR unless an issue exists and the branch is named according to the pattern.
- Always run `mise run format` on any generated code and ensure `mise run check` passes successfully locally before proposing a PR.
- Human retains the ultimate authority to review, approve, and merge the PR.
- **GitHub CLI Authentication**: On macOS, credentials are saved in the Keychain. When executing `gh` commands, explicitly set the token to an empty string (e.g., `GH_TOKEN="" gh issue create ...`) to avoid authentication errors.

## Developer Workflows
- **Core Runner**: Use `mise run <task>` from the root of the repository for all development tasks.
- **Task Naming Convention**: bare verbs for repo-wide lifecycle tasks (`setup`, `format`,
  `check`, `dev`); `domain:action` with a colon for namespaced tasks (`db:up`,
  `backend:test`, `docs:build`). Same convention as dicechess-engine-scala.
- **Code Formatting**: `mise run format` runs Ruff checks (with autofixes) and formatter;
  `mise run backend:format` runs scalafmt for the Scala backend.
- **Local CI validation**: `mise run check` is the repo-wide gate — Ruff checks for the
  legacy Python code plus the full Scala backend validation (`backend:check`).
- **Service Control**:
  - `mise run db:up`: Starts only the PostgreSQL container in background.
  - `mise run db:down`: Stops the compose services.
  - `mise run stack:up` / `stack:down`: Full stack (db + api + ui); the ui image is
    amd64-only for now, so this works on the server but not on Apple Silicon.
- **Database Migrations**:
  - `mise run db:migrate`: Applies database migrations using Alembic.
  - `mise run db:makemigrations "description"`: Auto-generates a new migration script.
- **Documentation**:
  - `mise run docs:dev`: Runs local hot-reloaded development server.
  - `mise run docs:build`: Compiles all pages to static HTML assets.

## Approved Milestones

Assign tasks to these milestones logically. Each milestone must be fully tested before moving to the next.

* **v0.1 - Foundation & Local Setup**: Project bootstrapping (FastAPI, Pydantic, SQLAlchemy, Alembic, Docker Compose with PostgreSQL), initial schemas, basic ETL SQLite importer, and documentation site setup.
* **v0.2 - Ingestion API & ETL Optimization**: Transactional endpoints for saving new games, high-throughput ETL updates (async processing, bulk inserts), and unit/integration testing suite.
* **v0.3 - Position Analytics & Deduplication**: FEN normalization, signed xxhash64 bigint mapping in PostgreSQL, database index tuning, and position analytics API endpoints (`/api/positions/{fen_hash}/analytics`).
* **v0.4 - Aggregate Metrics & Materialized Views**: Complex analytical queries (player rating histories, opening stats), PostgreSQL Materialized Views for performance caching, and advanced game search filters.
* **v0.5 - Real-Time Dashboard & WebSockets**: Real-time game event streaming and dashboard data endpoints using WebSockets.
* **v0.6 - Cache Layer & Query Tuning**: Redis integration for caching position analytics, query optimization using EXPLAIN ANALYZE, and API load testing.
* **v1.0 - Production Readiness & CI/CD**: Production Dockerfile, GitHub Actions CI pipelines, structured logging, and monitoring metrics (Prometheus/Grafana).

## Approved GitHub Labels

Use ONLY these labels when generating `gh` commands:
* **bug** — Something isn't working.
* **documentation** — Improvements or additions to documentation.
* **enhancement** — New feature or request.
* **testing** — Adding unit, property-based, or integration tests.
* **performance** — Query optimizations and ingestion speedups.
* **ai-ready** — Mandatory for well-scoped tasks. Acts as a strict contract that the Definition of Done is absolute and ready for an AI agent to implement.
