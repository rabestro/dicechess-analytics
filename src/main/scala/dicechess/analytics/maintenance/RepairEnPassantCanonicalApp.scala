package dicechess.analytics.maintenance

import cats.effect.{IO, IOApp}
import doobie.implicits.*

import dicechess.analytics.{AppConfig, Database}

/** One-shot runner for [[EnPassantCanonicalRepair]] (issue #203).
  *
  * Run by an operator against a target database (`DATABASE_URL` in the environment):
  *
  * {{{sbt "runMain dicechess.analytics.maintenance.RepairEnPassantCanonicalApp"}}}
  *
  * It applies any pending Flyway migrations first — the repair relies on the foreign-key indexes
  * from V7 to delete orphaned positions without a per-row sequential scan — and then runs the data
  * repair. Both steps are idempotent, so a re-run after a partial failure is safe and reports zero
  * changes once the data is clean.
  *
  * Deploy ordering: ship the canonical [[dicechess.analytics.Fen.normalize]] first (so new ingests
  * stop producing split rows), then run this repair to collapse the history.
  */
object RepairEnPassantCanonicalApp extends IOApp.Simple:

  def run: IO[Unit] =
    for
      config <- IO.fromEither(AppConfig.load().left.map(msg => IllegalArgumentException(msg)))
      _      <- Database.migrate(config.db)
      report <- Database
        .transactor(config.db, config.dbPoolSize)
        .use(xa => EnPassantCanonicalRepair.run.transact(xa))
      _ <- IO.println(s"EnPassantCanonicalRepair complete: $report")
    yield ()
