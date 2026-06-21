package dicechess.analytics.repository

import java.time.OffsetDateTime
import java.util.UUID

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dicechess.analytics.api.Protocol.{
  BreakdownRow,
  PlayerBreakdowns,
  PlayerStats,
  PlayerStatsQuery,
  PlayerSummary
}

/** Read access to the `players` table.
  *
  * `rating_classic` is not stored on the player: ratings exist only as per-game snapshots
  * (`games.white_rating` / `games.black_rating`). The player's current rating is therefore the
  * snapshot from their most recent game, fetched via a lateral join.
  */
object PlayersRepository:

  private val ratingLateral =
    fr"""
      LEFT JOIN LATERAL (
        SELECT CASE
                 WHEN g.white_player_id = p.id THEN g.white_rating
                 ELSE g.black_rating
               END AS rating
        FROM games g
        WHERE (g.white_player_id = p.id OR g.black_player_id = p.id)
        ORDER BY g.started_at DESC NULLS LAST
        LIMIT 1
      ) r ON TRUE
    """

  def list(username: Option[String], limit: Int): ConnectionIO[List[PlayerSummary]] =
    val where = Fragments.whereAndOpt(
      username.map(u => fr"p.username ILIKE ${s"%$u%"}")
    )
    (fr"""
      SELECT p.id, p.username, p.player_type, r.rating
      FROM players p
    """ ++ ratingLateral ++ where ++
      fr"ORDER BY r.rating DESC NULLS LAST LIMIT $limit")
      .query[PlayerSummary]
      .to[List]

  def get(id: UUID): ConnectionIO[Option[PlayerSummary]] =
    (fr"""
      SELECT p.id, p.username, p.player_type, r.rating
      FROM players p
    """ ++ ratingLateral ++ fr"WHERE p.id = $id")
      .query[PlayerSummary]
      .option

  /** Aggregate win/loss/draw statistics for one player, optionally filtered.
    *
    * Anchored on the `players` row so existence is decided by the query (a missing player yields
    * `None` → `404`; an existing player with no games yields zeros via the `LEFT JOIN`). Outcomes
    * are attributed by colour: a win is the player on the winning side (White with `result = 1` or
    * Black with `result = -1`), mirrored for losses, `result = 0` for draws — the same
    * `count(*) FILTER (...)` shape as `PositionsRepository.equity`, keyed on the player's id vs
    * `result`. `games` counts every game (undecided included); the win rate is over the decided
    * games only.
    *
    * The optional filters (mode / colour / opponent type / opponent / stake / date) are AND-ed into
    * every aggregate's `FILTER`, so the counts and history bounds reflect the filtered slice while
    * the per-mode rating `array_agg`s stay UNFILTERED — the header rating is identity and must not
    * change when the slice does. The opponent is joined via a `CASE` to the non-focal side.
    * `g.id IS NOT NULL` is the base predicate so a zero-games player stays at zero even unfiltered.
    */
  def stats(q: PlayerStatsQuery): ConnectionIO[Option[PlayerStats]] =
    val pid = q.playerId
    // Base predicate keeps a zero-games player (the all-NULL LEFT JOIN row) at zero; the shared
    // player filters are AND-ed in. The list is always non-empty, so `reduce` is safe.
    val fp = (Some(fr"g.id IS NOT NULL") :: Filters.playerFilters(pid, q)).flatten
      .reduce(_ ++ fr" AND " ++ _)
    val win =
      fr"((g.white_player_id = $pid AND g.result = 1) OR (g.black_player_id = $pid AND g.result = -1))"
    val loss =
      fr"((g.white_player_id = $pid AND g.result = -1) OR (g.black_player_id = $pid AND g.result = 1))"
    val rating =
      fr"CASE WHEN g.white_player_id = $pid THEN g.white_rating ELSE g.black_rating END"
    val select =
      fr"SELECT p.id, p.username, p.player_type," ++
        fr"count(*) FILTER (WHERE" ++ fp ++ fr")," ++
        fr"count(*) FILTER (WHERE" ++ fp ++ fr" AND" ++ win ++ fr")," ++
        fr"count(*) FILTER (WHERE" ++ fp ++ fr" AND g.result = 0)," ++
        fr"count(*) FILTER (WHERE" ++ fp ++ fr" AND" ++ loss ++ fr")," ++
        fr"count(*) FILTER (WHERE" ++ fp ++ fr" AND g.white_player_id = $pid)," ++
        fr"count(*) FILTER (WHERE" ++ fp ++ fr" AND g.black_player_id = $pid)," ++
        fr"min(g.started_at) FILTER (WHERE" ++ fp ++ fr")," ++
        fr"max(g.started_at) FILTER (WHERE" ++ fp ++ fr")," ++
        fr"(array_agg(" ++ rating ++
        fr" ORDER BY g.started_at DESC NULLS LAST) FILTER (WHERE g.mode::text = 'classic'))[1]," ++
        fr"(array_agg(" ++ rating ++
        fr" ORDER BY g.started_at DESC NULLS LAST) FILTER (WHERE g.mode::text = 'x2'))[1]"
    // Only join the opponent when an opponent filter is active — it is the only consumer, and on a
    // heavy player (50k+ games) the extra join is not free.
    val oppJoin =
      if q.opponentType.isDefined || q.opponentId.isDefined then
        fr"""LEFT JOIN players opp ON opp.id = CASE WHEN g.white_player_id = p.id
                                                    THEN g.black_player_id ELSE g.white_player_id END"""
      else fr""
    val from =
      fr"FROM players p" ++
        fr"LEFT JOIN games g ON (g.white_player_id = p.id OR g.black_player_id = p.id)" ++
        oppJoin ++
        fr"WHERE p.id = $pid GROUP BY p.id"
    (select ++ from)
      .query[
        (
            UUID,
            Option[String],
            String,
            Int,
            Int,
            Int,
            Int,
            Int,
            Int,
            Option[OffsetDateTime],
            Option[OffsetDateTime],
            Option[Int],
            Option[Int]
        )
      ]
      .option
      .map(_.map {
        case (
              pid,
              username,
              playerType,
              games,
              wins,
              draws,
              losses,
              asWhite,
              asBlack,
              firstGame,
              lastGame,
              ratingClassic,
              ratingX2
            ) =>
          val decided = wins + draws + losses
          val winRate = if decided > 0 then (wins + 0.5 * draws) / decided else 0.0
          PlayerStats(
            id = pid,
            username = username,
            playerType = playerType,
            games = games,
            wins = wins,
            draws = draws,
            losses = losses,
            decided = decided,
            winRate = winRate,
            asWhite = asWhite,
            asBlack = asBlack,
            firstGame = firstGame,
            lastGame = lastGame,
            ratingClassic = ratingClassic,
            ratingX2 = ratingX2
          )
      })

  /** Win-rate breakdowns for one player by colour, mode and opponent type, plus the mean number of
    * turns — all over the same filtered slice as [[stats]]. Returns `None` (→ `404`) when the
    * player does not exist; empty breakdown lists when no game matches the filters.
    *
    * A single scan builds the filtered CTE `gf` (each game tagged with the player's colour, mode,
    * opponent type and player-perspective result); a `LATERAL VALUES` then explodes each game into
    * one (dimension, key) row, so the win/draw/loss aggregates are written once and grouped per
    * (dim, key). The opponent is joined in `gf` because the opponent-type breakdown needs it.
    */
  def breakdowns(q: PlayerStatsQuery): ConnectionIO[Option[PlayerBreakdowns]] =
    val pid      = q.playerId
    val filtered =
      fr"WHERE " ++ (Some(fr"(g.white_player_id = $pid OR g.black_player_id = $pid)") ::
        Filters.playerFilters(pid, q)).flatten.reduce(_ ++ fr" AND " ++ _)
    val oppJoin =
      fr"""LEFT JOIN players opp ON opp.id = CASE WHEN g.white_player_id = $pid
                                                  THEN g.black_player_id ELSE g.white_player_id END"""
    // Each game is exploded into one (dimension, key) row per dimension via a LATERAL VALUES, so the
    // win/draw/loss FILTER aggregates are written once and grouped per (dim, key) — no repeated
    // per-dimension SELECT blocks. The opponent join is always present here (the opponent-type
    // dimension needs it); rows with a NULL key (a game with no opponent) are dropped.
    val rowsQuery =
      (fr"""
        WITH gf AS (
          SELECT
            (CASE WHEN g.white_player_id = $pid THEN 'w' ELSE 'b' END) AS my_color,
            g.mode::text AS mode,
            opp.player_type AS opp_type,
            (CASE WHEN (g.white_player_id = $pid AND g.result =  1)
                    OR (g.black_player_id = $pid AND g.result = -1) THEN 1
                  WHEN g.result = 0 THEN 0
                  WHEN (g.white_player_id = $pid AND g.result = -1)
                    OR (g.black_player_id = $pid AND g.result =  1) THEN -1
                  ELSE NULL END) AS my_result
          FROM games g
          """ ++ oppJoin ++ fr"""
          """ ++ filtered ++ fr"""
        )
        SELECT d.dim, d.key, count(*) AS games,
               count(*) FILTER (WHERE my_result =  1) AS wins,
               count(*) FILTER (WHERE my_result =  0) AS draws,
               count(*) FILTER (WHERE my_result = -1) AS losses
        FROM gf
        CROSS JOIN LATERAL (VALUES ('color', gf.my_color),
                                   ('mode', gf.mode),
                                   ('opponent_type', gf.opp_type)) AS d(dim, key)
        WHERE d.key IS NOT NULL
        GROUP BY d.dim, d.key
      """).query[(String, String, Int, Int, Int, Int)]
    // avg doesn't group by opponent, so only join it when an opponent filter actually needs it.
    val avgOppJoin = if q.opponentType.isDefined || q.opponentId.isDefined then oppJoin else fr""
    val avgQuery   =
      (fr"SELECT avg(g.total_turns)::float8 FROM games g" ++ avgOppJoin ++ fr" " ++ filtered)
        .query[Option[Double]]
    for
      exists <- sql"SELECT EXISTS(SELECT 1 FROM players WHERE id = $pid)".query[Boolean].unique
      rows   <-
        if exists then rowsQuery.to[List]
        else List.empty[(String, String, Int, Int, Int, Int)].pure[ConnectionIO]
      avg <- if exists then avgQuery.unique else Option.empty[Double].pure[ConnectionIO]
    yield
      if !exists then None
      else
        val byDim = rows
          .map { case (dim, key, games, wins, draws, losses) =>
            val decided = wins + draws + losses
            val winRate = if decided > 0 then (wins + 0.5 * draws) / decided else 0.0
            dim -> BreakdownRow(key, games, wins, draws, losses, winRate)
          }
          .groupMap(_._1)(_._2)
        def ordered(dim: String, keys: List[String]): List[BreakdownRow] =
          byDim
            .getOrElse(dim, Nil)
            .sortBy(r =>
              keys.indexOf(r.key) match
                case -1 => Int.MaxValue
                case i  => i
            )
        Some(
          PlayerBreakdowns(
            byColor = ordered("color", List("w", "b")),
            byMode = ordered("mode", List("classic", "x2")),
            byOpponentType = ordered("opponent_type", List("human", "bot")),
            avgTurns = avg
          )
        )
