package dicechess.analytics.repository

import java.time.OffsetDateTime
import java.util.UUID

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dicechess.analytics.api.Protocol.{PlayerStats, PlayerStatsQuery, PlayerSummary}

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
    val fp  = List(
      Some(fr"g.id IS NOT NULL"),
      q.mode.map(m => fr"g.mode::text = $m"),
      q.color.map(c =>
        if c == "w" then fr"g.white_player_id = $pid" else fr"g.black_player_id = $pid"
      ),
      q.opponentType.map(t => fr"opp.player_type = $t"),
      q.opponentId.map(o => fr"opp.id = $o"),
      q.stake.flatMap(Filters.stakePredicate),
      q.dateFrom.map(d => fr"g.started_at >= $d"),
      q.dateTo.map(d => fr"g.started_at < ${d.plusDays(1)}")
    ).flatten.reduce(_ ++ fr" AND " ++ _)
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
