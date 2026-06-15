package dicechess.analytics.repository

import java.time.OffsetDateTime
import java.util.UUID

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.circe.jsonb.implicits.given
import doobie.postgres.implicits.*
import io.circe.Json

import dicechess.analytics.api.Protocol.*

/** Read access to the `games`, `turns` and `game_events` tables. */
object GamesRepository:

  /** Raw row of a game joined with both players; assembled into the nested API shape in Scala
    * because doobie reads flat tuples.
    *
    * Nested players carry `rating_classic = None`, matching the historical FastAPI behaviour for
    * game responses (ratings at game time are exposed separately as `white_rating` /
    * `black_rating`).
    */
  private final case class GameRow(
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
      whiteId: Option[UUID],
      whiteUsername: Option[String],
      whiteType: Option[String],
      blackId: Option[UUID],
      blackUsername: Option[String],
      blackType: Option[String]
  ):
    private def player(
        id: Option[UUID],
        username: Option[String],
        playerType: Option[String]
    ): Option[PlayerSummary] =
      for
        pid <- id
        pt  <- playerType
      yield PlayerSummary(pid, username, pt, ratingClassic = None)

    def toSummary: GameSummary =
      GameSummary(
        id = id,
        source = source,
        mode = mode,
        result = result,
        termination = termination,
        totalTurns = totalTurns,
        startedAt = startedAt,
        whiteRating = whiteRating,
        blackRating = blackRating,
        timeInitialSec = timeInitialSec,
        timeIncrementSec = timeIncrementSec,
        initialStakeAmount = initialStakeAmount,
        finalStakeAmount = finalStakeAmount,
        whiteMoneyDelta = whiteMoneyDelta,
        blackMoneyDelta = blackMoneyDelta,
        stakeCurrency = stakeCurrency,
        whitePlayer = player(whiteId, whiteUsername, whiteType),
        blackPlayer = player(blackId, blackUsername, blackType)
      )

  private val gameColumns =
    fr"""
      SELECT g.id, g.source, g.mode::text, g.result, g.termination::text, g.total_turns, g.started_at,
             g.white_rating, g.black_rating, g.time_initial_sec, g.time_increment_sec,
             g.initial_stake_amount, g.final_stake_amount,
             g.white_money_delta, g.black_money_delta, g.stake_currency,
             wp.id, wp.username, wp.player_type,
             bp.id, bp.username, bp.player_type
    """

  private val gameTables =
    fr"""
      FROM games g
      LEFT JOIN players wp ON wp.id = g.white_player_id
      LEFT JOIN players bp ON bp.id = g.black_player_id
    """

  def list(query: GamesQuery, defaultLimit: Int): ConnectionIO[Page[GameSummary]] =
    val where = Fragments.whereAndOpt(
      query.playerId.map(p => fr"(g.white_player_id = $p OR g.black_player_id = $p)"),
      query.minTurns.map(t => fr"g.total_turns >= $t"),
      query.maxTurns.map(t => fr"g.total_turns <= $t"),
      query.mode.map(m => fr"g.mode::text = $m"),
      query.result.map(r => fr"g.result = $r"),
      query.dateFrom.map(d => fr"g.started_at >= $d"),
      query.dateTo.map(d => fr"g.started_at < ($d + INTERVAL '1 day')")
    )
    val limit  = query.limit.getOrElse(defaultLimit)
    val offset = query.offset.getOrElse(0)
    // ORDER BY column and direction come from a fixed whitelist, never interpolated raw input.
    val sortColumn = query.sort match
      case Some("total_turns") => "g.total_turns"
      case _                   => "g.started_at"
    val direction = query.order match
      case Some("asc") => "ASC"
      case _           => "DESC"
    val orderBy = Fragment.const(s"ORDER BY $sortColumn $direction NULLS LAST")
    for
      total <- (fr"SELECT count(*) FROM games g" ++ where).query[Long].unique
      rows  <- (gameColumns ++ gameTables ++ where ++ orderBy ++ fr"LIMIT $limit OFFSET $offset")
        .query[GameRow]
        .to[List]
    yield Page(rows.map(_.toSummary), total, limit, offset)

  def detail(gameId: UUID): ConnectionIO[Option[GameDetail]] =
    for
      row <- (gameColumns ++ fr", g.metadata_json" ++ gameTables ++ fr"WHERE g.id = $gameId")
        .query[(GameRow, Option[Json])]
        .option
      turns  <- row.fold(List.empty[TurnView].pure[ConnectionIO])(_ => turnsOf(gameId))
      events <- row.fold(List.empty[GameEventView].pure[ConnectionIO])(_ => eventsOf(gameId))
    yield row.map { (r, metadata) =>
      val s = r.toSummary
      GameDetail(
        id = s.id,
        source = s.source,
        mode = s.mode,
        result = s.result,
        termination = s.termination,
        totalTurns = s.totalTurns,
        startedAt = s.startedAt,
        whiteRating = s.whiteRating,
        blackRating = s.blackRating,
        timeInitialSec = s.timeInitialSec,
        timeIncrementSec = s.timeIncrementSec,
        initialStakeAmount = s.initialStakeAmount,
        finalStakeAmount = s.finalStakeAmount,
        whiteMoneyDelta = s.whiteMoneyDelta,
        blackMoneyDelta = s.blackMoneyDelta,
        stakeCurrency = s.stakeCurrency,
        whitePlayer = s.whitePlayer,
        blackPlayer = s.blackPlayer,
        metadataJson = metadata,
        turns = turns,
        events = events
      )
    }

  private def turnsOf(gameId: UUID): ConnectionIO[List[TurnView]] =
    sql"""
      SELECT t.turn_number, t.active_color, t.dice_sorted, t.played_moves,
             t.thinking_time_ms, sp.normalized_fen, ep.normalized_fen
      FROM turns t
      JOIN positions sp ON sp.id = t.position_id
      LEFT JOIN positions ep ON ep.id = t.position_after_id
      WHERE t.game_id = $gameId
      ORDER BY t.turn_number
    """
      .query[TurnView]
      .to[List]

  private def eventsOf(gameId: UUID): ConnectionIO[List[GameEventView]] =
    sql"""
      SELECT e.id, e.sequence_number, e.turn_number, e.event_type::text,
             e.actor_color, e.clock_white_ms, e.clock_black_ms, e.payload
      FROM game_events e
      WHERE e.game_id = $gameId
      ORDER BY e.sequence_number
    """
      .query[GameEventView]
      .to[List]
