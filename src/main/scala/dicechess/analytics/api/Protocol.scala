package dicechess.analytics.api

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

import io.circe.Json
import io.circe.derivation.{Configuration as CirceConfiguration, ConfiguredCodec}
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration as TapirConfiguration

/** JSON protocol of the API.
  *
  * Field names are serialized in snake_case to match the FastAPI/Pydantic contract that the UI
  * already consumes.
  */
object Protocol:

  given CirceConfiguration = CirceConfiguration.default.withSnakeCaseMemberNames
  given TapirConfiguration = TapirConfiguration.default.withSnakeCaseMemberNames
  given Schema[Json]       = Schema.any[Json]

  /** Generic pagination envelope returned by list endpoints. `total` is the count of rows matching
    * the current filters (independent of `limit`/`offset`), so the client can render "found N ·
    * page X of Y". Reused across all list endpoints.
    */
  final case class Page[T](
      items: List[T],
      total: Long,
      limit: Int,
      offset: Int
  ) derives ConfiguredCodec,
        Schema

  /** Query parameters of the games list endpoint (mapped from the Tapir inputs). */
  final case class GamesQuery(
      playerId: Option[UUID],
      minTurns: Option[Int],
      maxTurns: Option[Int],
      mode: Option[String],
      result: Option[Int],
      dateFrom: Option[LocalDate],
      dateTo: Option[LocalDate],
      sort: Option[String],
      order: Option[String],
      limit: Option[Int],
      offset: Option[Int]
  )

  /** Mirror of Pydantic `PlayerBase`. `rating_classic` is the rating snapshot taken from the
    * player's most recent game (fixes the Python bug where the field never existed on the model).
    */
  final case class PlayerSummary(
      id: UUID,
      username: Option[String],
      playerType: String,
      ratingClassic: Option[Int]
  ) derives ConfiguredCodec,
        Schema

  /** Mirror of Pydantic `GameBase`. */
  final case class GameSummary(
      id: UUID,
      source: String,
      mode: String,
      result: Option[Int],
      termination: String,
      totalTurns: Option[Int],
      startedAt: Option[OffsetDateTime],
      whiteRating: Option[Int],
      blackRating: Option[Int],
      timeInitialSec: Option[Int],
      timeIncrementSec: Option[Int],
      initialStakeAmount: Option[Int],
      finalStakeAmount: Option[Int],
      whiteMoneyDelta: Option[BigDecimal],
      blackMoneyDelta: Option[BigDecimal],
      stakeCurrency: Option[String],
      whitePlayer: Option[PlayerSummary],
      blackPlayer: Option[PlayerSummary]
  ) derives ConfiguredCodec,
        Schema

  /** Mirror of Pydantic `TurnBase`. */
  final case class TurnView(
      turnNumber: Int,
      activeColor: String,
      diceSorted: String,
      playedMoves: Option[List[String]],
      thinkingTimeMs: Option[Int],
      positionFen: Option[String],
      positionAfterFen: Option[String]
  ) derives ConfiguredCodec,
        Schema

  /** Mirror of Pydantic `GameEventBase`. */
  final case class GameEventView(
      id: Long,
      sequenceNumber: Int,
      turnNumber: Option[Int],
      eventType: String,
      actorColor: Option[String],
      clockWhiteMs: Option[Int],
      clockBlackMs: Option[Int],
      payload: Option[Json]
  ) derives ConfiguredCodec,
        Schema

  /** Mirror of Pydantic `GameDetail`. */
  final case class GameDetail(
      id: UUID,
      source: String,
      mode: String,
      result: Option[Int],
      termination: String,
      totalTurns: Option[Int],
      startedAt: Option[OffsetDateTime],
      whiteRating: Option[Int],
      blackRating: Option[Int],
      timeInitialSec: Option[Int],
      timeIncrementSec: Option[Int],
      initialStakeAmount: Option[Int],
      finalStakeAmount: Option[Int],
      whiteMoneyDelta: Option[BigDecimal],
      blackMoneyDelta: Option[BigDecimal],
      stakeCurrency: Option[String],
      whitePlayer: Option[PlayerSummary],
      blackPlayer: Option[PlayerSummary],
      metadataJson: Option[Json],
      turns: List[TurnView],
      events: List[GameEventView]
  ) derives ConfiguredCodec,
        Schema

  /** FastAPI-compatible error body: `{"detail": "..."}`. */
  final case class ApiError(detail: String) derives ConfiguredCodec, Schema

  final case class Welcome(message: String, docs: String) derives ConfiguredCodec, Schema

  /** Build/version information of the running API, served at `/version` for ops visibility. */
  final case class VersionInfo(name: String, version: String, scalaVersion: String)
      derives ConfiguredCodec,
        Schema

  /** Query parameters of the position-continuations endpoint (mapped from Tapir inputs). */
  final case class ContinuationsQuery(
      fen: String,
      dice: String,
      mode: Option[String],
      limit: Option[Int]
  )

  /** One way a (position, dice) was continued, identified by the resulting position. Move order is
    * irrelevant — permutations of the micro-moves collapse to the same resulting position.
    * Win/draw/loss are from the moving side's perspective; `winRate = (wins + 0.5*draws)/decided`.
    */
  final case class Continuation(
      fen: String,
      moves: List[String],
      games: Int,
      wins: Int,
      draws: Int,
      losses: Int,
      winRate: Double
  ) derives ConfiguredCodec,
        Schema

  /** Continuations for a (position, dice) pair, ranked by frequency (most played first). */
  final case class PositionContinuations(
      fen: String,
      dice: String,
      totalGames: Int,
      items: List[Continuation]
  ) derives ConfiguredCodec,
        Schema

  /** Query parameters of the position-equity endpoint (mapped from Tapir inputs). */
  final case class PositionEquityQuery(
      fen: String,
      mode: Option[String]
  )

  /** Pre-roll equity of a position: how the side to move fared across ALL rolls from here,
    * aggregated without conditioning on the dice. `winRate = (wins + 0.5*draws)/decided` — the
    * cubeless-equity-equivalent win probability a player weighs *before* rolling (and before
    * offering a double). It equals the per-roll continuation win rates averaged over all rolls,
    * each weighted by how often that roll was actually played and decided, so it stays consistent
    * with the explorer. `games` is the total matched turns (the same count semantics as
    * `continuations.totalGames`); the win rate is over the decided games `wins + draws + losses`.
    */
  final case class PositionEquity(
      fen: String,
      sideToMove: String,
      games: Int,
      wins: Int,
      draws: Int,
      losses: Int,
      winRate: Double
  ) derives ConfiguredCodec,
        Schema
