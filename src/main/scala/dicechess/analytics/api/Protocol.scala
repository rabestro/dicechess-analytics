package dicechess.analytics.api

import java.time.OffsetDateTime
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
