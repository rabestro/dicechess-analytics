package dicechess.analytics.repository

import java.time.OffsetDateTime
import java.util.UUID

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dicechess.analytics.api.Protocol.{PlayerStats, PlayerSummary}

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

  /** Aggregate win/loss/draw statistics for one player across all their games.
    *
    * Anchored on the `players` row so existence is decided by the query (a missing player yields
    * `None` → `404`; an existing player with no games yields zeros via the `LEFT JOIN`). Outcomes
    * are attributed by colour: a win is the player on the winning side (White with `result = 1` or
    * Black with `result = -1`), mirrored for losses, `result = 0` for draws — the same
    * `count(*) FILTER (...)` shape as `PositionsRepository.equity`, keyed on the player's id vs
    * `result` instead of `active_color`. `games` counts every game (undecided included); the win
    * rate is over the decided games only. Ratings are the most-recent snapshot per mode, read as
    * correlated scalar subqueries (no extra GROUP BY columns).
    */
  def stats(id: UUID): ConnectionIO[Option[PlayerStats]] =
    sql"""
      SELECT
        p.id, p.username, p.player_type,
        count(g.id),
        count(*) FILTER (WHERE (g.white_player_id = p.id AND g.result =  1)
                            OR (g.black_player_id = p.id AND g.result = -1)),
        count(*) FILTER (WHERE g.result = 0),
        count(*) FILTER (WHERE (g.white_player_id = p.id AND g.result = -1)
                            OR (g.black_player_id = p.id AND g.result =  1)),
        count(*) FILTER (WHERE g.white_player_id = p.id),
        count(*) FILTER (WHERE g.black_player_id = p.id),
        min(g.started_at),
        max(g.started_at),
        (SELECT CASE WHEN gc.white_player_id = p.id THEN gc.white_rating ELSE gc.black_rating END
           FROM games gc
          WHERE (gc.white_player_id = p.id OR gc.black_player_id = p.id) AND gc.mode::text = 'classic'
          ORDER BY gc.started_at DESC NULLS LAST
          LIMIT 1),
        (SELECT CASE WHEN gx.white_player_id = p.id THEN gx.white_rating ELSE gx.black_rating END
           FROM games gx
          WHERE (gx.white_player_id = p.id OR gx.black_player_id = p.id) AND gx.mode::text = 'x2'
          ORDER BY gx.started_at DESC NULLS LAST
          LIMIT 1)
      FROM players p
      LEFT JOIN games g ON (g.white_player_id = p.id OR g.black_player_id = p.id)
      WHERE p.id = $id
      GROUP BY p.id
    """
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
