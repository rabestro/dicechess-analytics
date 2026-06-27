package dicechess.analytics.maintenance

import cats.effect.{IO, IOApp}

import dicechess.analytics.{AppConfig, Database}

/** One-shot runner for [[PartialTurnsRepair]].
  *
  * Run by an operator against a target database (`DATABASE_URL` in the environment):
  *
  * {{{sbt "runMain dicechess.analytics.maintenance.RepairPartialTurnsApp"}}}
  */
object RepairPartialTurnsApp extends IOApp.Simple:

  def run: IO[Unit] =
    for
      config <- IO.fromEither(AppConfig.load().left.map(msg => IllegalArgumentException(msg)))
      _      <- Database.migrate(config.db)
      report <- Database
        .transactor(config.db, config.dbPoolSize)
        .use(xa => PartialTurnsRepair.run(xa))
      _ <- IO.println(s"PartialTurnsRepair complete: $report")
    yield ()
