package dicechess.analytics.repository

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

import dicechess.analytics.Fen

/** Write access to the deduplicated `positions` table. */
object PositionsRepository:

  /** Returns the id of the position for `fen`, inserting it if new.
    *
    * Dedup key is `normalized_fen`. The common path (a position already seen) is a single SELECT; a
    * genuinely new position is INSERTed with `ON CONFLICT` so that two concurrent inserts of the
    * same FEN resolve to one row and both still return its id.
    */
  def getOrCreate(fen: String): ConnectionIO[Long] =
    val nf = Fen.normalize(fen)
    sql"SELECT id FROM positions WHERE normalized_fen = $nf".query[Long].option.flatMap {
      case Some(id) => id.pure[ConnectionIO]
      case None     =>
        val f = Fen.fields(nf)
        sql"""INSERT INTO positions (normalized_fen, fen_hash, piece_placement,
                                     active_color, castling, en_passant)
              VALUES ($nf, ${Fen.hash(nf)}, ${f.piecePlacement},
                      ${f.activeColor}, ${f.castling}, ${f.enPassant})
              ON CONFLICT (normalized_fen) DO UPDATE SET normalized_fen = EXCLUDED.normalized_fen
              RETURNING id""".query[Long].unique
    }
