package dicechess.analytics

import cats.effect.{IO, Resource}
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.flywaydb.core.Flyway

object Database:

  /** Applies Flyway migrations.
    *
    * `baselineOnMigrate` is enabled because the production database was created by the Python app's
    * alembic migrations: on first contact Flyway records a baseline instead of attempting to
    * re-create the schema. Fresh databases (tests, new environments) get the full schema from
    * `db/migration`.
    */
  def migrate(config: DbConfig): IO[Unit] =
    IO.blocking {
      Flyway
        .configure()
        .dataSource(config.jdbcUrl, config.user, config.password)
        .baselineOnMigrate(true)
        .baselineVersion("1")
        // Tolerates slow container/port-forward startup (testcontainers, compose)
        .connectRetries(10)
        .load()
        .migrate()
    }.void

  def transactor(config: DbConfig, poolSize: Int = 16): Resource[IO, HikariTransactor[IO]] =
    for
      ce <- ExecutionContexts.fixedThreadPool[IO](poolSize)
      xa <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "org.postgresql.Driver",
        url = config.jdbcUrl,
        user = config.user,
        pass = config.password,
        connectEC = ce
      )
    yield xa
