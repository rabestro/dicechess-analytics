package dicechess.analytics

import cats.effect.{IO, IOApp}
import org.http4s.ember.server.EmberServerBuilder

import dicechess.analytics.api.Routes
import dicechess.analytics.api.Protocol.VersionInfo

object Main extends IOApp.Simple:

  // Effective version: the release tag injected at deploy time (APP_VERSION env), else the
  // build.sbt version baked by BuildInfo (e.g. 0.1.0-SNAPSHOT for local/dev). An IO value, so the
  // env read is suspended and runs at startup — not at object initialization.
  private val resolveVersion: IO[VersionInfo] = IO {
    VersionInfo(
      name = BuildInfo.name,
      version = sys.env.get("APP_VERSION").filter(_.nonEmpty).getOrElse(BuildInfo.version),
      scalaVersion = BuildInfo.scalaVersion
    )
  }

  def run: IO[Unit] =
    for
      config      <- IO.fromEither(AppConfig.load().left.map(msg => IllegalArgumentException(msg)))
      versionInfo <- resolveVersion
      _           <- Database.migrate(config.db)
      _           <- Database
        .transactor(config.db, config.dbPoolSize)
        .use { xa =>
          val app = Routes(xa, config.corsOrigins, config.ingestToken, versionInfo).httpApp
          EmberServerBuilder
            .default[IO]
            .withHost(config.host)
            .withPort(config.port)
            .withHttpApp(app)
            .build
            .useForever
        }
    yield ()
