package dicechess.analytics.api

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

import cats.effect.IO
import cats.syntax.all.*
import doobie.Transactor
import doobie.implicits.*
import org.http4s.HttpApp
import org.http4s.server.middleware.CORS
import sttp.model.StatusCode
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import dicechess.analytics.ingest.{GameReplay, ReplayError, TurnInput}
import dicechess.analytics.repository.{
  GamesRepository,
  IngestRepository,
  PlayersRepository,
  PositionsRepository
}
import IngestProtocol.*
import Protocol.*

/** Wires endpoint definitions to repository logic and assembles the HTTP application. */
final class Routes(
    xa: Transactor[IO],
    corsOrigins: List[String],
    ingestToken: Option[String],
    version: VersionInfo
):

  private val defaultLimit = 50

  private val rootLogic = Endpoints.root.serverLogicSuccess[IO](_ =>
    IO.pure(Welcome(message = "Welcome to Dice Chess Analytics API", docs = "/docs"))
  )

  private val versionLogic = Endpoints.version.serverLogicSuccess[IO](_ => IO.pure(version))

  private val listGamesLogic = Endpoints.listGames.serverLogicSuccess[IO] { query =>
    GamesRepository.list(query, defaultLimit).transact(xa)
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

  private val continuationsLogic = Endpoints.continuations.serverLogicSuccess[IO] { query =>
    PositionsRepository
      .continuations(
        query.fen,
        query.dice,
        query.mode,
        query.source,
        query.minRating,
        query.limit.getOrElse(defaultLimit)
      )
      .transact(xa)
  }

  private val positionEquityLogic = Endpoints.positionEquity.serverLogicSuccess[IO] { query =>
    PositionsRepository.equity(query.fen, query.mode, query.source, query.minRating).transact(xa)
  }

  // Bearer auth for the write path. No token configured ⇒ reject (closed by default).
  // Constant-time compare to avoid leaking the secret via timing.
  private def tokenAccepted(provided: String): Boolean =
    ingestToken.exists(expected =>
      MessageDigest.isEqual(expected.getBytes(UTF_8), provided.getBytes(UTF_8))
    )

  private def describe(error: ReplayError): String = error match
    case ReplayError.InvalidInitialFen(_, reason) => s"Invalid initial FEN: $reason"
    case ReplayError.UnknownDie(turn, value)      => s"Turn $turn: unknown die value $value"
    case ReplayError.IllegalTurn(turn, played, _) =>
      s"Turn $turn: illegal move sequence [${played.mkString(", ")}]"

  private val ingestGameLogic =
    Endpoints.ingestGame
      .serverSecurityLogic[Unit, IO] { token =>
        IO.pure(
          if tokenAccepted(token) then Right(())
          else Left((StatusCode.Unauthorized, ApiError("Invalid or missing ingest token")))
        )
      }
      .serverLogic { _ => game =>
        GameReplay.replay(
          game.initialFen,
          game.turns.map(t => TurnInput(t.dice, t.moves)),
          game.termination
        ) match
          case Left(error) =>
            IO.pure(Left((StatusCode.UnprocessableEntity, ApiError(describe(error)))))
          case Right(replayed) =>
            IngestRepository.persist(game, replayed).transact(xa).map { created =>
              val status = if created then StatusCode.Created else StatusCode.Ok
              Right((status, IngestResult(game.id, created)))
            }
      }

  private val swagger = SwaggerInterpreter()
    .fromEndpoints[IO](Endpoints.all, "Dice Chess Analytics API", version.version)

  def httpApp: HttpApp[IO] =
    val routes = Http4sServerInterpreter[IO]().toRoutes(
      swagger ++ List(
        listGamesLogic,
        getGameLogic,
        listPlayersLogic,
        getPlayerLogic,
        continuationsLogic,
        positionEquityLogic,
        rootLogic,
        versionLogic,
        ingestGameLogic
      )
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
