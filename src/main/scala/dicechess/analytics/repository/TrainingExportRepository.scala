package dicechess.analytics.repository

import java.util.UUID

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fs2.Stream

/** Bulk read of ML training rows for the EV model: one row per completed, outcome-labeled turn.
  *
  * Shares the explorer's data-quality semantics ([[PositionsRepository.completedTurn]] plus the
  * self-loop guard), so a model trained on this export sees exactly the turns the Openings Explorer
  * counts. `termination` is intentionally NOT filtered — the legacy backfill stores `unknown` for
  * all its games and would otherwise vanish from the dataset.
  */
object TrainingExportRepository:

  /** One turn with its outcome and stratification metadata.
    *
    * `result` stays in the stored White-POV encoding (1 / 0 / -1); the mover-perspective label is
    * `result` negated when `side` is `b`, which downstream tooling derives. `moves` is the UCI
    * micro-move sequence joined with spaces; an empty string is a legal forced pass.
    */
  final case class TrainingRow(
      gameId: UUID,
      turnNumber: Int,
      fen: String,
      dice: String,
      side: String,
      moves: String,
      result: Int,
      termination: String,
      mode: String,
      source: String,
      whiteRating: Option[Int],
      blackRating: Option[Int],
      whiteType: Option[String],
      blackType: Option[String]
  )

  private def conditions(minRating: Option[Int]): Fragment =
    val base =
      fr"""WHERE g.result IS NOT NULL
           AND t.position_after_id <> t.position_id
           AND""" ++ PositionsRepository.completedTurn
    minRating.fold(base)(r => base ++ fr"AND g.white_rating >= $r AND g.black_rating >= $r")

  private def selectFrom(minRating: Option[Int]): Fragment =
    fr"""SELECT t.game_id, t.turn_number, p.normalized_fen, t.dice_sorted, t.active_color,
                array_to_string(t.played_moves, ' '),
                g.result, g.termination::text, g.mode::text, g.source,
                g.white_rating, g.black_rating,
                wp.player_type::text, bp.player_type::text""" ++
      PositionsRepository.turnsJoin ++
      fr"""LEFT JOIN players wp ON wp.id = g.white_player_id
           LEFT JOIN players bp ON bp.id = g.black_player_id""" ++
      conditions(minRating)

  /** Streams every exportable turn; constant memory via a server-side cursor. */
  def rows(minRating: Option[Int], chunkSize: Int = 4096): Stream[ConnectionIO, TrainingRow] =
    selectFrom(minRating).query[TrainingRow].streamWithChunkSize(chunkSize)

  /** `(turns, distinct games)` matching the export filters — the summary printed after a run. */
  def counts(minRating: Option[Int]): ConnectionIO[(Long, Long)] =
    (fr"SELECT count(*), count(DISTINCT t.game_id)" ++
      PositionsRepository.turnsJoin ++
      conditions(minRating)).query[(Long, Long)].unique
