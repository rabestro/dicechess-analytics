# Code review style guide

Generated extract of the review-relevant rules in AGENTS.md — keep in sync manually.

## Code conventions
- Scala 3 new/braceless syntax (scalafmt enforces `convertToNewSyntax` + `removeOptionalBraces`, maxColumn 100). Flag legacy brace-style or `implicit`-era idioms.
- cats-effect `IO` throughout; lifecycles via `Resource`; JDBC-adjacent blocking must use `IO.blocking`. Flag raw side effects and unmanaged resources.
- API layer split: pure Tapir descriptions in `api/Endpoints.scala`, server logic only in `api/Routes.scala`. Wire JSON is snake_case; errors are `{"detail": ...}` — flag any deviation (the UI depends on this shape).
- The build uses `-Werror -Wunused:all`: unused imports/params are build failures.
- Every workaround needs a WHY comment (ideally with an issue number) — flag unexplained pins, version forces, or odd config.

## Forbidden patterns
- Re-implementing chess/dice rules — game logic comes only from the dicechess-engine-scala dependency (`ingest/GameReplay` replays games through it).
- Editing shipped Flyway migrations (`V1`–`V8`): migrations are append-only. Also flag `CREATE INDEX CONCURRENTLY` in new migrations — it hangs under Flyway here (see the V3/V7 migration comments); plain `CREATE INDEX` is this repo's deliberate convention.
- Changing `positions.fen_hash` hashing (signed xxHash64, bit-compatible with historical rows) or the X-FEN en-passant canonicalisation in `Fen.scala`.
- Unpinning `Compile/mainClass` from `dicechess.analytics.Main`, or removing the testcontainers/docker-java force-pins and `-Dapi.version` in `build.sbt`.
- Hardcoding or logging secrets (`INGEST_TOKEN`, `CURATION_TOKEN`, `DATABASE_URL`); write endpoints must stay closed-by-default.
- Committing `training_data*.csv.gz` or other generated data files.

## Testing expectations
- MUnit; suites named `<Thing>Spec.scala`; test names are lowercase behavioral sentences.
- E2E suites must run the real Flyway migration inside a `postgres:18-alpine` testcontainer and go through `Client.fromHttpApp` — never hand-built schemas or mocked SQL.
- `maintenance/**` is excluded from coverage: business logic must live in separately unit-tested classes, with runners as thin shells. Flag logic added directly to `*App` runners.

## Documentation standards
- All code comments, commits, PR text, and docs are English-only.
- API shape changes must update `docs/src/content/docs/api-specification.md` or `ingestion.md`; schema changes update `architecture.md`; data-semantics changes update `domain-conventions.md`.

## High-value review gotchas
- `PUT /api/games/{id}` intentionally deletes and re-inserts (re-conversion path, not an upsert) — do not "fix" it into an update.
- Ingest 422 rules: a partial final turn is valid only for timeout/draw_agreement/resign on the last turn; everything else must reject atomically.
- `result` is White-perspective and `dice_sorted` letter case encodes the side to move — flag any query or code that ignores the mover flip or trusts caller-supplied casing.
- docker-compose deliberately keeps the legacy `postgresql+asyncpg://` URL scheme (rewritten to JDBC in config) — do not normalize it.
- Doobie repositories run against a 140k+ game database — watch for N+1 patterns and missing index usage in new queries.
