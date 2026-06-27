package dicechess.analytics.maintenance

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

import dicechess.analytics.Fen
import dicechess.analytics.ingest.{GameReplay, TurnInput}
import dicechess.analytics.repository.PositionsRepository

/** Maintenance script to identify and repair partial/aborted turns that were saved in the database
  * with flipped active colors.
  */
object PartialTurnsRepair:

  final case class RepairReport(
      turnsScanned: Int,
      turnsRepointed: Int,
      finalsRepointed: Int,
      orphansDeleted: Int
  )

  final case class Candidate(
      turnId: Long,
      gameId: UUID,
      playedMoves: List[String],
      diceSorted: String,
      beforeFen: String,
      afterFen: String,
      termination: String,
      expectedColor: String
  )

  def run(xa: Transactor[IO]): IO[RepairReport] =
    for
      candidates <- loadCandidates.transact(xa)
      _          <- IO.println(s"[partial-turns-repair] Found ${candidates.size} candidates to analyze.")
      repointed <- candidates.foldLeftM(0) { (acc, c) =>
        processCandidate(c, xa).map {
          case true  => acc + 1
          case false => acc
        }
      }
      orphansDeleted <- deleteOrphans.transact(xa)
    yield RepairReport(candidates.size, repointed, repointed, orphansDeleted)

  private val loadCandidates: ConnectionIO[List[Candidate]] =
    sql"""SELECT t.id, t.game_id, t.played_moves, t.dice_sorted,
                 p.normalized_fen, pa.normalized_fen, g.termination::text, p.active_color
          FROM turns t
          JOIN positions p ON p.id = t.position_id
          JOIN positions pa ON pa.id = t.position_after_id
          JOIN games g ON g.id = t.game_id
          WHERE t.turn_number = g.total_turns
            AND cardinality(t.played_moves) > 0
            AND cardinality(t.played_moves) < 3
            AND pa.active_color <> p.active_color
       """.query[Candidate].to[List]

  private val deleteOrphans =
    sql"""DELETE FROM positions p
          WHERE NOT EXISTS (SELECT 1 FROM turns t WHERE t.position_id = p.id)
            AND NOT EXISTS (SELECT 1 FROM turns t WHERE t.position_after_id = p.id)
            AND NOT EXISTS (SELECT 1 FROM games g WHERE g.initial_position_id = p.id)
            AND NOT EXISTS (SELECT 1 FROM games g WHERE g.final_position_id = p.id)
            AND NOT EXISTS (SELECT 1 FROM opening_book_favorites f
                            WHERE f.normalized_fen = p.normalized_fen)""".update.run

  private def processCandidate(c: Candidate, xa: Transactor[IO]): IO[Boolean] =
    val pieces = Array('p', 'n', 'b', 'r', 'q', 'k')
    val dice   = c.diceSorted.toLowerCase.toList.map(char => pieces.indexOf(char) + 1)
    val turnInput = TurnInput(dice, c.playedMoves)

    val replayResult = GameReplay.replay(
      c.beforeFen,
      List(turnInput),
      Some("timeout")
    )

    replayResult match
      case Left(err) =>
        IO.println(s"[partial-turns-repair] Replay failed for turn ${c.turnId}: $err").as(false)
      case Right(game) =>
        val replayedAfterFen = game.turns.head.afterFen
        val isDifferent      = Fen.normalize(replayedAfterFen) != Fen.normalize(c.afterFen)

        if isDifferent then
          val repairIO = for
            newPositionId <- PositionsRepository.getOrCreate(replayedAfterFen)
            _ <- sql"UPDATE turns SET position_after_id = $newPositionId WHERE id = ${c.turnId}".update.run
            _ <- sql"UPDATE games SET final_position_id = $newPositionId WHERE id = ${c.gameId}".update.run
          yield ()
          repairIO.transact(xa).as(true)
        else
          IO.pure(false)
