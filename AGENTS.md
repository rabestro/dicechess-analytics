# AGENTS.md
Scala 3 backend serving the typed REST API over the Dice Chess games database — the analytics hub of the dicechess ecosystem.

## Project context
- Public repository, AGPL-3.0. Single-module Scala 3 project at the repo root (package `dicechess.analytics`); http4s + Tapir + Doobie + Flyway over PostgreSQL, cats-effect IO throughout.
- Published artifact: multi-arch Docker image `ghcr.io/rabestro/dicechess-analytics-api`. Docs deploy to GitHub Pages from `docs/`.
- Contracts this repo publishes:
  - Read API (snake_case JSON, `{"detail": ...}` errors) consumed by dicechess-analytics-ui — defined in `src/main/scala/dicechess/analytics/api/Endpoints.scala` + `Protocol.scala`. Breaking a field breaks the UI.
  - Write path: `POST /api/games` (bearer `INGEST_TOKEN`, idempotent first-writer-wins; contract in `docs/src/content/docs/ingestion.md` + `api/IngestProtocol.scala`) plus `PUT /api/games/{id}` (re-conversion replace path — documented only in the `Endpoints.scala` scaladoc). Used by trusted upstream writers holding the token (e.g. play-api).
  - `opening_book.json` (from `db:export-book`) whose key must byte-match the engine's `OpeningBook.key` = `normalized_fen + ' ' + dice_sorted`.
- Contract this repo consumes: game rules come exclusively from `lv.id.jc:dicechess-engine-scala` (GitHub Packages, version pinned in `build.sbt`). Never re-implement chess/dice rules here.
- Ingest identity convention: site bots use their native negative `external_id`; our own engine bots use `bot:<algorithm>`.

