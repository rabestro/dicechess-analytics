package dicechess.analytics

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.{ipv4, port, Host, Port}
import org.http4s.ember.server.EmberServerBuilder

import dicechess.analytics.api.Routes

object Main extends IOApp.Simple:

  def run: IO[Unit] =
    for
      config <- IO.fromEither(AppConfig.load().left.map(msg => IllegalArgumentException(msg)))
      _      <- Database.migrate(config.db)
      _ <- Database
        .transactor(config.db)
        .use { xa =>
          val app = Routes(xa, config.corsOrigins).httpApp
          EmberServerBuilder
            .default[IO]
            .withHost(Host.fromString(config.host).getOrElse(ipv4"0.0.0.0"))
            .withPort(Port.fromInt(config.port).getOrElse(port"8000"))
            .withHttpApp(app)
            .build
            .useForever
        }
    yield ()
