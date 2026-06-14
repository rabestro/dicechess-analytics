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

  def persist(request: GameIngest, replayed: ReplayedGame): ConnectionIO[Unit] =
    sql"SELECT 1 FROM games WHERE id = ${request.id}".query[Int].option.flatMap {
      case Some(_) => ().pure[ConnectionIO]
      case None    => insertAll(request, replayed)
    }

  private def insertAll(request: GameIngest, replayed: ReplayedGame): ConnectionIO[Unit] =
    for
      whiteId    <- request.whitePlayer.traverse(upsertPlayer)
      blackId    <- request.blackPlayer.traverse(upsertPlayer)
      initialPos <- PositionsRepository.getOrCreate(replayed.initialFen)
      finalPos   <- PositionsRepository.getOrCreate(replayed.finalFen)
      _          <- insertGame(request, whiteId, blackId, initialPos, finalPos, replayed.turns.size)
      _ <- request.turns.zip(replayed.turns).traverse_((dto, rt) => insertTurn(request.id, dto, rt))
      _ <- request.events.traverse_(event => insertEvent(request.id, event))
    yield ()

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
             ${request.startedAt})""".update.run

  private def insertTurn(
      gameId: UUID,
      dto: TurnInputDto,
      replayed: ReplayedTurn
  ): ConnectionIO[Long] =
    for
      before <- PositionsRepository.getOrCreate(replayed.beforeFen)
      after  <- PositionsRepository.getOrCreate(replayed.afterFen)
      id     <- sql"""INSERT INTO turns
                        (game_id, turn_number, active_color, position_id, dice_sorted,
                         played_moves, position_after_id, thinking_time_ms)
                      VALUES
                        ($gameId, ${dto.turnNumber}, ${dto.activeColor}, $before,
                         ${dto.dice.sorted.mkString}, ${dto.moves}, $after, ${dto.thinkingTimeMs})
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
