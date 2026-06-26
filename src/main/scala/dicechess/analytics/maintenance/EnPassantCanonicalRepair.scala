package dicechess.analytics.maintenance

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

import dicechess.analytics.Fen

/** One-shot repair for the en-passant position split (issue #203).
  *
  * `normalized_fen` historically kept the naive FEN en-passant field — a target after every pawn
  * double-push, even when no capture is possible. In Dice Chess a turn spans several micro-moves,
  * so that field is path-dependent and the same tactically-identical position was stored under many
  * en-passant variants, splitting one continuation into several rows in the Openings Explorer and
  * fragmenting the opening-book / equity samples.
  *
  * Since the engine bump to v1.6.1, [[Fen.normalize]] produces the canonical (X-FEN) form — a
  * target is kept only when the side to move can actually capture it — so new ingests are already
  * canonical. This repair fixes the history: every position whose canonical normalized FEN differs
  * from its stored one is merged into its canonical twin (created via the same hash path as
  * [[dicechess.analytics.repository.PositionsRepository.getOrCreate]] when missing), the `turns`
  * and `games` foreign keys and any `opening_book_favorites` are re-pointed, and the orphaned rows
  * are deleted.
  *
  * The work is **batched** by a keyset cursor over `positions.id`: each batch is its own
  * transaction, so locks stay short, WAL is emitted incrementally, and live ingests interleave
  * between batches — measured production scope is ~1.43M changing positions and ~1.5M turns, far
  * too large for a single transaction on the homelab node. It is idempotent and resumable: a re-run
  * (always from the start of the cursor) reprocesses the cheap, already-canonical survivors as
  * no-ops and changes nothing once the data is clean.
  *
  * Only positions with a non-empty en-passant field can change (the canonical target set is a
  * subset of the naive one), so they are the sole candidates. Re-pointing is set-based through a
  * transaction-scoped temp table and relies on the V7 foreign-key indexes to delete orphans without
  * a per-row sequential scan.
  */
