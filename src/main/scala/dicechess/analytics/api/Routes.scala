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

  private val playerNotFound = "Player not found"

  private val noOp = doobie.free.connection.pure(0)

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
      .map(_.toRight(ApiError(playerNotFound)))
  }

  private val playerStatsLogic = Endpoints.playerStats.serverLogic[IO] { query =>
    PlayersRepository
      .stats(query)
      .transact(xa)
      .map(_.toRight(ApiError(playerNotFound)))
  }

  private val breakdownsLogic = Endpoints.breakdowns.serverLogic[IO] { query =>
    PlayersRepository
      .breakdowns(query)
      .transact(xa)
      .map(_.toRight(ApiError(playerNotFound)))
  }

  private val profitHistoryLogic = Endpoints.profitHistory.serverLogic[IO] { query =>
    PlayersRepository
      .profitHistory(query)
      .transact(xa)
      .map(_.toRight(ApiError(playerNotFound)))
  }

  private val ratingHistoryLogic = Endpoints.ratingHistory.serverLogic[IO] { query =>
    PlayersRepository
      .ratingHistory(query)
      .transact(xa)
      .map(_.toRight(ApiError(playerNotFound)))
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

  // Admin user-management results share one error channel (runtime status code + message).
  private type AdminResult = Either[(StatusCode, ApiError), MessageResponse]

  private val userNotFound: AdminResult = Left((StatusCode.NotFound, ApiError("User not found")))
  private val lastAdminBlocked: AdminResult =
    Left((StatusCode.Conflict, ApiError("Cannot demote, disable or delete the last active admin")))
  private def updated: AdminResult = Right(MessageResponse("User updated successfully"))

  private val updateUserLogic = Endpoints.updateUser.serverLogic[IO] { case (userId, update) =>
    val invalidRole = update.role.exists(r => r != "USER" && r != "ADMIN")
    // Any change that would drop this user out of the active-admin set.
    val removesAdmin =
      update.role.contains("USER") ||
        update.isApproved.contains(false) ||
        update.isActive.contains(false)

    val action: doobie.ConnectionIO[AdminResult] = UserRepository.get(userId).flatMap {
      case None                   => doobie.free.connection.pure(userNotFound)
      case Some(_) if invalidRole =>
        doobie.free.connection.pure(
          Left((StatusCode.BadRequest, ApiError("Invalid role. Must be USER or ADMIN")))
        )
      case Some(_) =>
        val applyUpdate: doobie.ConnectionIO[AdminResult] =
          for
            _ <- update.isApproved.fold(noOp)(UserRepository.updateApproval(userId, _))
            _ <- update.isActive.fold(noOp)(UserRepository.updateActive(userId, _))
            _ <- update.role.fold(noOp)(UserRepository.updateRole(userId, _))
          yield updated
        // Only the changes that could drop this user out of the admin set need to serialize against
        // concurrent demotions; the common case (e.g. approving a pending user) applies directly.
        if !removesAdmin then applyUpdate
        else
          UserRepository.lockActiveAdmins().flatMap { adminIds =>
            if adminIds.contains(userId) && adminIds.sizeIs <= 1 then
              doobie.free.connection.pure(lastAdminBlocked)
            else applyUpdate
          }
    }
    action.transact(xa)
  }

  private val deleteUserLogic = Endpoints.deleteUser.serverLogic[IO] { userId =>
    val action: doobie.ConnectionIO[AdminResult] = UserRepository.get(userId).flatMap {
      case None    => doobie.free.connection.pure(userNotFound)
      case Some(_) =>
        UserRepository.lockActiveAdmins().flatMap { adminIds =>
          if adminIds.contains(userId) && adminIds.sizeIs <= 1 then
            doobie.free.connection.pure(lastAdminBlocked)
          else
            UserRepository.delete(userId).map { count =>
              if count > 0 then Right(MessageResponse("User deleted successfully"))
              else userNotFound
            }
        }
    }
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
    val securedRoutes  = CookieAuthMiddleware(xa, config.secretKey, config.mockAuth)(combinedRoutes)
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
