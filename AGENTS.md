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
  `check`); `domain:action` with a colon for namespaced tasks (`db:up`,
  `backend:test`, `docs:build`). Same convention as dicechess-engine-scala.
- **Git Hooks**: `mise run setup` (or `mise run hook:install`) registers lefthook Git hooks.
  Run `mise run hook:run` to execute all pre-commit checks against every file.
- **Code Formatting**: `mise run format` runs scalafmt across the Scala backend.
- **Local CI validation**: `mise run check` is the repo-wide gate — the full Scala
  backend validation (`backend:check`: scalafmt, coverage-gated tests on real PostgreSQL).
- **Service Control**:
  - `mise run db:up`: Starts only the PostgreSQL container in background.
  - `mise run db:down`: Stops and removes only the PostgreSQL container.
  - `mise run stack:up` / `mise run stack:down`: Full stack (db + api + ui) from published images.
- **Database Migrations**: applied by the Scala backend itself via Flyway on startup;
  migration scripts live in `backend/src/main/resources/db/migration/`.
- **Documentation**:
  - `mise run docs:dev`: Runs local hot-reloaded development server.
  - `mise run docs:build`: Compiles all pages to static HTML assets.

## Task Routing & Model Economy

Tasks differ in the AI-model capability they require; route deliberately instead of
defaulting to the strongest model:

- **Frontier tier** — architecture decisions, engine integration, cross-repo work,
  ambiguous problems, high blast radius (schema, public APIs, release pipelines).
- **Mid tier** — well-scoped features following existing patterns, refactors under
  good test coverage, addressing review feedback.
- **Routine tier** — config rollouts, documentation fixes, mechanical edits, tests
  written from a complete spec. The quality gates (`mise run check`, CI, review
  bots) catch failures cheaply, which is what makes this tier safe.

Orchestrating agents should delegate routine sub-tasks to cheaper models where the
harness supports it. When in doubt, escalate one tier up: reviewer time is more
expensive than tokens.

## Approved Milestones

Assign tasks to these milestones logically. Each milestone must be fully tested before moving to the next.

* **v0.1 - Foundation & Local Setup**: done — PostgreSQL schema, initial bulk import (140k+ games), Docker Compose stack, documentation site.
* **v0.2 - Ingestion API & Scala Rewrite**: read-parity Scala 3 backend (http4s + Tapir + Doobie + Flyway) in production; remaining: transactional `POST /api/games` with engine-side move validation.
* **v0.3 - Position Analytics & Deduplication**: FEN normalization via the engine, signed xxhash64 bigint position mapping in PostgreSQL, index tuning, and position analytics endpoints (`/api/positions`).
* **v0.4 - Aggregate Metrics & Materialized Views**: complex analytical queries (player rating histories, opening stats), PostgreSQL Materialized Views for performance caching, and advanced game search filters.
* **v0.5 - Real-Time Dashboard & WebSockets**: real-time game event streaming and dashboard data endpoints (http4s WebSockets).
* **v0.6 - Cache Layer & Query Tuning**: caching for position analytics, query optimization using EXPLAIN ANALYZE, and API load testing.
* **v1.0 - Production Readiness & CI/CD**: multi-arch images, GitHub Actions pipelines, structured logging, and monitoring metrics.

## Approved GitHub Labels

Use ONLY these labels when generating `gh` commands:

* **Shared core** (identical across all Dice Chess repositories):
  * **bug** — Something isn't working.
  * **enhancement** — New feature or request.
  * **refactoring** — Code restructuring without behavioral changes.
  * **documentation** — Improvements or additions to documentation.
  * **testing** — Adding unit, property-based, or integration tests.
  * **performance** — Query optimizations and ingestion speedups.
  * **ci-cd** — GitHub Actions, build scripts, or mise configuration.
  * **dependencies** — Dependency updates (applied by Dependabot).

* **Domains** (this repository only):
  * **database** — PostgreSQL schema, migrations, and query tuning.