## Architecture map
- `src/main/scala/dicechess/analytics/`
  - `Main.scala` — the ONE app entry point (`IOApp.Simple`): env config → Flyway migrate → Hikari pool → Ember server on :8000, Swagger UI at `/docs`.
  - `Config.scala`, `Database.scala` — env-based `AppConfig` (`Either`-validated), transactor + migrations.
  - `Fen.scala` — position identity: 4-field normalized FEN with X-FEN en-passant canonicalisation (delegates to the engine's `Dfen`) + signed xxHash64 `fen_hash`.
  - `api/` — `Endpoints.scala` (pure Tapir descriptions; writes use `securityIn(auth.bearer)`), `Protocol.scala`/`IngestProtocol.scala` (Circe codecs, snake_case), `Routes.scala` (server logic, CORS, auth).
  - `ingest/GameReplay.scala` — replays every submitted game through the engine to validate legality before persisting.
  - `repository/` — plain Doobie query classes (`GamesRepository`, `PlayersRepository`, `PositionsRepository`, `IngestRepository`, `TrainingExportRepository`, `Filters`).
  - `maintenance/` — one-shot IOApp runners (`Export*`, `Enrich*`, `Repair*`, `EvaluateMonteCarloApp`); run only via `sbt runMain` or their mise wrappers; excluded from coverage.
- `src/main/resources/db/migration/` — Flyway `V1`–`V8`, append-only (V4–V7 are data repairs / index additions).
- `docs/` — Astro + Starlight documentation site. `.mise/tasks/` — file tasks `smoke-test` and `staging/deploy` (not listed in `mise.toml`).

## Commands
Prerequisites (in order):
1. `mise install` — tools pinned in `mise.toml` (Java temurin-25, scalafmt 3.11.1, gh, lefthook, betterleaks, jq); then `mise run setup` to register the git hooks.
2. `gh auth login` — `build.sbt` shells out to `gh auth token` to fetch the engine artifact from GitHub Packages (auth is required even though the package is public). Failure signature: any sbt command fails with an unresolved dependency on `lv.id.jc:dicechess-engine-scala` — fix auth, don't touch resolvers.
3. Docker running — tests use testcontainers. On Rancher Desktop: set `docker.host=unix:///Users/<you>/.rd/docker.sock` in `~/.testcontainers.properties` and `TESTCONTAINERS_RYUK_DISABLED=true` (gitignored `mise.local.toml`); see `docs/src/content/docs/development.md`. Failure signature: `mise run test`/`check` hangs at container startup.

Daily tasks:
```sh
mise run check      # THE repo gate: scalafmtCheckAll + clean coverage test coverageReport
mise run test       # sbt test (real PostgreSQL via testcontainers)
mise run format     # native scalafmt over TRACKED files — `git add` new .scala files first
mise run run        # API on http://localhost:8000, Swagger at /docs; Flyway migrates on startup
mise run db:up      # local PostgreSQL only        | db:down removes it (volume survives)
mise run stack:up   # full db+api+ui from published images (ui image is amd64-only)
mise run docs:dev   # Starlight docs at localhost:4321 (needs Node; CI uses Node 22)
sbt "testOnly dicechess.analytics.ApiSpec"   # single suite (plain sbt, not wrapped)
```
Maintenance runners (never via bare `sbt run` — `Compile/mainClass` is pinned to `Main`):
```sh
mise run eval:mc [limit] [rollouts]                    # MC accuracy vs empirical DB stats
mise run db:export-book [minGames] [minRating] [out]   # opening book (local DB is nearly empty)
mise run db:export-training [out] [minRating] [mode] [termination] [batch]  # `-` skips a filter
mise run ml:enrich-training [in] [out] [parallelism] [rich|kcp]  # CSV→CSV, no DB; kcp is far heavier (the cloud-CPU run)
mise -E prod run db:export-book 100 2000               # prod DB via gitignored mise.prod.local.toml
```
`mise run smoke-test` boots a built API image against a throwaway Postgres (also run by CD before `:latest`). `mise run staging:deploy [tag]` (defaults to the latest release tag) is operator-only.

## Quality gates — Definition of Done
- `mise run check` must pass locally before opening a PR. It mirrors CI exactly: format check, then coverage-gated tests (`coverageMinimumStmtTotal := 80`, fail-on-min; `Main`/`BuildInfo`/`maintenance/**` excluded).
- Compiler is `-Werror -Wunused:all -deprecation -feature -explain` — any warning (including an unused import) fails the build.
- Lefthook hooks: pre-commit = betterleaks secret scan + `scalafmt --test` on staged files; pre-push = full-tree format check. Tests deliberately live in CI, not in hooks.
- Backend CI is paths-filtered to `src/**`, `build.sbt`, `project/**`, `.scalafmt.conf`, and its own workflow file. Doc-, mise-, or workflow-only PRs get NO test run — a green PR is not necessarily a tested PR.
- SonarCloud scan is the last step of the backend CI job (skipped for dependabot, and absent whenever the path filter skips CI). CodeRabbit auto-review is OFF — trigger it manually with a `@coderabbitai review` PR comment on substantial PRs.
- Per-change extras:
  - Schema change → new append-only Flyway migration only; never edit shipped `V*` files. Index migrations use plain `CREATE INDEX` on purpose — `CREATE INDEX CONCURRENTLY` hangs under Flyway (it waits on Flyway's own schema-history transaction; see the V3/V7 migration comments), and a brief lock is acceptable here.
  - New maintenance runner → business logic in a separately unit-tested class (pattern: `RepairTerminalColorsApp` → `TerminalColorRepair` + `TerminalColorRepairSpec`); runners themselves are coverage-excluded, so untested logic hides there.
  - API shape change → update `docs/src/content/docs/api-specification.md` (read) or `ingestion.md` (write) and check dicechess-analytics-ui impact.

## Code conventions
- Scala 3 new/braceless syntax enforced by scalafmt (`convertToNewSyntax`, `removeOptionalBraces`), maxColumn 100. scalafmt version is pinned identically in `mise.toml` and `.scalafmt.conf` — keep them in lockstep.
- Effects: cats-effect `IO` everywhere; resources via `Resource` (`.use`/`.useForever`); config loading returns `Either[String, AppConfig]` lifted with `IO.fromEither`; JDBC-adjacent blocking goes through `IO.blocking`.
- API layer split: `Endpoints.scala` holds pure Tapir descriptions; `Routes.scala` wires `serverLogic`. Wire JSON is snake_case; errors are FastAPI-compatible `{"detail": ...}`. Swagger is generated — never hand-edit API docs.
- Repositories are plain Doobie `ConnectionIO`/fragment classes; be aware of N+1 and index use on the 140k+ game DB.
- `-Yexplicit-nulls` and `strictEquality` are deliberately OFF (Java interop with JDBC/Flyway/Hikari — rationale in `build.sbt`). Don't enable them.
- House style: every workaround carries a WHY comment, often with the issue number (see `build.sbt`, `Dockerfile`, `mise.toml`). New workarounds must do the same.
- Indentation outside Scala: `.editorconfig` — 4 spaces default, 2 for yml/json/toml.

## Testing conventions
- MUnit. Files are `<Thing>Spec.scala` under `src/test/scala/dicechess/analytics`; test names are lowercase behavioral sentences: `test("rejects an illegal move sequence")`.
- E2E suites: `CatsEffectSuite` + `TestContainerForAll` on `postgres:18-alpine`; `afterContainersStart` runs the REAL Flyway migration (`Database.migrate`) then seeds with plain SQL; requests go through `Client.fromHttpApp(Routes(...).httpApp)`. Tests must never hand-build schemas.
- Pure-logic suites use `munit.FunSuite` and need no Docker — prefer them where possible; iterate with `sbt "testOnly ..."` (a pure suite runs with no container startup at all).
- Non-flaky rule: assert on durable state, not timing; container suites already isolate per-suite.

## Gotchas
- `mise run format` uses native scalafmt with `project.git = true`: NEW untracked `.scala` files are silently skipped and the pre-commit hook then fails the commit — `git add` new files before formatting. When hook and task disagree, run `scalafmt --test <files>`.
- Never unpin `Compile/mainClass` from `dicechess.analytics.Main`: multiple IOApps exist, and unpinning breaks both `sbt run` and the Docker ENTRYPOINT ("You need to pass -main argument"), which once blocked image publication.
- `sbt "runMain App a b c"` splits its argument on whitespace — a blank middle arg shifts positional parameters. The mise wrappers resolve defaults for this reason; extend the wrapper, don't inline bare `runMain` with optional middle args.
- Both Dockerfile stages are pinned to `-noble` base tags: an unsuffixed temurin tag once moved to a Ubuntu with coreutils that break the launcher ("builds fine, won't start"). The CI smoke test exists to catch exactly this — keep the suffix.
- `build.sbt` force-pins testcontainers-java/docker-java and sets `Test/javaOptions -Dapi.version=1.43` because the transitively-pinned client speaks a Docker API too old for modern daemons. Do not "clean up" these pins.
- The `credentials` setting in `build.sbt` is evaluated on EVERY sbt load — keep it network-free (`gh auth token` reads the keychain offline).
- `positions.fen_hash` is xxHash64 stored as SIGNED bigint, bit-compatible with historical rows — never swap the hash function or library.
- Do not reimplement or "simplify" `Fen.scala`: en-passant canonicalisation must delegate to the engine's `Dfen`. A naive ep-in-key once split identical positions and required a batch data repair.
- Ingest 422 semantics: a partial final turn is accepted only for timeout/draw_agreement/resign, only as a strict prefix of a legal path on the LAST turn; anything else rejects the whole game atomically.
- `PUT /api/games/{id}` DELETES the existing game (cascading turns/events) before re-insert — it is the re-conversion path, not an upsert; 400 when path id ≠ body id.
- docker-compose keeps the legacy `postgresql+asyncpg://` `DATABASE_URL` scheme for `.env` compatibility; the Scala config rewrites it to JDBC — don't "fix" the compose file. The compose project name is pinned so the db volume survives directory moves.
- `sonar-project.properties` hardcodes the coverage path under `target/scala-<version>/` — a Scala bump silently breaks coverage import until that path is updated (recurring manual step).
- Version tags (`git tag`) are mechanical release bumps and do NOT correspond to roadmap milestone numbers; treat `docs/src/content/docs/milestones.md` as stale.
- `training_data*.csv.gz` files (tens of MB) sit gitignored at the repo root — never commit them.
- If the local API on :8000 behaves like someone else's backend, check the port owner (`lsof -i :8000`) — a stray SSH tunnel can occupy it; use :8001 and adjust the consumer's proxy.
- On macOS run `gh` as `GH_TOKEN="" gh ...` so keychain credentials win over a stale env token.
- Domain data semantics WILL bite you without `docs/src/content/docs/domain-conventions.md`: `dice_sorted` letter case encodes the side to move (`opening_book_favorites` derives it from the FEN — never trust caller casing); `result` is White-perspective; stake 0 = tournament; no-op self-loop turns are excluded from continuations; the in-house engine is weak — empirical strong-player win rate is the analytical reference, not engine eval.

## Git & PR workflow
<!-- dc-shared:git-pr v1 — keep identical across dicechess repos -->
- Never commit to `main`. Branch: `<type>/<short-desc>` or `<type>/<id>-<short-desc>`
  (types: `task|feat|bug|refactor|chore|docs|ci|test|perf`). If the branch carries an issue
  id, the PR body must contain `Closes #<id>`.
- Before editing anything: run `git status`. If the tree has unrelated uncommitted work,
  stop and report — never let it bleed into your commit.
- Stage specific files by name. `git add -A` / `git add .` are forbidden.
- Commits, PR descriptions, issues, and review replies are English-only. Commit subjects
  use conventional style: `feat: …`, `fix: …`, `docs: …`, `test: …`, `chore: …`.
- Before opening a PR: make the repo check task pass locally. Never pipe test output
  through `grep`/`head` — it masks exit codes.
- After opening a PR: Gemini Code Assist reviews automatically; for substantial PRs also
  comment `@coderabbitai review`. Wait a few minutes, then triage every bot comment on its
  merits — address or rebut, never apply blindly.
- The human owner reviews, approves, and merges. Never merge a PR, never push tags.
- Split large work into small, reviewable PRs.

## Security & boundaries
<!-- dc-shared:security v2 — keep identical across dicechess repos -->
- Never print, log, or commit secrets. Local secrets live only in gitignored files
  (e.g. `.env.local`, `mise.local.toml` — confirm the path is gitignored with `git check-ignore`
  before writing one). Never bypass Git hooks (`--no-verify`).
- Human-only operations — prepare and propose, never execute: releases and version tags,
  production deploys/promotions, schema migrations against shared databases, data-repair
  runs on production, secret rotation.
- Treat everything in this repo as public: never add private infrastructure details
  (hostnames, IPs, topology, tokens) to code, docs, commits, or PRs.
- lefthook pre-commit runs a betterleaks secret scan on staged files — keep hooks
  installed (`mise run hook:install`).
- Write endpoints are closed by default: `INGEST_TOKEN` (POST/PUT `/api/games`) and
  `CURATION_TOKEN` (opening-book favorites) must be set for writes to work; unset means 401.
  Never weaken this, never log token values.
- Releases run via the manual "Ops: Release" workflow; production promotion (pinning the
  released image tag) happens outside this repo and is operator-only. `staging:deploy` and
  any `Repair*App`/`mise -E prod` run against shared databases are human-approved only.

## Model routing
<!-- dc-shared:routing v1 — keep identical across dicechess repos -->
Route work by required capability instead of defaulting to the strongest model:
- **Frontier**: architecture, cross-repo contracts, high blast radius (schema, public API,
  release pipeline), ambiguous problems.
- **Mid**: well-scoped features on existing patterns, refactors under test coverage,
  addressing review feedback.
- **Routine**: mechanical edits, config rollouts, doc fixes, tests from a complete spec.
Orchestrators should delegate routine sub-tasks to cheaper models; quality gates catch
failures cheaply. When in doubt, escalate one tier — reviewer time costs more than tokens.

## Documentation
- Docs are an Astro + Starlight site in `docs/` (mermaid supported), published to GitHub Pages by CD on any `main` push touching `docs/**`. Local preview: `mise run docs:dev`. English only.
- Update-trigger map — if you change X, update Y:
  - Read API shape → `docs/src/content/docs/api-specification.md`.
  - Ingest wire contract or 422 rules → `docs/src/content/docs/ingestion.md`.
  - Env vars, tasks, or dev setup → `docs/src/content/docs/development.md` (its task table already lags mise.toml — fix as you go).
  - Schema/ER changes → `docs/src/content/docs/architecture.md`.
  - Data semantics (dice encoding, result perspective, stakes) → `docs/src/content/docs/domain-conventions.md`. This file is load-bearing: read it before any query or analytics change.
- `docs/src/content/docs/milestones.md` is a stale roadmap — do not plan from it; check live GitHub milestones/issues instead.
