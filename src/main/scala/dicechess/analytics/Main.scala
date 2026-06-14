package dicechess.analytics

import cats.effect.{IO, IOApp}
import org.http4s.ember.server.EmberServerBuilder

import dicechess.analytics.api.Routes

object Main extends IOApp.Simple:

  def run: IO[Unit] =
    for
      config <- IO.fromEither(AppConfig.load().left.map(msg => IllegalArgumentException(msg)))
      _      <- Database.migrate(config.db)
      _      <- Database
        .transactor(config.db, config.dbPoolSize)
        .use { xa =>
          val app = Routes(xa, config.corsOrigins, config.ingestToken).httpApp
          EmberServerBuilder
            .default[IO]
            .withHost(config.host)
            .withPort(config.port)
            .withHttpApp(app)
            .build
            .useForever
        }
    yield ()
