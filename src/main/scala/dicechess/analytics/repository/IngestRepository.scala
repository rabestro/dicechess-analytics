package dicechess.analytics.repository

import java.util.UUID

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.circe.jsonb.implicits.given
import doobie.postgres.implicits.*

import dicechess.analytics.api.IngestProtocol.*
import dicechess.analytics.ingest.{ReplayedGame, ReplayedTurn}

/** Persists a validated, engine-replayed game in a single transaction.
  *
  * `request` carries the metadata; `replayed` carries the engine-derived before/after position FEN
  * for each turn (the two turn lists correspond 1:1). Idempotent on the game id: re-ingesting the
  * same game is a no-op. enum columns are written via explicit casts.
  */
object IngestRepository:

  /** Persists the game; returns whether it was newly created (`false` if it already existed). */
  def persist(request: GameIngest, replayed: ReplayedGame): ConnectionIO[Boolean] =
    sql"SELECT 1 FROM games WHERE id = ${request.id}".query[Int].option.flatMap {
      case Some(_) => false.pure[ConnectionIO]
      case None    => insertAll(request, replayed)
    }

  /** Replaces the game with a re-validated version: deletes any existing row (cascading its turns
    * and events) and re-inserts from `request`. Shared positions and players are left intact.
    * Returns `true` if the game did not exist before (created), `false` if it was replaced. Run
    * inside a transaction so the delete rolls back if the re-insert fails.
    */
  def persistReplace(request: GameIngest, replayed: ReplayedGame): ConnectionIO[Boolean] =
    for
      existed  <- sql"DELETE FROM games WHERE id = ${request.id}".update.run.map(_ > 0)
      inserted <- insertAll(request, replayed)
      // insertAll uses ON CONFLICT DO NOTHING; if a concurrent request re-inserted the game
      // between our DELETE and INSERT it would skip turns/events. Fail loudly so the whole
      // transaction (including the DELETE) rolls back rather than silently losing data.
      _ <-
        if inserted then ().pure[ConnectionIO]
        else
          new IllegalStateException(
            s"replace of game ${request.id} inserted nothing (concurrent ingest?) — rolling back"
          ).raiseError[ConnectionIO, Unit]
    yield !existed

  private def insertAll(request: GameIngest, replayed: ReplayedGame): ConnectionIO[Boolean] =
    if request.turns.sizeIs != replayed.turns.size then
      new IllegalArgumentException(
        s"turns/replayed mismatch: ${request.turns.size} request turns vs ${replayed.turns.size} replayed"
      ).raiseError[ConnectionIO, Boolean]
    else
      for
        whiteId    <- request.whitePlayer.traverse(upsertPlayer)
        blackId    <- request.blackPlayer.traverse(upsertPlayer)
        initialPos <- PositionsRepository.getOrCreate(replayed.initialFen)
        finalPos   <- PositionsRepository.getOrCreate(replayed.finalFen)
        // ON CONFLICT DO NOTHING returns 0 if a concurrent request already inserted this game;
        // in that case its turns/events are (being) written by that request — skip ours.
        inserted <- insertGame(request, whiteId, blackId, initialPos, finalPos, replayed.turns.size)
        _        <-
          if inserted == 0 then ().pure[ConnectionIO]
          else
            request.turns
              .zip(replayed.turns)
              .traverse_((dto, rt) => insertTurn(request.id, dto, rt)) *>
              request.events.traverse_(event => insertEvent(request.id, event))
      yield inserted > 0

  private def upsertPlayer(player: PlayerInput): ConnectionIO[UUID] =
    sql"""INSERT INTO players (id, external_id, username, player_type)
          VALUES (${UUID.randomUUID()}, ${player.externalId}, ${player.username},
                  ${player.playerType.getOrElse("human")})
          ON CONFLICT (external_id)
          DO UPDATE SET username = COALESCE(EXCLUDED.username, players.username)
          RETURNING id""".query[UUID].unique

  private def insertGame(
      request: GameIngest,
      whiteId: Option[UUID],
      blackId: Option[UUID],
      initialPos: Long,
      finalPos: Long,
      totalTurns: Int
  ): ConnectionIO[Int] =
    sql"""INSERT INTO games
            (id, source, white_player_id, black_player_id, white_rating, black_rating,
             mode, result, termination, initial_position_id, final_position_id, total_turns,
             time_initial_sec, time_increment_sec, initial_stake_amount, final_stake_amount,
             white_money_delta, black_money_delta, stake_currency, started_at)
          VALUES
            (${request.id}, ${request.source}, $whiteId, $blackId,
             ${request.whitePlayer.flatMap(_.rating)}, ${request.blackPlayer.flatMap(_.rating)},
             ${request.mode}::game_mode_enum, ${request.result},
             ${request.termination.getOrElse("unknown")}::game_termination_enum,
             $initialPos, $finalPos, $totalTurns,
             ${request.timeInitialSec}, ${request.timeIncrementSec},
             ${request.initialStakeAmount}, ${request.finalStakeAmount},
             ${request.whiteMoneyDelta}, ${request.blackMoneyDelta}, ${request.stakeCurrency},
             ${request.startedAt})
          ON CONFLICT (id) DO NOTHING""".update.run

  private def insertTurn(
      gameId: UUID,
      dto: TurnInputDto,
      replayed: ReplayedTurn
  ): ConnectionIO[Long] =
    val pieces     = Array('p', 'n', 'b', 'r', 'q', 'k')
    val letters    = dto.dice.map(d => pieces(d - 1)).sorted.mkString
    val diceSorted =
      if dto.activeColor == "w" then letters.toUpperCase else letters

    for
      before <- PositionsRepository.getOrCreate(replayed.beforeFen)
      after  <- PositionsRepository.getOrCreate(replayed.afterFen)
      id     <- sql"""INSERT INTO turns
                        (game_id, turn_number, active_color, position_id, dice_sorted,
                         played_moves, position_after_id, thinking_time_ms)
                      VALUES
                        ($gameId, ${dto.turnNumber}, ${dto.activeColor}, $before,
                         $diceSorted, ${dto.moves}, $after, ${dto.thinkingTimeMs})
                      RETURNING id""".query[Long].unique
    yield id

  private def insertEvent(gameId: UUID, event: GameEventInput): ConnectionIO[Int] =
    sql"""INSERT INTO game_events
            (game_id, sequence_number, turn_number, event_type,
             actor_color, clock_white_ms, clock_black_ms, payload)
          VALUES
            ($gameId, ${event.sequenceNumber}, ${event.turnNumber},
             ${event.eventType}::game_event_type_enum, ${event.actorColor},
             ${event.clockWhiteMs}, ${event.clockBlackMs}, ${event.payload})""".update.run
