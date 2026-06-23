package dicechess.analytics.repository

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

import dicechess.analytics.Fen
import dicechess.analytics.api.Protocol.{
  Continuation,
  DiceDistributionRow,
  PositionContinuations,
  PositionDiceDistribution,
  PositionEquity
}

/** Access to the deduplicated `positions` table. */
object PositionsRepository:

  /** A turn counts toward a position's aggregates only when it is "complete": the side to move
    * flips (a normal turn or a legal pass), OR the move captured a king — the resulting position
    * `pa` is missing a king, a genuine terminal move (including unflipped/placeholder legacy
    * backfill rows). This drops abandoned partial turns (one micro-move then a draw agreed) that
    * leave both kings on the board without flipping the side, plus no-op self-loops. Requires the
    * queried position joined as `p` and `position_after` as `pa`.
    */
  private val completedTurn: Fragment =
    fr"(pa.active_color <> p.active_color OR pa.piece_placement NOT LIKE '%K%' OR pa.piece_placement NOT LIKE '%k%')"

  /** `count(*)` plus win/draw/loss tallies from the moving side's perspective — the outcome columns
    * shared by `continuations`, `equity` and `diceDistribution`.
    */
  private val outcomeCounts: Fragment =
    fr"""count(*),
         count(*) FILTER (WHERE (t.active_color = 'w' AND g.result = 1)
                             OR (t.active_color = 'b' AND g.result = -1)),
         count(*) FILTER (WHERE g.result = 0),
         count(*) FILTER (WHERE (t.active_color = 'w' AND g.result = -1)
                             OR (t.active_color = 'b' AND g.result = 1))"""

  /** Turns joined to their before/after positions (`p` / `pa`) and game (`g`). */
  private val turnsJoin: Fragment =
    fr"""FROM turns t
         JOIN positions p  ON p.id = t.position_id
         JOIN positions pa ON pa.id = t.position_after_id
         JOIN games g      ON g.id = t.game_id"""

  /** Filters common to every position aggregate: the position, the completed-turn guard and the
    * mode / source / min-rating session filters. `continuations` adds the dice condition on top.
    */
  private def baseFilters(
      nf: String,
      mode: Option[String],
      source: Option[String],
      minRating: Option[Int]
  ): List[Option[Fragment]] =
    List(
      Some(fr"p.normalized_fen = $nf"),
      // Drop no-op self-loops and abandoned partial turns; keep complete turns and terminal captures.
      Some(fr"t.position_after_id <> t.position_id"),
      Some(completedTurn),
      mode.map(m => fr"g.mode::text = $m"),
      source.filter(_.trim.nonEmpty).map(s => fr"g.source = ${s.trim}"),
      // Both players at least minRating — a "strong game". Unrated games (NULL) are excluded.
      minRating.map(r => fr"g.white_rating >= $r AND g.black_rating >= $r")
    )

  /** `WHERE a AND b AND …` over the present (Some) conditions; empty when none. */
  private def whereOf(conds: List[Option[Fragment]]): Fragment =
    conds.flatten match
      case Nil       => Fragment.empty
      case f :: rest => rest.foldLeft(fr"WHERE" ++ f)((acc, c) => acc ++ fr"AND" ++ c)

  /** Mean game score (win 1, draw ½, loss 0) over decided games; 0.0 when nothing is decided. */
  private def winRate(wins: Int, draws: Int, losses: Int): Double =
    val decided = wins + draws + losses
    if decided > 0 then (wins + 0.5 * draws) / decided else 0.0

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
    val where =
      whereOf(Some(fr"t.dice_sorted = $diceKey") :: baseFilters(nf, mode, source, minRating))
    // `sum(count(*)) OVER ()` is the total games across ALL groups, evaluated before LIMIT, so the
    // reported total stays correct even when more continuations exist than `limit`. `played_moves`
    // is nullable, so the representative ordering is read as Option and missing moves yield [].
    val select =
      fr"""SELECT pa.normalized_fen,
                  (array_agg(array_to_string(t.played_moves, ' ')
                             ORDER BY array_to_string(t.played_moves, ' ')))[1], """ ++
        outcomeCounts ++ fr", (sum(count(*)) OVER ())::int" ++ turnsJoin
    val query =
      select ++ where ++ fr"GROUP BY pa.normalized_fen ORDER BY count(*) DESC LIMIT $limit"
    query.query[(String, Option[String], Int, Int, Int, Int, Int)].to[List].map { rows =>
      val items = rows.map { case (rfen, movesText, games, wins, draws, losses, _) =>
        val moves = movesText.fold(List.empty[String])(_.split(' ').toList.filter(_.nonEmpty))
        Continuation(rfen, moves, games, wins, draws, losses, winRate(wins, draws, losses))
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
    val nf     = Fen.normalize(fen)
    val side   = Fen.fields(nf).activeColor
    val where  = whereOf(baseFilters(nf, mode, source, minRating))
    val select = fr"SELECT " ++ outcomeCounts ++ turnsJoin
    (select ++ where).query[(Int, Int, Int, Int)].unique.map { case (games, wins, draws, losses) =>
      PositionEquity(nf, side, games, wins, draws, losses, winRate(wins, draws, losses))
    }

  /** Distribution of every dice roll played from `fen`, grouped by the stored dice code and ranked
    * by frequency. Win/draw/loss are from the moving side's perspective, identical to
    * `continuations`; this is `equity` broken out per roll (so a client can fetch all 56 rolls in
    * one request instead of fanning out). No-op self-loop turns are excluded exactly as elsewhere;
    * the dice code is upper-cased so the side to move does not change its presentation.
    */
  def diceDistribution(
      fen: String,
      mode: Option[String],
      source: Option[String],
      minRating: Option[Int]
  ): ConnectionIO[PositionDiceDistribution] =
    val nf     = Fen.normalize(fen)
    val side   = Fen.fields(nf).activeColor
    val where  = whereOf(baseFilters(nf, mode, source, minRating))
    val select = fr"SELECT t.dice_sorted, " ++ outcomeCounts ++ turnsJoin
    val query  = select ++ where ++ fr"GROUP BY t.dice_sorted ORDER BY count(*) DESC"
    query.query[(String, Int, Int, Int, Int)].to[List].map { rows =>
      val items = rows.map { case (dice, games, wins, draws, losses) =>
        DiceDistributionRow(
          dice.toUpperCase,
          games,
          wins,
          draws,
          losses,
          winRate(wins, draws, losses)
        )
      }
      PositionDiceDistribution(nf, side, items.map(_.games).sum, items)
    }
