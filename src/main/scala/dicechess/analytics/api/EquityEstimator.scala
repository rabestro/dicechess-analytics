package dicechess.analytics.api

import scala.util.Random

import dicechess.engine.domain.*
import dicechess.engine.search.{MonteCarloConfig, MonteCarloEquity}

import dicechess.analytics.Fen
import dicechess.analytics.api.Protocol.PositionEquity

/** Monte-Carlo fallback for position equity.
  *
  * When the database sample for a position is too small to trust, the win rate is estimated on
  * demand from the engine's Rao-Blackwellized Monte-Carlo estimator instead of returned as a
  * near-empty empirical rate. The estimate is **deterministic per position** (seeded by the
  * position hash) and **bounded** by a fixed rollout budget, so latency is predictable and
  * responses are reproducible. The `games`/`wins`/`draws`/`losses` counts still reflect the
  * (sparse) database sample; only `winRate`/`source`/`standardError` change.
  */
object EquityEstimator:

  /** Default decided-game floor below which the Monte-Carlo fallback engages. */
  val DefaultMinDecided = 30

  // Bounded so an on-demand estimate inside a request stays fast; seeded per position for reproducibility.
  private val McConfig = MonteCarloConfig(rollouts = 200, maxPlies = 30)

  /** Returns `db` flagged `source = "db"` when it has at least `minDecided` (default
    * [[DefaultMinDecided]]) decided games, otherwise a Monte-Carlo estimate (`source = "mc"`,
    * `standardError` set). Falls back to the database row if the FEN cannot be parsed.
    */
  def withFallback(db: PositionEquity, minDecided: Option[Int]): PositionEquity =
    val decided   = db.wins + db.draws + db.losses
    val threshold = minDecided.getOrElse(DefaultMinDecided)
    // db already carries source = "db" (the default), so return it as-is when the sample suffices
    // or when the FEN cannot be parsed for an estimate.
    if decided >= threshold then db
    else estimate(db).getOrElse(db)

  private def estimate(db: PositionEquity): Option[PositionEquity] =
    // db.fen is the normalized 4-field FEN; FenParser needs the half/full-move fields appended.
    FenParser.parse(s"${db.fen} 0 1").toOption.map { state =>
      val est     = MonteCarloEquity.estimate(state, McConfig, new Random(Fen.hash(db.fen)))
      val winRate = if state.activeColor.isWhite then est.whiteWin else est.blackWin
      db.copy(winRate = winRate, source = "mc", standardError = Some(est.standardError))
    }
