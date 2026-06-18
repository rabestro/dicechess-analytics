package dicechess.analytics

import java.util.UUID

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import dicechess.analytics.maintenance.TerminalColorRepair
import dicechess.analytics.repository.PositionsRepository

/** Regression test for issue #161.
  *
  * The 2026-06-10 legacy backfill recorded terminal king-capture positions without flipping the
  * side-to-move, so the same physical position existed under both colours and the Openings Explorer
  * showed one continuation as two rows. [[TerminalColorRepair.run]] flips them to the
  * engine-correct twin, re-points the turns/games, reclassifies the games, and deletes the orphaned
  * legacy rows.
  */
class TerminalColorRepairSpec extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  override def afterContainersStart(pg: PostgreSQLContainer): Unit =
    Database.migrate(DbConfig(pg.jdbcUrl, pg.username, pg.password)).unsafeRunSync()

  private def xa(pg: PostgreSQLContainer): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = "org.postgresql.Driver",
      url = pg.jdbcUrl,
      user = pg.username,
      password = pg.password,
      logHandler = None
    )

  // Scenario A — black captures the white king (b4-d3-e1); the engine-correct twin already exists
  // because a modern game reached it. The two games must collapse into one continuation.
  private val startA  = "r1bqkbnr/pppppppp/8/8/1n6/2N5/PPPPPPPP/1RBQKBNR b Kkq -"
  private val aLegacy = "r1bqkbnr/pppppppp/8/8/8/2N5/PPPPPPPP/1RBQnBNR b Kkq -" // not flipped (bug)
  private val aTwin   = "r1bqkbnr/pppppppp/8/8/8/2N5/PPPPPPPP/1RBQnBNR w Kkq -" // engine-correct

  // Scenario B — white captures the black king; no twin exists yet (the common backfill case, so
  // the repair must create it with the engine's fen_hash).
  private val startB  = "rnbqkbnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKBNR w KQkq -"
  private val bLegacy = "rnbq1bnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKBNR w KQkq -" // not flipped (bug)
  private val bTwin   = "rnbq1bnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKBNR b KQkq -" // must be created

  private val legacyA = UUID.fromString("00000000-0000-0000-0000-0000000161a1")
  private val modernA = UUID.fromString("00000000-0000-0000-0000-0000000161a2")
  private val legacyB = UUID.fromString("00000000-0000-0000-0000-0000000161b1")

  private def insertGame(id: UUID, termination: String, result: Int): ConnectionIO[Int] =
    sql"""INSERT INTO games (id, source, result, termination, total_turns)
          VALUES ($id, 'dicechess.com', $result,
                  $termination::game_termination_enum, 1)""".update.run

  private def insertTurn(
      game: UUID,
      color: String,
      dice: String,
      before: Long,
      after: Long,
      moves: List[String]
  ): ConnectionIO[Int] =
    sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                             dice_sorted, played_moves, position_after_id)
          VALUES ($game, 1, $color, $before, $dice, $moves, $after)""".update.run

  private def seed: ConnectionIO[Unit] =
    for
      psA <- PositionsRepository.getOrCreate(startA)
      paL <- PositionsRepository.getOrCreate(aLegacy)
      paT <- PositionsRepository.getOrCreate(aTwin) // modern twin already present
      psB <- PositionsRepository.getOrCreate(startB)
      pbL <- PositionsRepository.getOrCreate(bLegacy)
      _   <- insertGame(legacyA, "unknown", -1)
      _   <- insertGame(modernA, "king_captured", -1)
      _ <- insertGame(legacyB, "unknown", 0) // wrong placeholder result; repair must set it to 1
      _ <- insertTurn(legacyA, "b", "bnn", psA, paL, List("b4d3", "d3e1"))
      _ <- insertTurn(modernA, "b", "bnn", psA, paT, List("b4d3", "d3e1"))
      _ <- insertTurn(legacyB, "w", "N", psB, pbL, List("d1e2"))
    yield ()

  test("repairs legacy terminal king-capture positions: collapse, create twin, idempotent"):
    withContainers { pg =>
      val t                                = xa(pg)
      def conts(fen: String, dice: String) =
        PositionsRepository.continuations(fen, dice, None, None, None, 50).transact(t)
      for
        _       <- seed.transact(t)
        beforeA <- conts(startA, "bnn")
        beforeB <- conts(startB, "n")
        r1      <- TerminalColorRepair.run.transact(t)
        afterA  <- conts(startA, "bnn")
        afterB  <- conts(startB, "n")
        termA   <- sql"SELECT termination::text FROM games WHERE id = $legacyA"
          .query[String]
          .unique
          .transact(t)
        termB <- sql"SELECT termination::text FROM games WHERE id = $legacyB"
          .query[String]
          .unique
          .transact(t)
        resultB  <- sql"SELECT result FROM games WHERE id = $legacyB".query[Int].unique.transact(t)
        twinHash <- sql"SELECT fen_hash FROM positions WHERE normalized_fen = $bTwin"
          .query[Long]
          .option
          .transact(t)
        legacyBGone <- sql"SELECT count(*) FROM positions WHERE normalized_fen = $bLegacy"
          .query[Int]
          .unique
          .transact(t)
        r2     <- TerminalColorRepair.run.transact(t)
        finalA <- conts(startA, "bnn")
      yield
        // Scenario A: the split collapses into one grouped continuation.
        assertEquals(beforeA.items.size, 2)
        assertEquals(afterA.items.size, 1)
        assertEquals(afterA.items.head.games, 2)
        assertEquals(termA, "king_captured")
        // Scenario B: the missing twin is created (with the engine hash) and the turn re-pointed.
        assertEquals(beforeB.items.size, 1)
        assert(beforeB.items.head.fen.endsWith(" w KQkq -"), beforeB.items.head.fen)
        assertEquals(afterB.items.size, 1)
        assert(afterB.items.head.fen.endsWith(" b KQkq -"), afterB.items.head.fen)
        assertEquals(termB, "king_captured")
        assertEquals(resultB, 1)
        assertEquals(twinHash, Some(Fen.hash(bTwin)))
        assertEquals(legacyBGone, 0)
        // Counts, then idempotency: a second run changes nothing.
        assertEquals(r1, TerminalColorRepair.RepairReport(2, 0, 2, 2))
        assertEquals(r2, TerminalColorRepair.RepairReport(0, 0, 0, 0))
        assertEquals(finalA.items.size, 1)
    }
