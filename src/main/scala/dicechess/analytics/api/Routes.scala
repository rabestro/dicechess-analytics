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

import dicechess.analytics.AppConfig
import dicechess.analytics.ingest.{GameReplay, ReplayError, TurnInput}
import dicechess.analytics.repository.{
  GamesRepository,
  IngestRepository,
  PlayersRepository,
  PositionsRepository,
  UserRepository
}
import IngestProtocol.*
import Protocol.*

/** Wires endpoint definitions to repository logic and assembles the HTTP application. */
final class Routes(
    xa: Transactor[IO],
    config: AppConfig,
    version: VersionInfo
):

  private val ingestToken  = config.ingestToken
  private val curatorToken = config.curatorToken
  private val corsOrigins  = config.corsOrigins

  private val defaultLimit = 50

  private val rootLogic = Endpoints.root.serverLogicSuccess[IO](_ =>
    IO.pure(Welcome(message = "Welcome to Dice Chess Analytics API", docs = "/docs"))
  )

  private val versionLogic = Endpoints.version.serverLogicSuccess[IO](_ => IO.pure(version))

  private val listGamesLogic = Endpoints.listGames.serverLogic[IO] { query =>
    // `color` is meaningless without a focal player; reject rather than silently widen the result.
    if query.color.isDefined && query.playerId.isEmpty then
      IO.pure(Left(ApiError("color requires player_id")))
    else GamesRepository.list(query, defaultLimit).transact(xa).map(Right(_))
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

  private val playerStatsLogic = Endpoints.playerStats.serverLogic[IO] { query =>
    PlayersRepository
      .stats(query)
      .transact(xa)
      .map(_.toRight(ApiError("Player not found")))
  }

  private val breakdownsLogic = Endpoints.breakdowns.serverLogic[IO] { query =>
    PlayersRepository
      .breakdowns(query)
      .transact(xa)
      .map(_.toRight(ApiError("Player not found")))
  }

  private val profitHistoryLogic = Endpoints.profitHistory.serverLogic[IO] { query =>
    PlayersRepository
      .profitHistory(query)
      .transact(xa)
      .map(_.toRight(ApiError("Player not found")))
  }

  private val ratingHistoryLogic = Endpoints.ratingHistory.serverLogic[IO] { query =>
    PlayersRepository
      .ratingHistory(query)
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

  private val diceDistributionLogic = Endpoints.diceDistribution.serverLogicSuccess[IO] { query =>
    PositionsRepository
      .diceDistribution(query.fen, query.mode, query.source, query.minRating)
      .transact(xa)
  }

  // Bearer auth for the write path. No token configured ⇒ reject (closed by default).
  // Constant-time compare to avoid leaking the secret via timing.
  private def tokenAccepted(provided: String): Boolean =
    ingestToken.exists(expected =>
      MessageDigest.isEqual(expected.getBytes(UTF_8), provided.getBytes(UTF_8))
    )

  private def curatorAccepted(provided: String): Boolean =
    curatorToken.exists(expected =>
      MessageDigest.isEqual(expected.getBytes(UTF_8), provided.getBytes(UTF_8))
    )

  private def describe(error: ReplayError): String = error match
    case ReplayError.InvalidInitialFen(_, reason) => s"Invalid initial FEN: $reason"
    case ReplayError.UnknownDie(turn, value)      => s"Turn $turn: unknown die value $value"
    case ReplayError.EmptyDice(turn)              => s"Turn $turn: dice pool cannot be empty"
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

  private val replaceGameLogic =
    Endpoints.replaceGame
      .serverSecurityLogic[Unit, IO] { token =>
        IO.pure(
          if tokenAccepted(token) then Right(())
          else Left((StatusCode.Unauthorized, ApiError("Invalid or missing ingest token")))
        )
      }
      .serverLogic { _ => input =>
        val (gameId, game) = input
        if gameId != game.id then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ApiError(s"Path id $gameId does not match body id ${game.id}")
              )
            )
          )
        else
          GameReplay.replay(
            game.initialFen,
            game.turns.map(t => TurnInput(t.dice, t.moves)),
            game.termination
          ) match
            case Left(error) =>
              IO.pure(Left((StatusCode.UnprocessableEntity, ApiError(describe(error)))))
            case Right(replayed) =>
              IngestRepository.persistReplace(game, replayed).transact(xa).map { created =>
                val status = if created then StatusCode.Created else StatusCode.Ok
                Right((status, IngestResult(game.id, created)))
              }
      }

  private val getFavoritesLogic = Endpoints.getFavorites.serverLogicSuccess[IO] { fen =>
    PositionsRepository.favoritesForPosition(fen).transact(xa)
  }

  private val putFavoriteLogic =
    Endpoints.putFavorite
      .serverSecurityLogic[Unit, IO] { token =>
        IO.pure(
          if curatorAccepted(token) then Right(())
          else Left((StatusCode.Unauthorized, ApiError("Invalid or missing curator token")))
        )
      }
      .serverLogic { _ => input =>
        if input.dice.trim.isEmpty then
          IO.pure(
            Left((StatusCode.BadRequest, ApiError("Favorite must have a non-empty dice roll")))
          )
        else if input.moves.isEmpty || input.moves.exists(_.trim.isEmpty) then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ApiError("Favorite must have at least one move and no blank moves")
              )
            )
          )
        else
          PositionsRepository
            .setFavorite(input.fen, input.dice, input.moves, input.note)
            .transact(xa)
            .map(Right(_))
      }

  private val deleteFavoriteLogic =
    Endpoints.deleteFavorite
      .serverSecurityLogic[Unit, IO] { token =>
        IO.pure(
          if curatorAccepted(token) then Right(())
          else Left((StatusCode.Unauthorized, ApiError("Invalid or missing curator token")))
        )
      }
      .serverLogic { _ => input =>
        PositionsRepository.deleteFavorite(input.fen, input.dice).transact(xa).map { deleted =>
          if deleted then Right(())
          else Left((StatusCode.NotFound, ApiError("Favorite not found")))
        }
      }

  private val listUsersLogic = Endpoints.listUsers.serverLogic[IO] { status =>
    UserRepository.list(status).transact(xa).map { users =>
      Right(
        users.map(u =>
          UserResponse(
            u.id,
            u.email,
            u.name,
            u.pictureUrl,
            u.role,
            u.isApproved,
            u.isActive,
            u.lastLoginAt,
            u.createdAt
          )
        )
      )
    }
  }

  private val updateUserLogic = Endpoints.updateUser.serverLogic[IO] { case (userId, update) =>
    val action = for
      existing <- UserRepository.get(userId)
      res      <- existing match
        case None       => doobie.free.connection.pure(Left(ApiError("User not found")))
        case Some(user) =>
          val invalidRole = update.role.exists(r => r != "USER" && r != "ADMIN")
          if invalidRole then
            doobie.free.connection.pure(Left(ApiError("Invalid role. Must be USER or ADMIN")))
          else
            val demoting  = user.role == "ADMIN" && update.role.contains("USER")
            val disabling = user.role == "ADMIN" && (update.isActive.contains(
              false
            ) || update.isApproved.contains(false))
            for
              adminCount <-
                if demoting || disabling then UserRepository.countActiveAdmins()
                else doobie.free.connection.pure(2)
              result <-
                if (demoting || disabling) && adminCount <= 1 then
                  doobie.free.connection.pure(
                    Left(ApiError("Cannot demote or disable the last active admin"))
                  )
                else
                  for
                    _ <- update.isApproved
                      .map(UserRepository.updateApproval(userId, _))
                      .getOrElse(doobie.free.connection.pure(0))
                    _ <- update.isActive
                      .map(UserRepository.updateActive(userId, _))
                      .getOrElse(doobie.free.connection.pure(0))
                    _ <- update.role
                      .map(UserRepository.updateRole(userId, _))
                      .getOrElse(doobie.free.connection.pure(0))
                  yield Right(MessageResponse("User updated successfully"))
            yield result
    yield res
    action.transact(xa)
  }

  private val deleteUserLogic = Endpoints.deleteUser.serverLogic[IO] { userId =>
    val action = for
      existing <- UserRepository.get(userId)
      res      <- existing match
        case None       => doobie.free.connection.pure(Left(ApiError("User not found")))
        case Some(user) =>
          for
            adminCount <-
              if user.role == "ADMIN" && user.isApproved && user.isActive then
                UserRepository.countActiveAdmins()
              else doobie.free.connection.pure(2)
            result <-
              if user.role == "ADMIN" && user.isApproved && user.isActive && adminCount <= 1 then
                doobie.free.connection.pure(Left(ApiError("Cannot delete the last active admin")))
              else
                UserRepository.delete(userId).map { count =>
                  if count > 0 then Right(MessageResponse("User deleted successfully"))
                  else Left(ApiError("User not found"))
                }
          yield result
    yield res
    action.transact(xa)
  }

  private val swagger = SwaggerInterpreter()
    .fromEndpoints[IO](Endpoints.all, "Dice Chess Analytics API", version.version)

  def httpApp: HttpApp[IO] =
    val tapirRoutes = Http4sServerInterpreter[IO]().toRoutes(
      swagger ++ List(
        listGamesLogic,
        getGameLogic,
        listPlayersLogic,
        getPlayerLogic,
        playerStatsLogic,
        breakdownsLogic,
        profitHistoryLogic,
        ratingHistoryLogic,
        continuationsLogic,
        positionEquityLogic,
        diceDistributionLogic,
        rootLogic,
        versionLogic,
        ingestGameLogic,
        replaceGameLogic,
        getFavoritesLogic,
        putFavoriteLogic,
        deleteFavoriteLogic,
        listUsersLogic,
        updateUserLogic,
        deleteUserLogic
      )
    )
    val authRoutes     = AuthRoutes.routes(xa, config)
    val combinedRoutes = authRoutes <+> tapirRoutes
    val securedRoutes  = AuthMiddleware(xa, config.secretKey, config.mockAuth)(combinedRoutes)
    val cors           = CORS.policy
      .withAllowOriginHost(corsOrigins.flatMap(originHost).toSet)
      .withAllowCredentials(true)
      .apply(securedRoutes)
    cors.orNotFound

  private def originHost(origin: String): Option[org.http4s.headers.Origin.Host] =
    org.http4s.Uri.fromString(origin).toOption.flatMap { uri =>
      (uri.scheme, uri.host).mapN { (scheme, host) =>
        org.http4s.headers.Origin.Host(scheme, host, uri.port)
      }
    }
