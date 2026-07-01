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

  private val fenQueryDescription = "Position FEN (normalized server-side)"
  private val openingBookSegment  = "opening-book"

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

  val listGames: PublicEndpoint[GamesQuery, ApiError, Page[GameSummary], Any] =
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
      .in(
        query[Option[String]]("color")
          .description("Focal player's colour: w or b (requires player_id)")
          .validateOption(Validator.enumeration(List("w", "b")))
      )
      .in(
        query[Option[String]]("opponent_type")
          .description("Opponent type: human or bot (relative to player_id)")
          .validateOption(Validator.enumeration(List("human", "bot")))
      )
      .in(
        query[Option[UUID]]("opponent_id")
          .description("Filter by a specific opponent (relative to player_id)")
      )
      .in(
        query[Option[String]]("stake")
          .description("Stake tier on the pot: free, low, medium or high")
          .validateOption(Validator.enumeration(List("free", "low", "medium", "high")))
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
      .errorOut(statusCode(StatusCode.BadRequest).and(jsonBody[ApiError]))
      .out(jsonBody[Page[GameSummary]])
      .description("List, filter, sort and paginate games (color requires player_id)")

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

  /** The optional filter set shared by the player stats and breakdowns endpoints. Combined with the
    * path `player_id` it maps into a `PlayerStatsQuery`.
    */
  private val statsFilters =
    query[Option[String]]("mode")
      .description("Game mode: classic or x2")
      .validateOption(Validator.enumeration(List("classic", "x2")))
      .and(
        query[Option[String]]("color")
          .description("Focal player's colour: w or b")
          .validateOption(Validator.enumeration(List("w", "b")))
      )
      .and(
        query[Option[String]]("opponent_type")
          .description("Opponent type: human or bot")
          .validateOption(Validator.enumeration(List("human", "bot")))
      )
      .and(query[Option[UUID]]("opponent_id").description("Filter by a specific opponent"))
      .and(
        query[Option[String]]("stake")
          .description("Stake tier on the pot: free, low, medium or high")
          .validateOption(Validator.enumeration(List("free", "low", "medium", "high")))
      )
      .and(query[Option[LocalDate]]("date_from").description("Earliest start date, inclusive"))
      .and(query[Option[LocalDate]]("date_to").description("Latest start date, inclusive"))

  val playerStats: PublicEndpoint[PlayerStatsQuery, ApiError, PlayerStats, Any] =
    endpoint.get
      .in("api" / "players" / path[UUID]("player_id") / "stats")
      .in(statsFilters)
      .mapInTo[PlayerStatsQuery]
      .out(jsonBody[PlayerStats])
      .errorOut(notFound)
      .description(
        "Aggregate win/loss/draw statistics for a player, optionally filtered by mode, colour, opponent, stake and date"
      )

  val breakdowns: PublicEndpoint[PlayerStatsQuery, ApiError, PlayerBreakdowns, Any] =
    endpoint.get
      .in("api" / "players" / path[UUID]("player_id") / "breakdowns")
      .in(statsFilters)
      .mapInTo[PlayerStatsQuery]
      .out(jsonBody[PlayerBreakdowns])
      .errorOut(notFound)
      .description(
        "Win-rate breakdowns by colour, mode and opponent type (plus average moves) for a player, same filters as stats"
      )

  val profitHistory: PublicEndpoint[ProfitHistoryQuery, ApiError, ProfitHistory, Any] =
    endpoint.get
      .in("api" / "players" / path[UUID]("player_id") / "profit-history")
      .in(query[Option[LocalDate]]("date_from").description("Earliest start date, inclusive"))
      .in(query[Option[LocalDate]]("date_to").description("Latest start date, inclusive"))
      .mapInTo[ProfitHistoryQuery]
      .out(jsonBody[ProfitHistory])
      .errorOut(notFound)
      .description(
        "Daily cumulative profit across all paid games; free and beturanga games are excluded automatically"
      )

  val ratingHistory: PublicEndpoint[RatingHistoryQuery, ApiError, RatingHistory, Any] =
    endpoint.get
      .in("api" / "players" / path[UUID]("player_id") / "rating-history")
      .in(
        query[Option[String]]("mode")
          .description("Game mode: classic or x2 (omit for both)")
          .validateOption(Validator.enumeration(List("classic", "x2")))
      )
      .in(query[Option[LocalDate]]("date_from").description("Earliest start date, inclusive"))
      .in(query[Option[LocalDate]]("date_to").description("Latest start date, inclusive"))
      .mapInTo[RatingHistoryQuery]
      .out(jsonBody[RatingHistory])
      .errorOut(notFound)
      .description(
        "Per-mode rating over time (one point per active day), filtered by mode and date"
      )

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
      .in(query[String]("fen").description(fenQueryDescription))
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
      .mapInTo[PositionEquityQuery]
      .out(jsonBody[PositionEquity])
      .description(
        "Pre-roll equity of a position: win probability for the side to move across all rolls"
      )

  val diceDistribution: PublicEndpoint[PositionEquityQuery, Unit, PositionDiceDistribution, Any] =
    endpoint.get
      .in("api" / "positions" / "dice-distribution")
      .in(query[String]("fen").description(fenQueryDescription))
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
      .mapInTo[PositionEquityQuery]
      .out(jsonBody[PositionDiceDistribution])
      .description(
        "Every dice roll played from a position (up to 56), each with games and win rate"
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

  /** Replace a previously-ingested game with a re-validated version (the re-conversion path).
    *
    * Unlike `POST /api/games` (create-only, idempotent), this deletes any existing game — cascading
    * its turns and events — and re-inserts from the supplied payload, so a corrected normalization
    * overwrites the old one. `201` when the game did not exist, `200` when it was replaced;
    * `401`/`422` on bad token / invalid game, `400` when the path id ≠ body id.
    */
  val replaceGame: Endpoint[
    String,
    (UUID, GameIngest),
    (StatusCode, ApiError),
    (StatusCode, IngestResult),
    Any
  ] =
    endpoint.put
      .securityIn(auth.bearer[String]())
      .in("api" / "games" / path[UUID]("game_id"))
      .in(jsonBody[GameIngest])
      .out(statusCode.and(jsonBody[IngestResult]))
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .description(
        "Replace a previously-ingested game with a re-validated version (bearer auth required)"
      )

  /** List curated opening-book favorites for a position. Public (read-only). */
  val getFavorites: PublicEndpoint[String, Unit, PositionFavorites, Any] =
    endpoint.get
      .in("api" / openingBookSegment / "favorites")
      .in(query[String]("fen").description(fenQueryDescription))
      .out(jsonBody[PositionFavorites])
      .description("Curated opening-book favorites for a position")

  /** Create or update a curated favorite. Bearer-authenticated (`CURATION_TOKEN`). */
  val putFavorite: Endpoint[String, FavoriteInput, (StatusCode, ApiError), FavoriteEntry, Any] =
    endpoint.put
      .securityIn(auth.bearer[String]())
      .in("api" / openingBookSegment / "favorites")
      .in(jsonBody[FavoriteInput])
      .out(jsonBody[FavoriteEntry])
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .description("Create or update a curated opening-book favorite (bearer auth required)")

  /** Delete a curated favorite. Bearer-authenticated (`CURATION_TOKEN`). */
  val deleteFavorite: Endpoint[String, FavoriteKeyInput, (StatusCode, ApiError), Unit, Any] =
    endpoint.delete
      .securityIn(auth.bearer[String]())
      .in("api" / openingBookSegment / "favorites")
      .in(jsonBody[FavoriteKeyInput])
      .out(statusCode(StatusCode.NoContent))
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .description("Delete a curated opening-book favorite (bearer auth required)")

  val listUsers: PublicEndpoint[Option[String], Unit, List[UserResponse], Any] =
    endpoint.get
      .in("api" / "admin" / "users")
      .in(
        query[Option[String]]("status")
          .validateOption(Validator.enumeration(List("pending", "approved", "blocked", "admins")))
          .description("Filter users by status: pending, approved, blocked, admins")
      )
      .out(jsonBody[List[UserResponse]])
      .description("List all users (Admin only)")

  val updateUser: PublicEndpoint[(UUID, AdminUserUpdateRequest), ApiError, MessageResponse, Any] =
    endpoint.patch
      .in("api" / "admin" / "users" / path[UUID]("user_id"))
      .in(jsonBody[AdminUserUpdateRequest])
      .out(jsonBody[MessageResponse])
      .errorOut(notFound)
      .description("Update user approval, role or active state (Admin only)")

  val deleteUser: PublicEndpoint[UUID, ApiError, MessageResponse, Any] =
    endpoint.delete
      .in("api" / "admin" / "users" / path[UUID]("user_id"))
      .out(jsonBody[MessageResponse])
      .errorOut(notFound)
      .description("Delete a user (Admin only)")

  val all: List[AnyEndpoint] =
    List(
      root,
      version,
      listGames,
      getGame,
      listPlayers,
      getPlayer,
      playerStats,
      breakdowns,
      profitHistory,
      ratingHistory,
      continuations,
      positionEquity,
      diceDistribution,
      ingestGame,
      replaceGame,
      getFavorites,
      putFavorite,
      deleteFavorite,
      listUsers,
      updateUser,
      deleteUser
    )
