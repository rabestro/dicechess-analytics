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
  * counts. `termination` is NOT filtered unless [[Filters.termination]] is set — the legacy
  * backfill stores `unknown` for all its games and would otherwise vanish from an unfiltered
  * export.
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

  /** Optional, independently composable slice of the export: `minRating` requires BOTH players at
    * least that strong, `mode`/`termination` restrict to one `games.mode` / `games.termination`
    * value. `None` means unfiltered for that dimension.
    */
  final case class Filters(
      minRating: Option[Int] = None,
      mode: Option[String] = None,
      termination: Option[String] = None
  )

  private def conditions(filters: Filters): Fragment =
    val base =
      fr"""WHERE g.result IS NOT NULL
           AND t.position_after_id <> t.position_id
           AND""" ++ PositionsRepository.completedTurn
    val withRating =
      filters.minRating.fold(base)(r =>
        base ++ fr"AND g.white_rating >= $r AND g.black_rating >= $r"
      )
    val withMode = filters.mode.fold(withRating)(m => withRating ++ fr"AND g.mode::text = $m")
    filters.termination.fold(withMode)(t => withMode ++ fr"AND g.termination::text = $t")

  private def selectFrom(filters: Filters): Fragment =
    fr"""SELECT t.game_id, t.turn_number, p.normalized_fen, t.dice_sorted, t.active_color,
                coalesce(array_to_string(t.played_moves, ' '), ''),
                g.result, g.termination::text, g.mode::text, g.source,
                g.white_rating, g.black_rating,
                wp.player_type::text, bp.player_type::text""" ++
      PositionsRepository.turnsJoin ++
      fr"""LEFT JOIN players wp ON wp.id = g.white_player_id
           LEFT JOIN players bp ON bp.id = g.black_player_id""" ++
      conditions(filters)

  /** Streams every exportable turn matching `filters`; constant memory via a server-side cursor. */
  def rows(filters: Filters, chunkSize: Int = 4096): Stream[ConnectionIO, TrainingRow] =
    selectFrom(filters).query[TrainingRow].streamWithChunkSize(chunkSize)

  /** `(turns, distinct games)` matching `filters` — the summary printed after a run. */
  def counts(filters: Filters): ConnectionIO[(Long, Long)] =
    (fr"SELECT count(*), count(DISTINCT t.game_id)" ++
      PositionsRepository.turnsJoin ++
      conditions(filters)).query[(Long, Long)].unique
