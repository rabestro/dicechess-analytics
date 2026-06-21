package dicechess.analytics.repository

import doobie.*
import doobie.implicits.*

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
