package dicechess.analytics.api

import java.time.LocalDate
import java.util.UUID

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import Protocol.*
import IngestProtocol.*

/** Typed endpoint definitions (the REST contract, kept identical to the FastAPI app). */
object Endpoints:

  private val notFound =
    statusCode(StatusCode.NotFound).and(jsonBody[ApiError].description("Entity not found"))

  val root: PublicEndpoint[Unit, Unit, Welcome, Any] =
    endpoint.get
      .in("")
      .out(jsonBody[Welcome])
      .description("Welcome endpoint providing basic API information")

  val version: PublicEndpoint[Unit, Unit, VersionInfo, Any] =
    endpoint.get
      .in("version")
      .out(jsonBody[VersionInfo])
      .description("Build and version information of the running API")

  val listGames: PublicEndpoint[GamesQuery, Unit, Page[GameSummary], Any] =
    endpoint.get
      .in("api" / "games")
      .in(query[Option[UUID]]("player_id").description("Filter by player UUID (white or black)"))
      .in(query[Option[Int]]("min_turns").description("Minimum number of turns in the game"))
      .in(query[Option[Int]]("max_turns").description("Maximum number of turns in the game"))
      .in(
        query[Option[String]]("mode")
          .description("Game mode: classic or x2")
          .validateOption(Validator.enumeration(List("classic", "x2")))
      )
      .in(
        query[Option[Int]]("result")
          .description("Result from White's perspective: 1 win, 0 draw, -1 loss")
          .validateOption(Validator.inRange(-1, 1))
      )
      .in(query[Option[LocalDate]]("date_from").description("Earliest start date, inclusive"))
      .in(query[Option[LocalDate]]("date_to").description("Latest start date, inclusive"))
      .in(
        query[Option[String]]("sort")
          .description("Sort field: started_at (default) or total_turns")
          .validateOption(Validator.enumeration(List("started_at", "total_turns")))
      )
      .in(
        query[Option[String]]("order")
          .description("Sort direction: desc (default) or asc")
          .validateOption(Validator.enumeration(List("asc", "desc")))
      )
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
      .mapInTo[GamesQuery]
      .out(jsonBody[Page[GameSummary]])
      .description("List, filter, sort and paginate games")

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

  val continuations: PublicEndpoint[ContinuationsQuery, Unit, PositionContinuations, Any] =
    endpoint.get
      .in("api" / "positions" / "continuations")
      .in(query[String]("fen").description("Starting position FEN (normalized server-side)"))
      .in(query[String]("dice").description("Dice roll as sorted piece letters, e.g. BPQ"))
      .in(
        query[Option[String]]("mode")
          .description("Game mode: classic or x2 (omit for all)")
          .validateOption(Validator.enumeration(List("classic", "x2")))
      )
      .in(query[Option[String]]("source").description("Filter by game source, e.g. dicechess.com"))
      .in(
        query[Option[Int]]("min_rating")
          .description("Keep only games where both players were rated at least this high")
          .validateOption(Validator.min(0))
      )
      .in(
        query[Option[Int]]("limit")
          .description("Max continuations to return, default 50")
          .validateOption(Validator.inRange(1, 200))
      )
      .mapInTo[ContinuationsQuery]
      .out(jsonBody[PositionContinuations])
      .description(
        "Continuations from a position+dice, grouped by resulting position, ranked by frequency"
      )

  val positionEquity: PublicEndpoint[PositionEquityQuery, Unit, PositionEquity, Any] =
    endpoint.get
      .in("api" / "positions" / "equity")
      .in(query[String]("fen").description("Position FEN (normalized server-side)"))
      .in(
        query[Option[String]]("mode")
          .description("Game mode: classic or x2 (omit for all)")
          .validateOption(Validator.enumeration(List("classic", "x2")))
      )
      .in(query[Option[String]]("source").description("Filter by game source, e.g. dicechess.com"))
      .in(
        query[Option[Int]]("min_rating")
          .description("Keep only games where both players were rated at least this high")
          .validateOption(Validator.min(0))
      )
      .in(
        query[Option[Int]]("min_decided")
          .description(
            "Below this many decided games, fall back to a Monte-Carlo estimate (source=mc). Default 30; 0 disables the fallback."
          )
          .validateOption(Validator.min(0))
      )
      .mapInTo[PositionEquityQuery]
      .out(jsonBody[PositionEquity])
      .description(
        "Pre-roll equity of a position: win probability for the side to move across all rolls (Monte-Carlo fallback when the sample is sparse)"
      )

  /** Ingest a completed, engine-validated game. Bearer-authenticated (the write path).
    *
    * The status code is set at runtime on both channels: `201`/`200` on success (created vs
    * already-existing), `401`/`422` on error (bad token / invalid game).
    */
  val ingestGame
      : Endpoint[String, GameIngest, (StatusCode, ApiError), (StatusCode, IngestResult), Any] =
    endpoint.post
      .securityIn(auth.bearer[String]())
      .in("api" / "games")
      .in(jsonBody[GameIngest])
      .out(statusCode.and(jsonBody[IngestResult]))
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .description("Ingest a completed, engine-validated game (bearer auth required)")

  val all: List[AnyEndpoint] =
    List(
      root,
      version,
      listGames,
      getGame,
      listPlayers,
      getPlayer,
      continuations,
      positionEquity,
      ingestGame
    )
