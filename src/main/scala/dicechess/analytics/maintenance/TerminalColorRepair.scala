package dicechess.analytics.maintenance

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

import dicechess.analytics.Fen

/** One-shot repair for the 2026-06-10 legacy backfill (issue #161).
  *
  * That bulk import stored each game's terminal FEN verbatim instead of re-deriving it through the
  * engine, so after a king capture the side-to-move was never flipped — the resulting position kept
  * the mover's colour — and the game was never classified (`termination = 'unknown'`). Because
  * `normalized_fen` includes the active colour and is the dedup/grouping key, the same terminal
  * position exists under both colours, which splits one continuation into two rows in the Openings
  * Explorer.
  *
  * The repair flips those positions to the colour-flipped twin — obtained via the same insert path
  * as [[dicechess.analytics.repository.PositionsRepository.getOrCreate]] so the engine computes the
  * correct `fen_hash` — re-points the legacy turns and the games' `final_position_id` to the twin,
  * reclassifies the affected games as `king_captured`, and deletes the now-orphaned legacy
  * positions. It is idempotent: a second run finds nothing left to fix.
  */
object TerminalColorRepair:

  /** What the repair changed, for operator logging. */
  final case class RepairReport(
      turnsRepointed: Int,
      finalsRepointed: Int,
      gamesReclassified: Int,
      legacyPositionsDeleted: Int
  )

  /** A legacy not-flipped terminal position: the side to move equals the side that just captured,
    * so the *other* king is absent. Black captured -> white king gone, still 'b'; white captured ->
    * black king gone, still 'w'.
    */
  private val legacyCond: Fragment =
    fr"""((p.active_color = 'b' AND p.piece_placement NOT LIKE '%K%')
       OR (p.active_color = 'w' AND p.piece_placement NOT LIKE '%k%'))"""

  /** The colour-flipped twin's normalized FEN, rebuilt from `p`'s stored split fields. */
  private val twinFen: Fragment =
    fr"""p.piece_placement || ' ' || (CASE p.active_color WHEN 'b' THEN 'w' ELSE 'b' END)
       || ' ' || p.castling || ' ' || p.en_passant"""

  /** Twins that must be created because no equivalent position exists yet: returns the columns the
    * `positions` row needs, with the active colour already flipped.
    */
  private val selectTwinsToCreate: ConnectionIO[List[(String, String, String, String)]] =
    sql"""SELECT p.piece_placement,
                 (CASE p.active_color WHEN 'b' THEN 'w' ELSE 'b' END) AS twin_color,
                 p.castling, p.en_passant
          FROM positions p
          WHERE $legacyCond
            AND NOT EXISTS (SELECT 1 FROM positions q WHERE q.normalized_fen = ($twinFen))
       """.query[(String, String, String, String)].to[List]

  private val insertTwin =
    Update[(String, Long, String, String, String, String)](
      """INSERT INTO positions
           (normalized_fen, fen_hash, piece_placement, active_color, castling, en_passant)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT (normalized_fen) DO NOTHING"""
    )

  private def createTwins(rows: List[(String, String, String, String)]): ConnectionIO[Int] =
    val records = rows.map { case (placement, color, castling, enPassant) =>
      val nf = s"$placement $color $castling $enPassant"
      (nf, Fen.hash(nf), placement, color, castling, enPassant)
    }
    insertTwin.updateMany(records)

  // The repaired games' winner is the side that captured = the mover = the legacy after-position's
  // (unflipped) colour. Result is from white's perspective: white mover -> 1, black mover -> -1.
  private val reclassifyGames =
    sql"""UPDATE games g
          SET termination = 'king_captured'::game_termination_enum,
              result = CASE WHEN p.active_color = 'w' THEN 1 ELSE -1 END
          FROM turns t
          JOIN positions p ON p.id = t.position_after_id
          WHERE t.game_id = g.id
            AND g.termination = 'unknown'::game_termination_enum
            AND $legacyCond""".update

  private val repointTurns =
    sql"""UPDATE turns t
          SET position_after_id = tw.id
          FROM positions p, positions tw
          WHERE t.position_after_id = p.id
            AND $legacyCond
            AND tw.normalized_fen = ($twinFen)""".update

  private val repointFinals =
    sql"""UPDATE games g
          SET final_position_id = tw.id
          FROM positions p, positions tw
          WHERE g.final_position_id = p.id
            AND $legacyCond
            AND tw.normalized_fen = ($twinFen)""".update

  // Safe even if the global pre-checks were ever violated: only deletes positions that nothing
  // still references (legacy terminal positions are never a turn's `position_id` or a game's
  // `initial_position_id`, so after re-pointing they are fully orphaned).
  private val deleteOrphans =
    sql"""DELETE FROM positions p
          WHERE $legacyCond
            AND NOT EXISTS (SELECT 1 FROM turns t
                            WHERE t.position_id = p.id OR t.position_after_id = p.id)
            AND NOT EXISTS (SELECT 1 FROM games g
                            WHERE g.initial_position_id = p.id OR g.final_position_id = p.id)
       """.update

  /** Runs the full repair in one transaction and reports what changed. */
  def run: ConnectionIO[RepairReport] =
    for
      toCreate     <- selectTwinsToCreate
      _            <- if toCreate.isEmpty then 0.pure[ConnectionIO] else createTwins(toCreate)
      reclassified <- reclassifyGames.run
      turns        <- repointTurns.run
      finals       <- repointFinals.run
      deleted      <- deleteOrphans.run
    yield RepairReport(turns, finals, reclassified, deleted)
