package dicechess.analytics.repository

import java.util.UUID

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dicechess.analytics.api.Protocol.PlayerSummary

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
