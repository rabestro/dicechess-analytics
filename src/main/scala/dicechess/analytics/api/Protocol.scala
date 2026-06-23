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

  /** Query parameters of the games list endpoint (mapped from the Tapir inputs). `color`,
    * `opponentType` and `opponentId` are interpreted relative to `playerId` (the focal player);
    * `stake` is a tier bucket over `initial_stake_amount` (the pot).
    */
  final case class GamesQuery(
      playerId: Option[UUID],
      minTurns: Option[Int],
      maxTurns: Option[Int],
      mode: Option[String],
      result: Option[Int],
      color: Option[String],
      opponentType: Option[String],
      opponentId: Option[UUID],
      stake: Option[String],
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

  /** Query parameters of the player-stats endpoint (mapped from the Tapir inputs). The path
    * `playerId` is the first input; the rest are optional filters from this player's perspective:
    * `color` is the focal player's side (`w`/`b`), `opponentType` (`human`/`bot`) and `opponentId`
    * constrain the other side, and `stake` is a tier bucket over `initial_stake_amount` (the pot).
    * Identity and per-mode current ratings are NOT affected by these filters.
    */
  final case class PlayerStatsQuery(
      playerId: UUID,
      mode: Option[String],
      color: Option[String],
      opponentType: Option[String],
      opponentId: Option[UUID],
      stake: Option[String],
      dateFrom: Option[LocalDate],
      dateTo: Option[LocalDate]
  )

  /** Aggregate statistics for a single player across all of their games.
    *
    * Outcomes are from the player's perspective, combining `games.result` (White-perspective: `1`
    * White win, `-1` Black win, `0` draw, `NULL` undecided) with the colour the player had in each
    * game. `games` counts every game played, including undecided ones; `decided = wins + draws +
    * losses` is the win-rate denominator. `winRate = (wins + 0.5*draws)/decided` — draws weighted
    * as half a win, the same convention as [[PositionEquity]] — and is `0.0` when nothing is
    * decided.
    *
    * `ratingClassic` / `ratingX2` are the rating snapshots from the player's most recent game in
    * each mode (a player carries an independent rating per mode); either is `None` when the player
    * has no game in that mode.
    */
  final case class PlayerStats(
      id: UUID,
      username: Option[String],
      playerType: String,
      games: Int,
      wins: Int,
      draws: Int,
      losses: Int,
      decided: Int,
      winRate: Double,
      asWhite: Int,
      asBlack: Int,
      firstGame: Option[OffsetDateTime],
      lastGame: Option[OffsetDateTime],
      ratingClassic: Option[Int],
      ratingX2: Option[Int]
  ) derives ConfiguredCodec,
        Schema

  /** One row of a win/loss/draw breakdown (a single colour, mode, or opponent type). Outcomes are
    * from the player's perspective; `winRate = (wins + 0.5*draws)/decided`, `0.0` when nothing is
    * decided. `games` includes undecided games.
    */
  final case class BreakdownRow(
      key: String,
      games: Int,
      wins: Int,
      draws: Int,
      losses: Int,
      winRate: Double
  ) derives ConfiguredCodec,
        Schema

  /** Accept/decline tally for x2 cube offers (one side of the doubling stats). */
  final case class DoublingOutcome(accepted: Int, declined: Int) derives ConfiguredCodec, Schema

  /** x2 doubling-cube offers from the player's perspective, split by who made the offer. */
  final case class Doubling(
      playerOffered: DoublingOutcome,
      opponentOffered: DoublingOutcome
  ) derives ConfiguredCodec,
        Schema

  /** Win-rate breakdowns for a player across categorical dimensions, honouring the same filters as
    * the stats endpoint. `byTimeControl` rows are keyed `initSec:incSec` (the UI formats them);
    * `avgTurns` is the mean `total_turns` over the filtered slice (`None` when no game matches);
    * `doubling` tallies x2 cube offers by who offered.
    */
  final case class PlayerBreakdowns(
      byColor: List[BreakdownRow],
      byMode: List[BreakdownRow],
      byOpponentType: List[BreakdownRow],
      byTimeControl: List[BreakdownRow],
      avgTurns: Option[Double],
      doubling: Doubling
  ) derives ConfiguredCodec,
        Schema

  /** Query parameters of the profit-history endpoint. Profit is cross-mode (a single currency
    * denomination), so only the date range applies — mode, colour, opponent and stake do not shape
    * a cumulative profit curve.
    */
  final case class ProfitHistoryQuery(
      playerId: UUID,
      dateFrom: Option[LocalDate],
      dateTo: Option[LocalDate]
  )

  /** One day of a player's cumulative profit trajectory. `delta` is the net profit for that day
    * (sum of all paid-game `money_delta` values from the player's perspective); `cumulative` is the
    * running total up to and including this day. Free games and beturanga.com games contribute a
    * `NULL` `money_delta` and are therefore excluded naturally.
    */
  final case class ProfitPoint(date: LocalDate, delta: BigDecimal, cumulative: BigDecimal)
      derives ConfiguredCodec,
        Schema

  /** A player's profit trajectory as a daily cumulative series (`GET
    * /api/players/{id}/profit-history`). Only days with at least one paid game (non-null
    * `money_delta`) appear. Empty when the player has no paid games.
    */
  final case class ProfitHistory(points: List[ProfitPoint]) derives ConfiguredCodec, Schema

  /** Query parameters of the rating-history endpoint. Rating is a per-mode, point-in-time property,
    * so only `mode` and the date range apply (colour / opponent / stake do not shape a rating
    * curve).
    */
  final case class RatingHistoryQuery(
      playerId: UUID,
      mode: Option[String],
      dateFrom: Option[LocalDate],
      dateTo: Option[LocalDate]
  )

  /** One point of a rating-over-time series: the player's rating after the last game of `date`. */
  final case class RatingPoint(date: LocalDate, rating: Int) derives ConfiguredCodec, Schema

  /** A player's rating trajectory as one daily point per mode. A series is empty when the player
    * has no rated game in that mode (or the mode was filtered out).
    */
  final case class RatingHistory(
      classic: List[RatingPoint],
      x2: List[RatingPoint]
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

  /** Query parameters of the position-continuations endpoint (mapped from Tapir inputs). `source`
    * and `minRating` are session filters: `minRating` keeps only games where *both* players were
    * rated at least that high (a strong game), excluding unrated games.
    */
  final case class ContinuationsQuery(
      fen: String,
      dice: String,
      mode: Option[String],
      source: Option[String],
      minRating: Option[Int],
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

  /** Query parameters of the position-equity endpoint (mapped from Tapir inputs). `source` and
    * `minRating` are the same session filters as on continuations, so the equity reflects the same
    * game set (`minRating` keeps only games where both players were rated at least that high).
    */
  final case class PositionEquityQuery(
      fen: String,
      mode: Option[String],
      source: Option[String],
      minRating: Option[Int]
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

  // The dice-distribution endpoint takes the same query parameters as equity, so it reuses
  // `PositionEquityQuery` rather than declaring an identical case class.

  /** How often one dice roll was played from a position, with the moving side's outcome. `dice` is
    * the sorted piece letters in upper case (e.g. BPQ), regardless of side to move;
    * `winRate = (wins + 0.5*draws)/decided`.
    */
  final case class DiceDistributionRow(
      dice: String,
      games: Int,
      wins: Int,
      draws: Int,
      losses: Int,
      winRate: Double
  ) derives ConfiguredCodec,
        Schema

  /** Every dice roll played from a position, ranked by frequency. The per-roll win rates are the
    * same numbers the continuations endpoint aggregates, but exposed for all rolls at once (at most
    * 56 rows) so a client need not fan out 56 continuation requests. `totalGames` is the sum over
    * rows (= the equity endpoint's `games` for the same filters).
    */
  final case class PositionDiceDistribution(
      fen: String,
      sideToMove: String,
      totalGames: Int,
      items: List[DiceDistributionRow]
  ) derives ConfiguredCodec,
        Schema
