package dicechess.analytics.maintenance

import cats.effect.{IO, IOApp}
import doobie.implicits.*

import dicechess.analytics.{AppConfig, Database}

/** One-shot runner for [[TerminalColorRepair]] (issue #161).
  *
  * Run by an operator against a target database (`DATABASE_URL` in the environment):
  *
  * {{{sbt "runMain dicechess.analytics.maintenance.RepairTerminalColorsApp"}}}
  *
  * It applies any pending Flyway migrations first — the repair relies on the foreign-key indexes
  * from V7 (#165) to delete orphaned positions without a per-row sequential scan — and then runs
  * the data repair. Both steps are idempotent, so a re-run after a partial failure is safe and
  * reports zero changes once the data is clean.
  */
object RepairTerminalColorsApp extends IOApp.Simple:

  def run: IO[Unit] =
    for
      config <- IO.fromEither(AppConfig.load().left.map(msg => IllegalArgumentException(msg)))
      _      <- Database.migrate(config.db)
      report <- Database
        .transactor(config.db, config.dbPoolSize)
        .use(xa => TerminalColorRepair.run.transact(xa))
      _ <- IO.println(s"TerminalColorRepair complete: $report")
    yield ()
