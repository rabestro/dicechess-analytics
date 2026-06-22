package dicechess.analytics.maintenance

import cats.effect.{IO, IOApp}
import doobie.implicits.*
import scala.util.Random

import dicechess.analytics.{AppConfig, Database, Fen}
import dicechess.engine.domain.FenParser
import dicechess.engine.search.{MonteCarloConfig, MonteCarloEquity}

/** A one-off script to evaluate the accuracy of the Monte Carlo engine against empirical DB stats.
  * It fetches the top N most played positions, calculates their win rate from the database,
  * runs the engine's Monte Carlo estimation on them, and prints a comparison table.
  */
object EvaluateMonteCarloApp extends IOApp:

  def run(args: List[String]): IO[cats.effect.ExitCode] =
    val topNPositions = args.headOption.flatMap(_.toIntOption).getOrElse(10)
    val rollouts      = args.drop(1).headOption.flatMap(_.toIntOption).getOrElse(200)

    for
      config <- IO.fromEither(AppConfig.load().left.map(msg => IllegalArgumentException(msg)))
      _      <- IO.println(s"Evaluating Monte Carlo equity for top $topNPositions positions ($rollouts rollouts)...")
      _      <- Database.transactor(config.db, 4).use { xa =>
        for
          positions <- getTopPositions(topNPositions).transact(xa)
          _         <- printTable(positions, rollouts)
        yield ()
      }
    yield cats.effect.ExitCode.Success

  case class PositionStats(
      normalizedFen: String,
      games: Int,
      wins: Int,
      draws: Int,
      losses: Int
  ):
    def winRate: Double =
      val decided = wins + draws + losses
      if decided > 0 then (wins + 0.5 * draws) / decided else 0.0

  def getTopPositions(limit: Int): doobie.ConnectionIO[List[PositionStats]] =
    sql"""SELECT p.normalized_fen,
                 count(*) as games,
                 count(*) FILTER (WHERE (t.active_color = 'w' AND g.result = 1)
                                     OR (t.active_color = 'b' AND g.result = -1)),
                 count(*) FILTER (WHERE g.result = 0),
                 count(*) FILTER (WHERE (t.active_color = 'w' AND g.result = -1)
                                     OR (t.active_color = 'b' AND g.result = 1))
          FROM turns t
          JOIN positions p ON p.id = t.position_id
          JOIN games g     ON g.id = t.game_id
          WHERE t.position_after_id <> t.position_id
          GROUP BY p.normalized_fen
          HAVING count(*) > 0
          ORDER BY count(*) DESC
          LIMIT $limit
       """
      .query[(String, Int, Int, Int, Int)]
      .map { case (fen, g, w, d, l) => PositionStats(fen, g, w, d, l) }
      .to[List]

  def printTable(positions: List[PositionStats], rollouts: Int): IO[Unit] = IO {
    val rng = new Random()
    val mcConfig = MonteCarloConfig(rollouts = rollouts, maxPlies = 60)

    println(f"${"DFEN"}%-75s | ${"Games"}%7s | ${"DB WR"}%7s | ${"MC WR"}%7s | ${"Delta"}%7s")
    println("-" * 115)

    positions.foreach { stat =>
      val dbWinRate = stat.winRate

      val mcWinRate = FenParser.parse(stat.normalizedFen) match
        case Left(err) => 
          println(s"Error parsing FEN ${stat.normalizedFen}: $err")
          0.0
        case Right(state) =>
          // Active color is moving. MC whiteWin returns win chance for side-to-move because engine assumes
          // the turn is starting. But wait, `MonteCarloEquity.estimate` returns `whiteWin` and `blackWin`
          // absolutely, or relative to side to move?
          // Looking at engine API: est.whiteWin, est.blackWin. So it's absolute.
          // In the database, PositionEquity calculates win rate from the moving side's perspective.
          // Wait, `stat.winRate` here calculates from the perspective of `t.active_color`.
          // Let's verify: if t.active_color = 'w', win means g.result = 1. So it's side-to-move win rate.
          val est = MonteCarloEquity.estimate(state, mcConfig, rng)
          val side = Fen.fields(stat.normalizedFen).activeColor
          if side == "w" then est.whiteWin else est.blackWin

      val delta = (mcWinRate - dbWinRate).abs
      println(f"${stat.normalizedFen}%-75s | ${stat.games}%7d | ${dbWinRate * 100}%6.2f%% | ${mcWinRate * 100}%6.2f%% | ${delta * 100}%6.2f%%")
    }
    
    println("-" * 115)
    println("Done.")
  }
