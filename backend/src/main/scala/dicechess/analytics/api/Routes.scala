package dicechess.analytics.api

import cats.effect.IO
import cats.syntax.all.*
import doobie.Transactor
import doobie.implicits.*
import org.http4s.HttpApp
import org.http4s.server.middleware.CORS
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import dicechess.analytics.repository.{GamesRepository, PlayersRepository}
import Protocol.*

/** Wires endpoint definitions to repository logic and assembles the HTTP application. */
final class Routes(xa: Transactor[IO], corsOrigins: List[String]):

  private val defaultLimit = 50

  private val rootLogic = Endpoints.root.serverLogicSuccess[IO](_ =>
    IO.pure(Welcome(message = "Welcome to Dice Chess Analytics API", docs = "/docs"))
  )

  private val listGamesLogic = Endpoints.listGames.serverLogicSuccess[IO] {
    case (playerId, minTurns, limit, offset) =>
      GamesRepository
        .list(playerId, minTurns, limit.getOrElse(defaultLimit), offset.getOrElse(0))
        .transact(xa)
  }

  private val getGameLogic = Endpoints.getGame.serverLogic[IO] { gameId =>
    GamesRepository
      .detail(gameId)
      .transact(xa)
      .map(_.toRight(ApiError("Game not found")))
  }

  private val listPlayersLogic = Endpoints.listPlayers.serverLogicSuccess[IO] {
    case (username, limit) =>
      PlayersRepository.list(username, limit.getOrElse(defaultLimit)).transact(xa)
  }

  private val getPlayerLogic = Endpoints.getPlayer.serverLogic[IO] { playerId =>
    PlayersRepository
      .get(playerId)
      .transact(xa)
      .map(_.toRight(ApiError("Player not found")))
  }

  private val swagger = SwaggerInterpreter()
    .fromEndpoints[IO](Endpoints.all, "Dice Chess Analytics API", "1.0.0")

  def httpApp: HttpApp[IO] =
    val routes = Http4sServerInterpreter[IO]().toRoutes(
      swagger ++ List(listGamesLogic, getGameLogic, listPlayersLogic, getPlayerLogic, rootLogic)
    )
    val cors = CORS.policy
      .withAllowOriginHost(corsOrigins.flatMap(originHost).toSet)
      .withAllowCredentials(true)
      .apply(routes)
    cors.orNotFound

  private def originHost(origin: String): Option[org.http4s.headers.Origin.Host] =
    org.http4s.Uri.fromString(origin).toOption.flatMap { uri =>
      (uri.scheme, uri.host).mapN { (scheme, host) =>
        org.http4s.headers.Origin.Host(scheme, host, uri.port)
      }
    }
