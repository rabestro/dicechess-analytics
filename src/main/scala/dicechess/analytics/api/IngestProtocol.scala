package dicechess.analytics.api

import java.time.OffsetDateTime
import java.util.UUID

import io.circe.Json
import io.circe.derivation.ConfiguredCodec
import sttp.tapir.Schema

import Protocol.given

/** Request contract for `POST /api/games`: a completed, source-agnostic game submitted by a trusted
  * writer. snake_case on the wire (shares [[Protocol]]'s codec configuration).
  */
object IngestProtocol:

  /** A player as seen by the writer; resolved to a `players` row by `external_id`. */
  final case class PlayerInput(
      externalId: String,
      username: Option[String],
      playerType: Option[String],
      rating: Option[Int]
  ) derives ConfiguredCodec,
        Schema

  /** One turn: the rolled dice (1 = pawn … 6 = king) and the UCI micro-moves played. */
  final case class TurnInputDto(
      turnNumber: Int,
      activeColor: String,
      dice: List[Int],
      moves: List[String],
      thinkingTimeMs: Option[Int],
      fenAfter: Option[String]
  ) derives ConfiguredCodec,
        Schema

  /** A non-move event (doubling, draw offer). */
  final case class GameEventInput(
      sequenceNumber: Int,
      turnNumber: Option[Int],
      eventType: String,
      actorColor: Option[String],
      clockWhiteMs: Option[Int],
      clockBlackMs: Option[Int],
      payload: Option[Json]
  ) derives ConfiguredCodec,
        Schema

  /** A complete game to ingest. `id` is the source's game UUID and the primary key (idempotency).
    */
  final case class GameIngest(
      id: UUID,
      source: String,
      mode: String,
      result: Option[Int],
      termination: Option[String],
      startedAt: Option[OffsetDateTime],
      timeInitialSec: Option[Int],
      timeIncrementSec: Option[Int],
      initialStakeAmount: Option[Int],
      finalStakeAmount: Option[Int],
      whiteMoneyDelta: Option[BigDecimal],
      blackMoneyDelta: Option[BigDecimal],
      stakeCurrency: Option[String],
      whitePlayer: Option[PlayerInput],
      blackPlayer: Option[PlayerInput],
      initialFen: String,
      turns: List[TurnInputDto],
      events: List[GameEventInput]
  ) derives ConfiguredCodec,
        Schema
