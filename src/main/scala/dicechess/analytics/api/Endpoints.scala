package dicechess.analytics.api

import java.util.UUID

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import Protocol.*

/** Typed endpoint definitions (the REST contract, kept identical to the FastAPI app). */
object Endpoints:

  private val notFound =
    statusCode(StatusCode.NotFound).and(jsonBody[ApiError].description("Entity not found"))

  val root: PublicEndpoint[Unit, Unit, Welcome, Any] =
    endpoint.get
      .in("")
      .out(jsonBody[Welcome])
      .description("Welcome endpoint providing basic API information")

  val listGames: PublicEndpoint[
    (Option[UUID], Option[Int], Option[Int], Option[Int]),
    Unit,
    List[GameSummary],
    Any
  ] =
    endpoint.get
      .in("api" / "games")
      .in(query[Option[UUID]]("player_id").description("Filter by player UUID (white or black)"))
      .in(query[Option[Int]]("min_turns").description("Minimum number of turns in the game"))
      .in(
        query[Option[Int]]("limit")
          .description("Page size, default 50")
          .validateOption(Validator.inRange(1, 200))
      )
      .in(
        query[Option[Int]]("offset")
          .description("Number of games to skip")
          .validateOption(Validator.min(0))
      )
      .out(jsonBody[List[GameSummary]])
      .description("List and filter games")

  val getGame: PublicEndpoint[UUID, ApiError, GameDetail, Any] =
    endpoint.get
      .in("api" / "games" / path[UUID]("game_id"))
      .out(jsonBody[GameDetail])
      .errorOut(notFound)
      .description("Get detailed information about a game, including its turns and events")

  val listPlayers: PublicEndpoint[(Option[String], Option[Int]), Unit, List[PlayerSummary], Any] =
    endpoint.get
      .in("api" / "players")
      .in(query[Option[String]]("username").description("Filter by username (substring)"))
      .in(
        query[Option[Int]]("limit")
          .description("Page size, default 50")
          .validateOption(Validator.inRange(1, 100))
      )
      .out(jsonBody[List[PlayerSummary]])
      .description("List players ordered by their current rating")

  val getPlayer: PublicEndpoint[UUID, ApiError, PlayerSummary, Any] =
    endpoint.get
      .in("api" / "players" / path[UUID]("player_id"))
      .out(jsonBody[PlayerSummary])
      .errorOut(notFound)
      .description("Get a specific player by UUID")

  val all = List(root, listGames, getGame, listPlayers, getPlayer)
