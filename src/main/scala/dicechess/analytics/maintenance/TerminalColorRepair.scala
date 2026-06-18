package dicechess.analytics.maintenance

import doobie.*
import doobie.implicits.*

/** One-shot repair for the 2026-06-10 legacy backfill (issue #161).
  *
  * That bulk import stored each game's terminal FEN verbatim instead of re-deriving it through the
  * engine, so after a king capture the side-to-move was never flipped — the resulting position kept
  * the mover's colour — and the game was never classified (`termination = 'unknown'`). Because
  * `normalized_fen` includes the active colour and is the dedup/grouping key, the same terminal
  * position exists under both colours, which splits one continuation into two rows in the Openings
  * Explorer.
  *
  * The repair re-points those legacy turns (and the games' `final_position_id`) to the
  * colour-flipped twin position — obtained via
  * [[dicechess.analytics.repository.PositionsRepository.getOrCreate]] so the engine computes the
  * correct `fen_hash` — reclassifies the affected games as `king_captured`, and deletes the
  * now-orphaned legacy positions. It is idempotent: a second run finds nothing left to fix.
  */
object TerminalColorRepair:

  /** What the repair changed, for operator logging. */
  final case class RepairReport(
      turnsRepointed: Int,
      finalsRepointed: Int,
      gamesReclassified: Int,
      legacyPositionsDeleted: Int
  )

  /** Not implemented yet (issue #161): a no-op so the suspended regression test documents the
    * unfixed behaviour. The fix PR replaces this body and removes the test's `.fail`.
    */
  def run: ConnectionIO[RepairReport] =
    sql"SELECT 0".query[Int].unique.map(_ => RepairReport(0, 0, 0, 0))
