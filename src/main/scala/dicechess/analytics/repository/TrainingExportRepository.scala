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
  *
  * A wide filter (e.g. rating ≥ 1800, classic, king_captured) can match hundreds of thousands of
  * games, and Postgres badly underestimates that count for this column combination (a known
  * correlated-predicate limitation `ANALYZE` alone does not fix) — the resulting Nested Loop plan
  * degrades into roughly one random disk seek per output row, which stalls on spinning disks under
  * concurrent write load. [[filteredGameIds]] + [[rowsForGames]] page through the export by
  * `games.id` instead of running [[rows]] in one shot, keeping each individual query small enough
  * for Nested Loop to stay cheap and spreading the I/O over time.
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

  /** Games-level filter only, no join to turns — shared by the single-shot query below and by the
    * keyset pagination in [[filteredGameIds]].
    */
  private def gameConditions(filters: Filters): Fragment =
    val base       = fr"WHERE g.result IS NOT NULL"
    val withRating =
      filters.minRating.fold(base)(r =>
        base ++ fr"AND g.white_rating >= $r AND g.black_rating >= $r"
      )
    val withMode = filters.mode.fold(withRating)(m => withRating ++ fr"AND g.mode::text = $m")
    filters.termination.fold(withMode)(t => withMode ++ fr"AND g.termination::text = $t")

  /** Turn-level guards shared by every row query: drop no-op self-loops and abandoned partial
    * turns, keep legal passes and terminal king-captures.
    */
  private val turnGuards: Fragment =
    fr"t.position_after_id <> t.position_id AND" ++ PositionsRepository.completedTurn

  private def conditions(filters: Filters): Fragment =
    gameConditions(filters) ++ fr"AND" ++ turnGuards

  private val selectColumnsAndJoins: Fragment =
    fr"""SELECT t.game_id, t.turn_number, p.normalized_fen, t.dice_sorted, t.active_color,
                coalesce(array_to_string(t.played_moves, ' '), ''),
                g.result, g.termination::text, g.mode::text, g.source,
                g.white_rating, g.black_rating,
                wp.player_type::text, bp.player_type::text""" ++
      PositionsRepository.turnsJoin ++
      fr"""LEFT JOIN players wp ON wp.id = g.white_player_id
           LEFT JOIN players bp ON bp.id = g.black_player_id"""

  private def selectFrom(filters: Filters): Fragment =
    selectColumnsAndJoins ++ conditions(filters)

  /** Streams every exportable turn matching `filters` in one query; constant memory via a
    * server-side cursor, but the underlying join can be prohibitively slow for a wide filter — see
    * the class doc. Prefer [[filteredGameIds]] + [[rowsForGames]] for anything but a narrow,
    * already-known-small filter.
    */
  def rows(filters: Filters, chunkSize: Int = 4096): Stream[ConnectionIO, TrainingRow] =
    selectFrom(filters).query[TrainingRow].streamWithChunkSize(chunkSize)

  /** `(turns, distinct games)` matching `filters`. Shares the same join cost as [[rows]] — cheap
    * only for a filter already known to be narrow.
    */
  def counts(filters: Filters): ConnectionIO[(Long, Long)] =
    (fr"SELECT count(*), count(DISTINCT t.game_id)" ++
      PositionsRepository.turnsJoin ++
      conditions(filters)).query[(Long, Long)].unique

  /** Up to `limit` game ids matching `filters`, in ascending `id` order, strictly after `afterId`.
    * Keyset (not `OFFSET`) pagination: every call is an indexed range scan of `games` alone, so
    * cost stays flat no matter how far through the result set the caller already is. `None` starts
    * from the beginning; an empty result means every matching game has been returned.
    */
  def filteredGameIds(
      filters: Filters,
      afterId: Option[UUID],
      limit: Int
  ): ConnectionIO[List[UUID]] =
    val withCursor =
      afterId.fold(gameConditions(filters))(id => gameConditions(filters) ++ fr"AND g.id > $id")
    (fr"SELECT g.id FROM games g" ++ withCursor ++ fr"ORDER BY g.id LIMIT $limit")
      .query[UUID]
      .to[List]

  /** Rows for a specific, already-filtered batch of games (from [[filteredGameIds]]) — no
    * games-level filter is re-applied, only the turn-level guards. At batch scale (a few thousand
    * games) Nested Loop over indexed joins is cheap, which is the entire point of pagination.
    */
  def rowsForGames(gameIds: List[UUID], chunkSize: Int = 4096): Stream[ConnectionIO, TrainingRow] =
    if gameIds.isEmpty then Stream.empty
    else
      (selectColumnsAndJoins ++ fr"WHERE t.game_id = ANY($gameIds) AND" ++ turnGuards)
        .query[TrainingRow]
        .streamWithChunkSize(chunkSize)
