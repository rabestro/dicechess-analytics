package dicechess.analytics.repository

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.util.fragments.whereAndOpt

import dicechess.analytics.Fen
import dicechess.analytics.api.Protocol.{Continuation, PositionContinuations}

/** Access to the deduplicated `positions` table. */
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

  /** Continuations played from `fen` after rolling `dice`, grouped by the resulting position so
    * that permutations of the micro-moves collapse, ranked by frequency. Win/draw/loss counts are
    * from the moving side's perspective. The dice key is upper-cased and sorted to the stored form.
    */
  def continuations(
      fen: String,
      dice: String,
      mode: Option[String],
      limit: Int
  ): ConnectionIO[PositionContinuations] =
    val nf      = Fen.normalize(fen)
    val diceKey = dice.trim.toUpperCase.sorted
    val where   = whereAndOpt(
      Some(fr"p.normalized_fen = $nf"),
      Some(fr"t.dice_sorted = $diceKey"),
      mode.map(m => fr"g.mode::text = $m")
    )
    val select =
      fr"""SELECT pa.normalized_fen,
                  (array_agg(array_to_string(t.played_moves, ' ')
                             ORDER BY array_to_string(t.played_moves, ' ')))[1],
                  count(*),
                  count(*) FILTER (WHERE (t.active_color = 'w' AND g.result = 1)
                                      OR (t.active_color = 'b' AND g.result = -1)),
                  count(*) FILTER (WHERE g.result = 0),
                  count(*) FILTER (WHERE (t.active_color = 'w' AND g.result = -1)
                                      OR (t.active_color = 'b' AND g.result = 1))
           FROM turns t
           JOIN positions p  ON p.id = t.position_id
           JOIN positions pa ON pa.id = t.position_after_id
           JOIN games g      ON g.id = t.game_id"""
    val query =
      select ++ where ++ fr"GROUP BY pa.normalized_fen ORDER BY count(*) DESC LIMIT $limit"
    query.query[(String, String, Int, Int, Int, Int)].to[List].map { rows =>
      val items = rows.map { case (rfen, movesText, games, wins, draws, losses) =>
        val decided = wins + draws + losses
        val winRate = if decided > 0 then (wins + 0.5 * draws) / decided else 0.0
        Continuation(rfen, movesText.split(' ').toList, games, wins, draws, losses, winRate)
      }
      PositionContinuations(nf, diceKey, items.map(_.games).sum, items)
    }