object EnPassantCanonicalRepair:

  /** What the repair changed, for operator logging. */
  final case class RepairReport(
      positionsMerged: Int,
      turnsRepointed: Int,
      finalsRepointed: Int,
      initialsRepointed: Int,
      favoritesRepointed: Int,
      positionsDeleted: Int
  ):
    def +(o: RepairReport): RepairReport =
      RepairReport(
        positionsMerged + o.positionsMerged,
        turnsRepointed + o.turnsRepointed,
        finalsRepointed + o.finalsRepointed,
        initialsRepointed + o.initialsRepointed,
        favoritesRepointed + o.favoritesRepointed,
        positionsDeleted + o.positionsDeleted
      )

  object RepairReport:
    val zero: RepairReport = RepairReport(0, 0, 0, 0, 0, 0)

  /** Default batch size: positions scanned per transaction. */
  val DefaultBatchSize: Int = 10000

  private def loadCandidates(afterId: Long, batchSize: Int): ConnectionIO[List[(Long, String)]] =
    sql"""SELECT id, normalized_fen FROM positions
          WHERE en_passant <> '-' AND id > $afterId
          ORDER BY id LIMIT $batchSize""".query[(Long, String)].to[List]

  private val createTempMap: ConnectionIO[Int] =
    sql"""CREATE TEMP TABLE ep_repair_map (
            old_id       BIGINT  NOT NULL,
            old_nf       VARCHAR NOT NULL,
            canonical_nf VARCHAR NOT NULL
          ) ON COMMIT DROP""".update.run

  private val insertMap =
    Update[(Long, String, String)](
      "INSERT INTO ep_repair_map (old_id, old_nf, canonical_nf) VALUES (?, ?, ?)"
    )

  private val insertTwin =
    Update[(String, Long, String, String, String, String)](
      """INSERT INTO positions
           (normalized_fen, fen_hash, piece_placement, active_color, castling, en_passant)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT (normalized_fen) DO NOTHING"""
    )

  // Re-point each foreign key from the legacy position to its canonical twin, resolved by joining
  // the temp map's canonical FEN back to the (now guaranteed to exist) twin row.
  private val repointTurnsBefore =
    sql"""UPDATE turns t SET position_id = p.id
          FROM ep_repair_map m
          JOIN positions p ON p.normalized_fen = m.canonical_nf
          WHERE t.position_id = m.old_id""".update

  private val repointTurnsAfter =
    sql"""UPDATE turns t SET position_after_id = p.id
          FROM ep_repair_map m
          JOIN positions p ON p.normalized_fen = m.canonical_nf
          WHERE t.position_after_id = m.old_id""".update

  private val repointFinals =
    sql"""UPDATE games g SET final_position_id = p.id
          FROM ep_repair_map m
          JOIN positions p ON p.normalized_fen = m.canonical_nf
          WHERE g.final_position_id = m.old_id""".update

  private val repointInitials =
    sql"""UPDATE games g SET initial_position_id = p.id
          FROM ep_repair_map m
          JOIN positions p ON p.normalized_fen = m.canonical_nf
          WHERE g.initial_position_id = m.old_id""".update

  // Favorites are keyed by the FEN string. Skip a move that would collide with a curated favorite
  // already present for the canonical key + dice — a curator resolves those by hand.
  private val repointFavorites =
    sql"""UPDATE opening_book_favorites f SET normalized_fen = m.canonical_nf
          FROM ep_repair_map m
          WHERE f.normalized_fen = m.old_nf
            AND NOT EXISTS (SELECT 1 FROM opening_book_favorites g
                            WHERE g.normalized_fen = m.canonical_nf
                              AND g.dice_sorted = f.dice_sorted)""".update

  // After re-pointing, legacy rows are fully orphaned. The reference checks are split one-per-column
  // so PostgreSQL resolves each as its own anti-join (V7 indexes) instead of a sequential scan. The
  // last check keeps a position alive if a curated favorite still references its FEN — `repoint
  // Favorites` skips the rare key collision, and `opening_book_favorites` has no foreign key, so
  // this avoids leaving a favorite pointing at a deleted position's FEN.
  private val deleteOrphans =
    sql"""DELETE FROM positions p USING ep_repair_map m
          WHERE p.id = m.old_id
            AND NOT EXISTS (SELECT 1 FROM turns t WHERE t.position_id = p.id)
            AND NOT EXISTS (SELECT 1 FROM turns t WHERE t.position_after_id = p.id)
            AND NOT EXISTS (SELECT 1 FROM games g WHERE g.initial_position_id = p.id)
            AND NOT EXISTS (SELECT 1 FROM games g WHERE g.final_position_id = p.id)
            AND NOT EXISTS (SELECT 1 FROM opening_book_favorites f
                            WHERE f.normalized_fen = p.normalized_fen)""".update

  /** Processes one keyset window `(afterId, afterId + batchSize]` in a single transaction. Returns
    * the greatest scanned `id` (the next cursor), how many positions were scanned (0 once the
    * cursor is exhausted), and what changed in this batch.
    */
  def processBatch(afterId: Long, batchSize: Int): ConnectionIO[(Long, Int, RepairReport)] =
    loadCandidates(afterId, batchSize).flatMap { candidates =>
      if candidates.isEmpty then (afterId, 0, RepairReport.zero).pure[ConnectionIO]
      else
        val lastId  = candidates.map(_._1).max
        val scanned = candidates.size
        val changed = candidates.flatMap { (id, nf) =>
          val canonical = Fen.normalize(nf)
          Option.when(canonical != nf)((id, nf, canonical))
        }
        if changed.isEmpty then (lastId, scanned, RepairReport.zero).pure[ConnectionIO]
        else
          val twins = changed.map(_._3).distinct.map { nf =>
            val f = Fen.fields(nf)
            (nf, Fen.hash(nf), f.piecePlacement, f.activeColor, f.castling, f.enPassant)
          }
          for
            _         <- createTempMap
            _         <- insertMap.updateMany(changed)
            _         <- insertTwin.updateMany(twins)
            tBefore   <- repointTurnsBefore.run
            tAfter    <- repointTurnsAfter.run
            finals    <- repointFinals.run
            initials  <- repointInitials.run
            favorites <- repointFavorites.run
            deleted   <- deleteOrphans.run
          yield (
            lastId,
            scanned,
            RepairReport(changed.size, tBefore + tAfter, finals, initials, favorites, deleted)
          )
    }

  /** Runs the repair to completion, one transaction per batch, accumulating the report and logging
    * progress. Stops when the keyset cursor is exhausted.
    */
  def runBatched(xa: Transactor[IO], batchSize: Int = DefaultBatchSize): IO[RepairReport] =
    def loop(afterId: Long, acc: RepairReport, batchNo: Int): IO[RepairReport] =
      processBatch(afterId, batchSize).transact(xa).flatMap { case (lastId, scanned, delta) =>
        if scanned == 0 then IO.pure(acc)
        else
          val next = acc + delta
          IO.println(
            s"[ep-repair] batch $batchNo: scanned $scanned up to id=$lastId; " +
              s"merged ${next.positionsMerged}, turns ${next.turnsRepointed}, deleted ${next.positionsDeleted}"
          ) *> loop(lastId, next, batchNo + 1)
      }
    loop(0L, RepairReport.zero, 1)
