package dicechess.analytics.maintenance

import cats.effect.{IO, IOApp}
import doobie.implicits.*

import dicechess.analytics.{AppConfig, Database}

/** One-shot runner for [[TerminalColorRepair]] (issue #161).
  *
  * Deliberately separate from the server `Main` and from Flyway: this is a data repair, not a
  * schema migration, so it runs only when an operator invokes it against a target database
  * (`DATABASE_URL` in the environment):
  *
  * {{{sbt "runMain dicechess.analytics.maintenance.RepairTerminalColorsApp"}}}
  *
  * It is idempotent, so a re-run after a partial failure is safe and reports zero changes once the
  * data is clean.
  */
object RepairTerminalColorsApp extends IOApp.Simple:

  def run: IO[Unit] =
    for
      config <- IO.fromEither(AppConfig.load().left.map(msg => IllegalArgumentException(msg)))
      report <- Database
        .transactor(config.db, config.dbPoolSize)
        .use(xa => TerminalColorRepair.run.transact(xa))
      _ <- IO.println(s"TerminalColorRepair complete: $report")
    yield ()
