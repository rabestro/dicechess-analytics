package dicechess.analytics.repository

import java.util.UUID

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dicechess.analytics.api.Protocol.PlayerStatsQuery

/** Shared SQL predicates for the Players-section filters, reused by `PlayersRepository` (stats) and
  * `GamesRepository` (recent games). Fragments reference the `games` alias `g`.
  */
object Filters:

  /** Predicate on `g.initial_stake_amount` (the pot = 2× the site bet) for a stake tier, or `None`
    * for an unknown tier name. Tiers: `free` = `0`/`NULL`, `low` = `1–20` (bet 1–10), `medium` =
    * `21–200` (bet 25–100), `high` = `> 200` (bet 300+).
    */
  def stakePredicate(tier: String): Option[Fragment] = tier match
    case "free"   => Some(fr"(g.initial_stake_amount IS NULL OR g.initial_stake_amount = 0)")
    case "low"    => Some(fr"g.initial_stake_amount BETWEEN 1 AND 20")
    case "medium" => Some(fr"g.initial_stake_amount BETWEEN 21 AND 200")
    case "high"   => Some(fr"g.initial_stake_amount > 200")
    case _        => None

  /** The optional player-perspective filter predicates (mode / colour / opponent type / opponent /
    * stake / date) shared by the player stats and breakdowns queries. They reference the `games`
    * alias `g` and the opponent alias `opp`; an opponent predicate is emitted only when set, so the
    * caller need only join `opp` when one is present.
    */
  def playerFilters(pid: UUID, q: PlayerStatsQuery): List[Option[Fragment]] = List(
    q.mode.map(m => fr"g.mode::text = $m"),
    q.color.map(c =>
      if c == "w" then fr"g.white_player_id = $pid" else fr"g.black_player_id = $pid"
    ),
    q.opponentType.map(t => fr"opp.player_type = $t"),
    q.opponentId.map(o => fr"opp.id = $o"),
    q.stake.flatMap(stakePredicate),
    q.dateFrom.map(d => fr"g.started_at >= $d"),
    q.dateTo.map(d => fr"g.started_at < ${d.plusDays(1)}")
  )
