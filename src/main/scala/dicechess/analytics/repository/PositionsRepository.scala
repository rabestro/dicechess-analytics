package dicechess.analytics.repository

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.util.fragments.whereAndOpt

import dicechess.analytics.Fen
import dicechess.analytics.api.Protocol.{Continuation, PositionContinuations, PositionEquity}

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
    * from the moving side's perspective. The dice key is cased to the position's side to move
    * (white upper-case, black lower-case) and sorted to the stored form.
    */
  def continuations(
      fen: String,
      dice: String,
      mode: Option[String],
      source: Option[String],
      minRating: Option[Int],
      limit: Int
  ): ConnectionIO[PositionContinuations] =
    val nf = Fen.normalize(fen)
    // dice_sorted encodes the side to move by letter case — white pieces upper-case (e.g. BPQ),
    // black lower-case (bpq) — so key the dice to the queried position's active colour.
    val diceKey =
      (if Fen.fields(nf).activeColor == "b" then dice.trim.toLowerCase
       else dice.trim.toUpperCase).sorted
    val where = whereAndOpt(
      Some(fr"p.normalized_fen = $nf"),
      Some(fr"t.dice_sorted = $diceKey"),
      // Exclude no-op turns: the player rolled but never moved (a draw agreed / game abandoned before
      // the move), recorded as a self-loop on the same position. A pure no-op is not a legal move, so
      // it is not a continuation. Legitimate passes flip the side to move (position_after <> position)
      // and are kept.
      Some(fr"t.position_after_id <> t.position_id"),
      mode.map(m => fr"g.mode::text = $m"),
      source.filter(_.trim.nonEmpty).map(s => fr"g.source = ${s.trim}"),
      // Both players at least minRating — a "strong game". Unrated games (NULL) are excluded.
      minRating.map(r => fr"g.white_rating >= $r AND g.black_rating >= $r")
    )
    // `sum(count(*)) OVER ()` is the total games across ALL groups, evaluated before LIMIT, so the
    // reported total stays correct even when more continuations exist than `limit`. `played_moves`
    // is nullable, so the representative ordering is read as Option and missing moves yield [].
    val select =
      fr"""SELECT pa.normalized_fen,
                  (array_agg(array_to_string(t.played_moves, ' ')
                             ORDER BY array_to_string(t.played_moves, ' ')))[1],
                  count(*),
                  count(*) FILTER (WHERE (t.active_color = 'w' AND g.result = 1)
                                      OR (t.active_color = 'b' AND g.result = -1)),
                  count(*) FILTER (WHERE g.result = 0),
                  count(*) FILTER (WHERE (t.active_color = 'w' AND g.result = -1)
                                      OR (t.active_color = 'b' AND g.result = 1)),
                  (sum(count(*)) OVER ())::int
           FROM turns t
           JOIN positions p  ON p.id = t.position_id
           JOIN positions pa ON pa.id = t.position_after_id
           JOIN games g      ON g.id = t.game_id"""
    val query =
      select ++ where ++ fr"GROUP BY pa.normalized_fen ORDER BY count(*) DESC LIMIT $limit"
    query.query[(String, Option[String], Int, Int, Int, Int, Int)].to[List].map { rows =>
      val items = rows.map { case (rfen, movesText, games, wins, draws, losses, _) =>
        val decided = wins + draws + losses
        val winRate = if decided > 0 then (wins + 0.5 * draws) / decided else 0.0
        val moves   = movesText.fold(List.empty[String])(_.split(' ').toList.filter(_.nonEmpty))
        Continuation(rfen, moves, games, wins, draws, losses, winRate)
      }
      PositionContinuations(nf, diceKey, rows.headOption.map(_._7).getOrElse(0), items)
    }

  /** Pre-roll equity for `fen`: the aggregate outcome of every turn played from this position
    * across ALL dice, from the moving side's perspective — the win probability a player weighs
    * before rolling (and before offering a double). Algebraically it is the per-roll continuation
    * win rates averaged over all rolls, each weighted by how often that roll was actually played
    * and decided in the data (empirical weighting, not the theoretical dice distribution), so it
    * stays consistent with the explorer. No-op self-loop turns are excluded exactly as in
    * `continuations`. The aggregate has no GROUP BY, so the query always returns exactly one row
    * (zeros when nothing matched).
    */
  def equity(
      fen: String,
      mode: Option[String],
      source: Option[String],
      minRating: Option[Int]
  ): ConnectionIO[PositionEquity] =
    val nf    = Fen.normalize(fen)
    val side  = Fen.fields(nf).activeColor
    val where = whereAndOpt(
      Some(fr"p.normalized_fen = $nf"),
      Some(fr"t.position_after_id <> t.position_id"),
      mode.map(m => fr"g.mode::text = $m"),
      source.filter(_.trim.nonEmpty).map(s => fr"g.source = ${s.trim}"),
      // Both players at least minRating — a "strong game". Unrated games (NULL) are excluded.
      minRating.map(r => fr"g.white_rating >= $r AND g.black_rating >= $r")
    )
    val select =
      fr"""SELECT count(*),
                  count(*) FILTER (WHERE (t.active_color = 'w' AND g.result = 1)
                                      OR (t.active_color = 'b' AND g.result = -1)),
                  count(*) FILTER (WHERE g.result = 0),
                  count(*) FILTER (WHERE (t.active_color = 'w' AND g.result = -1)
                                      OR (t.active_color = 'b' AND g.result = 1))
           FROM turns t
           JOIN positions p ON p.id = t.position_id
           JOIN games g     ON g.id = t.game_id"""
    (select ++ where).query[(Int, Int, Int, Int)].unique.map { case (games, wins, draws, losses) =>
      val decided = wins + draws + losses
      val winRate = if decided > 0 then (wins + 0.5 * draws) / decided else 0.0
      PositionEquity(nf, side, games, wins, draws, losses, winRate)
    }
